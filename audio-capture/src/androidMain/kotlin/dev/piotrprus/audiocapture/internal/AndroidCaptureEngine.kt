package dev.piotrprus.audiocapture.internal

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import dev.piotrprus.audiocapture.AndroidAudioSource
import dev.piotrprus.audiocapture.AudioCaptureException
import dev.piotrprus.audiocapture.CaptureConfig
import dev.piotrprus.audiocapture.ExperimentalVoiceProcessing
import dev.piotrprus.audiocapture.InputDevice
import dev.piotrprus.audiocapture.InterruptionMode
import dev.piotrprus.audiocapture.VoiceProcessing

/**
 * [CaptureEngine] over [AudioRecord], reading on a dedicated thread.
 *
 * Asks for float samples and falls back to 16-bit on devices that refuse them. Reads in short
 * slices (about 20 ms) regardless of the chunk size; the session re-chunks. Pausing stops the
 * recorder and its thread so the microphone indicator goes off; resuming starts both again.
 *
 * Interruptions (API 29+) come from the recording callback's `isClientSilenced`: while the system
 * silences this recorder (the app went to the background without a microphone foreground service,
 * or a higher-priority capture started) it is fed zeros. In `PauseResume` mode the recorder keeps
 * running so the callback can report when the microphone comes back; the session drops the
 * silence meanwhile.
 *
 * If the audio server dies (`ERROR_DEAD_OBJECT`, also seen on some route changes) the recorder is
 * rebuilt once and capture carries on.
 */
