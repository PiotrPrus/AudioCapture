package dev.piotrprus.audiocapture

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Entry point to the microphone.
 *
 * One [AudioCapture] can start any number of sessions, one after another. Each [start] opens the
 * microphone and returns a [CaptureSession] that owns it until [CaptureSession.stop] or
 * [CaptureSession.cancel].
 *
 * Get an instance with the `AudioCapture()` factory. On Android it picks up the application
 * context through `androidx.startup`; an overload that takes a `Context` is there for apps that
 * disable the initializer.
 */
public interface AudioCapture {

    /**
     * Current microphone permission.
     *
     * The library does not request it. Ask before the first [start]: on Android through the
     * usual runtime-permission flow, on iOS with `AVAudioApplication.requestRecordPermission`. If
     * iOS has never asked, starting a session shows the system prompt and the first moments of
     * audio are silence.
     */
    public fun permission(): MicPermission

    /**
     * Microphones the system can record from right now.
     *
     * Pass one as [CaptureConfig.device] to prefer it over the system default. The list changes
     * as headsets and USB devices come and go, so read it when you need it rather than caching it.
     */
    public fun inputDevices(): List<InputDevice>

    /** Whether this platform can write files with [encoder]. */
    public fun isSupported(encoder: AudioEncoder): Boolean

    /**
     * Opens the microphone and starts capturing with [config].
     *
     * The session is recording when this returns. Call [CaptureSession.stop] to finish it and get
     * the file, or [CaptureSession.cancel] to discard it.
     *
     * @throws AudioCaptureException if the microphone cannot be opened: no permission, the
     *   requested format is not supported, or another app holds the input exclusively.
     */
    public suspend fun start(config: CaptureConfig = CaptureConfig()): CaptureSession
}

/**
 * Streams raw PCM for as long as the flow is collected.
 *
 * The microphone opens when collection starts and closes when the collector is cancelled, so the
 * capture cannot outlive the code that uses it. Any [CaptureConfig.file] is ignored: use [AudioCapture.start]
 * when you also need a recording.
 */
public fun AudioCapture.stream(config: CaptureConfig = CaptureConfig()): Flow<AudioChunk> = flow {
    val session = start(config.copy(file = null))
    try {
        session.chunks.collect { emit(it) }
    } finally {
        session.cancel()
    }
}

/** The platform's [AudioCapture]. */
public expect fun AudioCapture(): AudioCapture
