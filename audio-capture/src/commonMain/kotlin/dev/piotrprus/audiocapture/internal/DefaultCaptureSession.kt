package dev.piotrprus.audiocapture.internal

import dev.piotrprus.audiocapture.AudioCaptureException
import dev.piotrprus.audiocapture.AudioChunk
import dev.piotrprus.audiocapture.AudioLevel
import dev.piotrprus.audiocapture.CaptureConfig
import dev.piotrprus.audiocapture.CaptureSession
import dev.piotrprus.audiocapture.CaptureState
import dev.piotrprus.audiocapture.InputDevice
import dev.piotrprus.audiocapture.InterruptionMode
import dev.piotrprus.audiocapture.PauseReason
import dev.piotrprus.audiocapture.Pcm
import dev.piotrprus.audiocapture.PcmEncoding
import dev.piotrprus.audiocapture.PcmFormat
import dev.piotrprus.audiocapture.Recording
import dev.piotrprus.audiocapture.VoiceProcessing
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration.Companion.microseconds

/**
 * Common session logic over a platform [CaptureEngine].
 *
 * Everything that changes state — audio from the engine's thread, interruptions, calls from the
 * app — goes through one channel and is handled by one coroutine, so there is no locking and
 * events apply in the order they happened. Audio that arrives while the session is not recording
 * is dropped; that is also how an Android interruption is paused in [InterruptionMode.PauseResume],
 * since the engine keeps running there to learn when the microphone comes back.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class DefaultCaptureSession(
    private val config: CaptureConfig,
    private val engine: CaptureEngine,
    private val writer: AudioFileWriter?,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CaptureSession {

    override val format: PcmFormat = PcmFormat(
        sampleRate = config.sampleRate,
        channels = config.channels,
        encoding = config.stream?.encoding ?: PcmEncoding.Int16,
    )

    private val _state = MutableStateFlow<CaptureState>(CaptureState.Recording)
    override val state: StateFlow<CaptureState> = _state.asStateFlow()

    private val _level = MutableStateFlow(AudioLevel.Silence)
    override val level: StateFlow<AudioLevel> = _level.asStateFlow()

    override val inputDevice: InputDevice? get() = engine.routedDevice

    override val voiceProcessing: VoiceProcessing? get() = engine.appliedVoiceProcessing

    private val output = Channel<AudioChunk>(
        capacity = config.stream?.bufferedChunks ?: 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val collected = AtomicBoolean(false)
    override val chunks: Flow<AudioChunk> = flow {
        // A second collector would silently receive every other chunk; fail loudly instead.
        check(collected.compareAndSet(expectedValue = false, newValue = true)) {
            "CaptureSession.chunks can only be collected once. Share it with shareIn() for several consumers."
        }
        emitAll(output.receiveAsFlow())
    }

    private val events = Channel<Event>(Channel.UNLIMITED)
    private val pendingAudio = AtomicInt(0)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val chunkSamples = config.framesPerChunk * config.channels
    private val pending = FloatArray(chunkSamples)
    private var pendingCount = 0
    private var framesEmitted = 0L
    private var result: Recording? = null

    private val listener = object : CaptureEngine.Listener {
        override fun onAudio(samples: FloatArray) {
            // A stalled file writer must not grow the queue without bound: past a few seconds of
            // backlog the newest audio is dropped rather than the process running out of memory.
            if (pendingAudio.incrementAndFetch() > MAX_PENDING_AUDIO) {
                pendingAudio.decrementAndFetch()
                return
            }
            events.trySend(Event.Audio(samples))
        }

        override fun onInterruptionBegan() {
            events.trySend(Event.InterruptionBegan)
        }

        override fun onInterruptionEnded(shouldResume: Boolean) {
            events.trySend(Event.InterruptionEnded(shouldResume))
        }

        override fun onError(error: AudioCaptureException) {
            events.trySend(Event.Failed(error))
        }
    }

    /** Opens the microphone. On failure nothing is left behind: no file, no running coroutine. */
    fun begin() {
        try {
            engine.start(listener)
        } catch (e: Throwable) {
            runCatching { engine.stop() }
            writer?.delete()
            scope.cancel()
            throw e as? AudioCaptureException ?: AudioCaptureException("Could not open the microphone", e)
        }
        scope.launch { process() }
    }

    override fun pause() {
        events.trySend(Event.Pause)
    }

    override fun resume() {
        events.trySend(Event.Resume)
    }

    override suspend fun stop(): Recording? = finish(discard = false)

    override suspend fun cancel() {
        finish(discard = true)
    }

    private suspend fun finish(discard: Boolean): Recording? {
        val reply = CompletableDeferred<Recording?>()
        // A closed channel means the session already ended on its own, after an error.
        return if (events.trySend(Event.Finish(discard, reply)).isSuccess) reply.await() else result
    }

    private suspend fun process() {
        for (event in events) {
            try {
                when (event) {
                    is Event.Audio -> {
                        pendingAudio.decrementAndFetch()
                        onAudio(event.samples)
                    }
                    Event.Pause -> onPause()
                    Event.Resume -> onResume()
                    Event.InterruptionBegan -> onInterruptionBegan()
                    is Event.InterruptionEnded -> onInterruptionEnded(event.shouldResume)
                    is Event.Failed -> {
                        terminate(discard = false, error = event.error)
                        break
                    }
                    is Event.Finish -> {
                        terminate(discard = event.discard, error = null)
                        event.reply.complete(result)
                        break
                    }
                }
            } catch (e: Exception) {
                // A file that cannot be written or an engine that cannot pause ends the session;
                // it must never escape into the app as an uncaught exception.
                terminateAfterFailure(e as? AudioCaptureException ?: AudioCaptureException("Capture failed", e))
                if (event is Event.Finish) event.reply.complete(result)
                break
            }
        }
        // Anyone who asked to finish while the session was ending gets the same answer.
        events.close()
        while (true) {
            val event = events.tryReceive().getOrNull() ?: break
            if (event is Event.Finish) event.reply.complete(result)
        }
        scope.cancel()
    }

    private fun onAudio(samples: FloatArray) {
        if (_state.value != CaptureState.Recording) return
        writer?.write(samples, samples.size)
        var offset = 0
        while (offset < samples.size) {
            val count = minOf(samples.size - offset, chunkSamples - pendingCount)
            samples.copyInto(pending, pendingCount, offset, offset + count)
            pendingCount += count
            offset += count
            if (pendingCount == chunkSamples) emitPending()
        }
    }

    private fun emitPending() {
        if (pendingCount == 0) return
        _level.value = Pcm.level(pending, pendingCount)
        config.stream?.let { stream ->
            output.trySend(
                AudioChunk(
                    bytes = Pcm.encode(pending, pendingCount, stream.encoding),
                    format = format,
                    timestamp = (framesEmitted * 1_000_000L / config.sampleRate).microseconds,
                ),
            )
        }
        framesEmitted += pendingCount / config.channels
        pendingCount = 0
    }

    private fun onPause() {
        val current = _state.value
        if (current == CaptureState.Recording || current is CaptureState.Paused && current.reason == PauseReason.Interruption) {
            engine.pause()
            _state.value = CaptureState.Paused(PauseReason.User)
            _level.value = AudioLevel.Silence
        }
    }

    private fun onResume() {
        if (_state.value is CaptureState.Paused) resumeEngine()
    }

    private fun onInterruptionBegan() {
        if (config.interruption == InterruptionMode.None) return
        if (_state.value == CaptureState.Recording) {
            // Pause waits for the app to resume, so the microphone is released like a user pause.
            // PauseResume keeps the engine so it can hear when the microphone comes back.
            if (config.interruption == InterruptionMode.Pause) engine.pause()
            _state.value = CaptureState.Paused(PauseReason.Interruption)
            _level.value = AudioLevel.Silence
        }
    }

    private fun onInterruptionEnded(shouldResume: Boolean) {
        val current = _state.value
        if (current is CaptureState.Paused && current.reason == PauseReason.Interruption &&
            config.interruption == InterruptionMode.PauseResume && shouldResume
        ) {
            resumeEngine()
        }
    }

    private fun resumeEngine() {
        try {
            engine.resume()
            _state.value = CaptureState.Recording
        } catch (e: AudioCaptureException) {
            events.trySend(Event.Failed(e))
        }
    }

    private fun terminate(discard: Boolean, error: AudioCaptureException?) {
        runCatching { engine.stop() }
        if (!discard) emitPending()
        val file = config.file
        val fileWriter = writer
        if (fileWriter != null && file != null) {
            // Nothing captured means no playable file: remove it rather than hand back an empty one.
            if (discard || framesEmitted == 0L) {
                fileWriter.delete()
            } else if (runCatching { fileWriter.close() }.isSuccess) {
                result = Recording(
                    path = file.path,
                    encoder = file.encoder,
                    durationMillis = framesEmitted * 1000 / config.sampleRate,
                )
            } else {
                fileWriter.delete()
            }
        }
        pendingCount = 0
        _level.value = AudioLevel.Silence
        _state.value = CaptureState.Stopped(error)
        output.close(error)
    }

    /** Ends the session after a handler threw; nothing here may throw again. */
    private fun terminateAfterFailure(error: AudioCaptureException) {
        if (_state.value is CaptureState.Stopped) return
        runCatching { engine.stop() }
        runCatching { writer?.close() }
        _level.value = AudioLevel.Silence
        _state.value = CaptureState.Stopped(error)
        output.close(error)
    }

    private sealed interface Event {
        class Audio(val samples: FloatArray) : Event
        data object Pause : Event
        data object Resume : Event
        data object InterruptionBegan : Event
        class InterruptionEnded(val shouldResume: Boolean) : Event
        class Failed(val error: AudioCaptureException) : Event
        class Finish(val discard: Boolean, val reply: CompletableDeferred<Recording?>) : Event
    }

    private companion object {
        /** Engine blocks waiting to be processed before new audio is dropped. */
        const val MAX_PENDING_AUDIO = 500
    }
}