internal class AndroidCaptureEngine(
    context: Context,
    private val config: CaptureConfig,
) : CaptureEngine {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val lock = Any()

    @Volatile
    private var record: AudioRecord? = null
    private var floatSamples = true
    private var thread: Thread? = null
    private val effects = mutableListOf<AudioEffect>()

    @Volatile
    private var applied: VoiceProcessing? = null
    private var recordingCallback: Any? = null
    private lateinit var listener: CaptureEngine.Listener

    @Volatile
    private var reading = false

    @Volatile
    private var silenced = false

    /** Set when stop() gave up waiting for a stuck read; the reader releases the recorder on exit. */
    @Volatile
    private var releaseOnExit = false

    override val routedDevice: InputDevice?
        get() = record?.routedDevice?.toInputDevice()

    override val appliedVoiceProcessing: VoiceProcessing?
        get() = applied

    override fun start(listener: CaptureEngine.Listener) {
        this.listener = listener
        val created = createRecord()
        record = created
        attachEffects(created.audioSessionId)
        config.device?.let { wanted -> preferDevice(created, wanted) }
        if (Build.VERSION.SDK_INT >= 29 && config.interruption != InterruptionMode.None) {
            registerInterruptions(created)
        }
        synchronized(lock) { startReading(created) }
    }

    override fun pause() {
        synchronized(lock) { stopReading() }
    }

    override fun resume() {
        synchronized(lock) {
            val current = record ?: return
            if (thread != null) {
                // A reader that never came back from pause() still owns the recorder.
                if (thread?.isAlive == true) throw AudioCaptureException("The microphone did not stop in time to resume")
                return
            }
            silenced = false
            startReading(current)
        }
    }

    override fun stop() {
        synchronized(lock) {
            val exited = stopReading()
            if (Build.VERSION.SDK_INT >= 29) unregisterInterruptions()
            effects.forEach { runCatching { it.release() } }
            effects.clear()
            // Releasing while the reader is still inside read() is a native use-after-free; if it
            // is stuck, it releases the recorder itself when read() finally returns.
            if (exited) record?.release() else releaseOnExit = true
            record = null
        }
    }

    private fun preferDevice(target: AudioRecord, wanted: InputDevice) {
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id.toString() == wanted.id }
            ?: throw AudioCaptureException("${wanted.name} is no longer connected")
        if (!target.setPreferredDevice(device)) {
            throw AudioCaptureException("The system refused to record from ${wanted.name}")
        }
    }

    private fun startReading(target: AudioRecord) {
        target.startRecording()
        if (target.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw AudioCaptureException("The microphone is in use by another app")
        }
        reading = true
        thread = Thread({ readLoop(target) }, "AudioCapture").apply { start() }
    }

    /** @return whether the reader thread has exited. A stuck reader stays in [thread]. */
    private fun stopReading(): Boolean {
        val running = thread ?: return true
        reading = false
        // Unblocks the read in progress.
        runCatching { record?.stop() }
        running.join(JOIN_TIMEOUT_MS)
        if (running.isAlive) return false
        thread = null
        return true
    }

    private fun readLoop(initial: AudioRecord) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var target = initial
        var rebuilt = false
        val size = framesPerRead() * config.channels
        val floats = FloatArray(size)
        val shorts = ShortArray(size)
        try {
            while (reading) {
                val read = if (floatSamples) {
                    target.read(floats, 0, size, AudioRecord.READ_BLOCKING)
                } else {
                    target.read(shorts, 0, size)
                }
                when {
                    read > 0 -> {
                        val whole = read - read % config.channels
                        if (whole > 0) {
                            listener.onAudio(if (floatSamples) floats.copyOf(whole) else FloatArray(whole) { shorts[it] / 32768f })
                        }
                    }
                    read == AudioRecord.ERROR_DEAD_OBJECT && reading && !rebuilt -> {
                        rebuilt = true
                        target = rebuild(target) ?: return
                    }
                    // Other negative values are error codes, not short reads. After stop() they are expected.
                    read < 0 -> {
                        if (reading) listener.onError(AudioCaptureException("AudioRecord read failed with code $read"))
                        return
                    }
                }
            }
        } finally {
            if (releaseOnExit) target.release()
        }
    }

    /** Replaces a recorder whose audio server died. Returns `null` after reporting a failure. */
    private fun rebuild(dead: AudioRecord): AudioRecord? = try {
        if (Build.VERSION.SDK_INT >= 29) unregisterInterruptions()
        dead.release()
        createRecord().also { fresh ->
            record = fresh
            attachEffects(fresh.audioSessionId)
            config.device?.let { preferDevice(fresh, it) }
            if (Build.VERSION.SDK_INT >= 29 && config.interruption != InterruptionMode.None) {
                registerInterruptions(fresh)
            }
            fresh.startRecording()
        }
    } catch (e: Exception) {
        listener.onError(e as? AudioCaptureException ?: AudioCaptureException("Could not recover the microphone", e))
        null
    }

    /** About 20 ms, never more than a chunk. */
    private fun framesPerRead(): Int = minOf(config.framesPerChunk, (config.sampleRate / 50).coerceAtLeast(1))

    private fun createRecord(): AudioRecord {
        val channelMask = if (config.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        for (encoding in listOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
            val minBuffer = AudioRecord.getMinBufferSize(config.sampleRate, channelMask, encoding)
            if (minBuffer <= 0) continue
            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            // About 200 ms of headroom keeps the recorder from overrunning while a slice is handed off.
            val bufferSize = maxOf(minBuffer, config.sampleRate / 5 * config.channels * bytesPerSample)
            val created = try {
                AudioRecord.Builder()
                    .setAudioSource(audioSource())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(config.sampleRate)
                            .setChannelMask(channelMask)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } catch (e: SecurityException) {
                throw AudioCaptureException("RECORD_AUDIO permission is not granted", e)
            } catch (_: UnsupportedOperationException) {
                continue
            } catch (_: IllegalArgumentException) {
                continue
            }
            if (created.state == AudioRecord.STATE_INITIALIZED) {
                floatSamples = encoding == AudioFormat.ENCODING_PCM_FLOAT
                return created
            }
            created.release()
        }
        throw AudioCaptureException(
            "This device cannot record ${config.sampleRate} Hz with ${config.channels} channel(s) " +
                "from ${config.android.audioSource}",
        )
    }

    private fun audioSource(): Int = when (config.android.audioSource) {
        AndroidAudioSource.Default -> MediaRecorder.AudioSource.DEFAULT
        AndroidAudioSource.Mic -> MediaRecorder.AudioSource.MIC
        AndroidAudioSource.Camcorder -> MediaRecorder.AudioSource.CAMCORDER
        AndroidAudioSource.VoiceRecognition -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        AndroidAudioSource.VoiceCommunication -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        AndroidAudioSource.Unprocessed -> MediaRecorder.AudioSource.UNPROCESSED
        AndroidAudioSource.VoicePerformance ->
            if (Build.VERSION.SDK_INT >= 29) MediaRecorder.AudioSource.VOICE_PERFORMANCE else MediaRecorder.AudioSource.MIC
    }

    @OptIn(ExperimentalVoiceProcessing::class)
    private fun attachEffects(sessionId: Int) {
        effects.forEach { runCatching { it.release() } }
        effects.clear()
        val wanted = config.voiceProcessing ?: return
        fun attach(requested: Boolean, available: Boolean, create: () -> AudioEffect?): Boolean {
            if (!requested || !available) return false
            val effect = runCatching { create() }.getOrNull() ?: return false
            effects += effect
            return runCatching { effect.setEnabled(true) == AudioEffect.SUCCESS && effect.enabled }.getOrDefault(false)
        }
        val echo = attach(wanted.echoCancel, AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(sessionId) }
        val noise = attach(wanted.noiseSuppress, NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(sessionId) }
        val gain = attach(wanted.autoGain, AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(sessionId) }
        applied = if (echo || noise || gain) VoiceProcessing(echoCancel = echo, noiseSuppress = noise, autoGain = gain) else null
    }

    @RequiresApi(29)
    private fun registerInterruptions(target: AudioRecord) {
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val current = record ?: return
                val mine = configs.firstOrNull { it.clientAudioSessionId == current.audioSessionId } ?: return
                val nowSilenced = mine.isClientSilenced
                if (nowSilenced == silenced) return
                silenced = nowSilenced
                if (nowSilenced) listener.onInterruptionBegan() else listener.onInterruptionEnded(shouldResume = true)
            }
        }
        target.registerAudioRecordingCallback(appContext.mainExecutor, callback)
        recordingCallback = callback
    }

    @RequiresApi(29)
    private fun unregisterInterruptions() {
        val callback = recordingCallback as? AudioManager.AudioRecordingCallback ?: return
        record?.unregisterAudioRecordingCallback(callback)
        recordingCallback = null
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 1_000L
    }
}
