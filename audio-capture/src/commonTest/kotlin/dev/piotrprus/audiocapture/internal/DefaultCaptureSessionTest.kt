package dev.piotrprus.audiocapture.internal

import dev.piotrprus.audiocapture.AudioCaptureException
import dev.piotrprus.audiocapture.AudioChunk
import dev.piotrprus.audiocapture.AudioEncoder
import dev.piotrprus.audiocapture.CaptureConfig
import dev.piotrprus.audiocapture.CaptureState
import dev.piotrprus.audiocapture.FileOutput
import dev.piotrprus.audiocapture.InputDevice
import dev.piotrprus.audiocapture.InterruptionMode
import dev.piotrprus.audiocapture.PauseReason
import dev.piotrprus.audiocapture.PcmEncoding
import dev.piotrprus.audiocapture.Recording
import dev.piotrprus.audiocapture.StreamOutput
import dev.piotrprus.audiocapture.VoiceProcessing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCaptureSessionTest {

    // 8 kHz and 10 ms chunks: 80 frames per chunk keeps the numbers readable.
    private val base = CaptureConfig(sampleRate = 8_000, chunkDuration = 10.milliseconds)

    private class FakeEngine : CaptureEngine {
        lateinit var listener: CaptureEngine.Listener
        var running = false
        var stopped = false
        var failOnStart: AudioCaptureException? = null
        var pauses = 0
        override val routedDevice: InputDevice? = null
        override val appliedVoiceProcessing: VoiceProcessing? = null

        override fun start(listener: CaptureEngine.Listener) {
            failOnStart?.let { throw it }
            this.listener = listener
            running = true
        }

        override fun pause() {
            running = false
            pauses++
        }

        override fun resume() {
            running = true
        }

        override fun stop() {
            running = false
            stopped = true
        }

        fun emit(frames: Int, value: Float = 0.5f) = listener.onAudio(FloatArray(frames) { value })
    }

    private class FakeWriter : AudioFileWriter {
        val written = mutableListOf<Float>()
        var closed = false
        var deleted = false

        override fun write(samples: FloatArray, count: Int) {
            for (i in 0 until count) written += samples[i]
        }

        override fun close() {
            closed = true
        }

        override fun delete() {
            deleted = true
        }
    }

    private fun TestScope.session(
        config: CaptureConfig = base,
        engine: FakeEngine = FakeEngine(),
        writer: AudioFileWriter? = null,
    ) = DefaultCaptureSession(config, engine, writer, StandardTestDispatcher(testScheduler)).also { it.begin() }

    @Test
    fun uneven_engine_blocks_become_whole_chunks_with_continuous_timestamps() = runTest {
        val engine = FakeEngine()
        val session = session(engine = engine)

        engine.emit(35) // 80 frames per 10 ms chunk at 8 kHz
        engine.emit(100)
        engine.emit(25)
        advanceUntilIdle()
        session.stop()

        val chunks = session.chunks.toList()
        assertEquals(listOf(80, 80), chunks.map { it.frames })
        assertEquals(listOf(0.milliseconds, 10.milliseconds), chunks.map { it.timestamp })
    }

    @Test
    fun stop_flushes_the_partial_chunk() = runTest {
        val engine = FakeEngine()
        val session = session(engine = engine)

        engine.emit(100)
        advanceUntilIdle()
        session.stop()

        assertEquals(listOf(80, 20), session.chunks.toList().map(AudioChunk::frames))
        assertTrue(engine.stopped)
        assertEquals(CaptureState.Stopped(), session.state.value)
    }

    @Test
    fun chunks_use_the_requested_encoding() = runTest {
        val engine = FakeEngine()
        val session = session(base.copy(stream = StreamOutput(PcmEncoding.Float32)), engine)

        engine.emit(80, value = 0.25f)
        advanceUntilIdle()
        session.stop()

        val chunk = session.chunks.toList().single()
        assertEquals(80 * 4, chunk.bytes.size)
        assertTrue(chunk.toFloatArray().all { it == 0.25f })
    }

    @Test
    fun level_follows_the_latest_chunk() = runTest {
        val engine = FakeEngine()
        val session = session(engine = engine)

        engine.emit(80, value = 0.5f)
        advanceUntilIdle()

        assertEquals(0.5f, session.level.value.peak)
        session.stop()
    }

    @Test
    fun file_gets_every_sample_and_stop_returns_the_recording() = runTest {
        val engine = FakeEngine()
        val writer = FakeWriter()
        val config = base.copy(file = FileOutput("/tmp/a.wav", AudioEncoder.Wav))
        val session = session(config, engine, writer)

        engine.emit(8_000)
        engine.emit(4_000)
        advanceUntilIdle()
        val recording = session.stop()

        assertEquals(12_000, writer.written.size)
        assertTrue(writer.closed)
        assertEquals(Recording("/tmp/a.wav", AudioEncoder.Wav, durationMillis = 1_500), recording)
    }

    @Test
    fun paused_audio_is_dropped_and_timestamps_skip_the_pause() = runTest {
        val engine = FakeEngine()
        val writer = FakeWriter()
        val session = session(base.copy(file = FileOutput("/tmp/a.m4a")), engine, writer)

        engine.emit(80)
        session.pause()
        advanceUntilIdle()
        assertEquals(CaptureState.Paused(PauseReason.User), session.state.value)
        assertEquals(false, engine.running)

        engine.emit(80) // Arrives late from the platform; must not be recorded.
        advanceUntilIdle()
        session.resume()
        advanceUntilIdle()
        assertEquals(CaptureState.Recording, session.state.value)
        engine.emit(80)
        advanceUntilIdle()
        session.stop()

        assertEquals(160, writer.written.size)
        assertEquals(listOf(0.milliseconds, 10.milliseconds), session.chunks.toList().map { it.timestamp })
    }

    @Test
    fun interruption_pauses_and_waits_for_resume_by_default() = runTest {
        val engine = FakeEngine()
        val session = session(engine = engine)

        engine.listener.onInterruptionBegan()
        advanceUntilIdle()
        assertEquals(CaptureState.Paused(PauseReason.Interruption), session.state.value)

        engine.listener.onInterruptionEnded(shouldResume = true)
        advanceUntilIdle()
        assertEquals(CaptureState.Paused(PauseReason.Interruption), session.state.value)

        session.resume()
        advanceUntilIdle()
        assertEquals(CaptureState.Recording, session.state.value)
        session.stop()
    }

    @Test
    fun pause_resume_mode_resumes_only_when_the_system_says_so() = runTest {
        val engine = FakeEngine()
        val session = session(base.copy(interruption = InterruptionMode.PauseResume), engine)

        engine.listener.onInterruptionBegan()
        engine.listener.onInterruptionEnded(shouldResume = false)
        advanceUntilIdle()
        assertEquals(CaptureState.Paused(PauseReason.Interruption), session.state.value)

        session.resume()
        engine.listener.onInterruptionBegan()
        engine.listener.onInterruptionEnded(shouldResume = true)
        advanceUntilIdle()
        assertEquals(CaptureState.Recording, session.state.value)
        session.stop()
    }

    @Test
    fun interruption_does_not_override_a_user_pause() = runTest {
        val engine = FakeEngine()
        val session = session(base.copy(interruption = InterruptionMode.PauseResume), engine)

        session.pause()
        engine.listener.onInterruptionBegan()
        engine.listener.onInterruptionEnded(shouldResume = true)
        advanceUntilIdle()

        assertEquals(CaptureState.Paused(PauseReason.User), session.state.value)
        session.stop()
    }

    @Test
    fun none_mode_ignores_interruptions() = runTest {
        val engine = FakeEngine()
        val session = session(base.copy(interruption = InterruptionMode.None), engine)

        engine.listener.onInterruptionBegan()
        advanceUntilIdle()

        assertEquals(CaptureState.Recording, session.state.value)
        session.stop()
    }

    @Test
    fun cancel_deletes_the_file() = runTest {
        val writer = FakeWriter()
        val session = session(base.copy(file = FileOutput("/tmp/a.m4a")), writer = writer)

        session.cancel()

        assertTrue(writer.deleted)
        assertEquals(CaptureState.Stopped(), session.state.value)
    }

    @Test
    fun engine_error_stops_the_session_and_fails_the_stream() = runTest {
        val engine = FakeEngine()
        val writer = FakeWriter()
        val session = session(base.copy(file = FileOutput("/tmp/a.m4a")), engine, writer)
        val error = AudioCaptureException("mic gone")

        engine.emit(80)
        engine.listener.onError(error)
        advanceUntilIdle()

        assertEquals(CaptureState.Stopped(error), session.state.value)
        assertTrue(writer.closed, "the audio captured before the failure is kept")
        assertFailsWith<AudioCaptureException> { session.chunks.toList() }
        assertEquals("/tmp/a.m4a", session.stop()?.path)
    }

    @Test
    fun writer_failure_stops_the_session_instead_of_crashing() = runTest {
        val engine = FakeEngine()
        val writer = object : AudioFileWriter {
            override fun write(samples: FloatArray, count: Int) = throw AudioCaptureException("disk full")
            override fun close() = Unit
            override fun delete() = Unit
        }
        val session = session(base.copy(file = FileOutput("/tmp/a.m4a")), engine, writer)

        engine.emit(80)
        advanceUntilIdle()

        assertEquals("disk full", assertIs<CaptureState.Stopped>(session.state.value).error?.message)
        assertTrue(engine.stopped)
        assertNull(session.stop())
    }

    @Test
    fun stop_after_stop_returns_the_same_recording() = runTest {
        val engine = FakeEngine()
        val session = session(base.copy(file = FileOutput("/tmp/a.m4a")), engine, FakeWriter())
        engine.emit(80)
        advanceUntilIdle()

        val first = session.stop()
        val second = session.stop()

        assertEquals("/tmp/a.m4a", first?.path)
        assertEquals(first, second)
    }

    @Test
    fun stopping_before_any_audio_deletes_the_empty_file() = runTest {
        val writer = FakeWriter()
        val session = session(base.copy(file = FileOutput("/tmp/a.m4a")), writer = writer)

        assertNull(session.stop())
        assertTrue(writer.deleted)
        assertEquals(false, writer.closed)
    }

    @Test
    fun a_file_that_fails_to_finish_is_deleted_and_not_returned() = runTest {
        val engine = FakeEngine()
        var deleted = false
        val writer = object : AudioFileWriter {
            override fun write(samples: FloatArray, count: Int) = Unit
            override fun close() = throw AudioCaptureException("muxer")
            override fun delete() { deleted = true }
        }
        val session = session(base.copy(file = FileOutput("/tmp/a.m4a")), engine, writer)
        engine.emit(80)
        advanceUntilIdle()

        assertNull(session.stop())
        assertTrue(deleted)
    }

    @Test
    fun pause_mode_releases_the_microphone_on_interruption() = runTest {
        val engine = FakeEngine()
        val session = session(engine = engine)

        engine.listener.onInterruptionBegan()
        advanceUntilIdle()

        assertEquals(1, engine.pauses)
        assertEquals(false, engine.running)
        session.stop()
    }

    @Test
    fun pause_resume_mode_keeps_the_engine_during_an_interruption() = runTest {
        val engine = FakeEngine()
        val session = session(base.copy(interruption = InterruptionMode.PauseResume), engine)

        engine.listener.onInterruptionBegan()
        advanceUntilIdle()

        assertEquals(0, engine.pauses)
        session.stop()
    }

    @Test
    fun chunks_can_only_be_collected_once() = runTest {
        val engine = FakeEngine()
        val session = session(engine = engine)
        val first = launch { session.chunks.collect {} }
        advanceUntilIdle()

        assertFailsWith<IllegalStateException> { session.chunks.first() }
        first.cancel()
        session.stop()
    }

    @Test
    fun failed_start_cleans_up() = runTest {
        val engine = FakeEngine().apply { failOnStart = AudioCaptureException("busy") }
        val writer = FakeWriter()

        val error = assertFailsWith<AudioCaptureException> {
            session(base.copy(file = FileOutput("/tmp/a.m4a")), engine, writer)
        }

        assertEquals("busy", error.message)
        assertTrue(writer.deleted)
        assertTrue(engine.stopped)
    }

    @Test
    fun file_only_session_has_no_chunks() = runTest {
        val engine = FakeEngine()
        val session = session(base.copy(stream = null, file = FileOutput("/tmp/a.m4a")), engine, FakeWriter())

        engine.emit(160)
        advanceUntilIdle()
        session.stop()

        assertTrue(session.chunks.toList().isEmpty())
    }

    @Test
    fun config_rejects_no_output() {
        assertFailsWith<IllegalArgumentException> { CaptureConfig(stream = null, file = null) }
    }

    @Test
    fun stopped_state_has_no_error_after_a_normal_stop() = runTest {
        val session = session()
        session.stop()
        assertNull(assertIs<CaptureState.Stopped>(session.state.value).error)
    }
}
