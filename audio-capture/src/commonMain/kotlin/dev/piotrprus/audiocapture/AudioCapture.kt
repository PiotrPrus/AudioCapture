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

    /** Current microphone permission, without asking. */
    public fun permission(): MicPermission

    /**
     * Shows the system permission dialog if needed and returns the answer. Returns at once when
     * permission was already granted, or when the user has already refused for good.
     *
     * [start] calls this for you, so you only need it to ask at a moment of your choosing. On
     * Android the dialog needs the app in the foreground; from the background this returns
     * [MicPermission.Denied].
     */
    public suspend fun requestPermission(): MicPermission

    /**
     * An absolute path for [fileName] in the app's private storage (Android `filesDir`, iOS
     * Application Support), in a `recordings` folder that is created if needed. Use it for
     * [FileOutput.path].
     */
    public fun recordingPath(fileName: String): String

    /**
     * Microphones the system can record from right now.
     *
     * Pass one as [CaptureConfig.device] to prefer it over the system default. The list changes
     * as headsets and USB devices come and go, so read it when you need it rather than caching it.
     */
    public fun inputDevices(): List<InputDevice>

    /**
     * Whether this device can write [encoder] files at [sampleRate] with [channels]. AAC is limited
     * to 8–48 kHz. [start] checks this and throws [AudioCaptureException] with the reason.
     */
    public fun isSupported(encoder: AudioEncoder, sampleRate: Int = 16_000, channels: Int = 1): Boolean

    /**
     * Opens the microphone and starts capturing with [config].
     *
     * Asks for microphone permission first if it has not been granted. The session is recording
     * when this returns. Call [CaptureSession.stop] to finish it and get the file, or
     * [CaptureSession.cancel] to discard it.
     *
     * @throws AudioCaptureException if the microphone cannot be opened: permission refused, the
     *   requested format is not supported, or another app holds the input exclusively.
     */
    public suspend fun start(config: CaptureConfig = CaptureConfig()): CaptureSession
}

/**
 * The shortest way to record: asks for permission if needed and starts recording [fileName]
 * (`.m4a` for AAC, `.wav` for WAV) into [AudioCapture.recordingPath]. Chunks and levels are on
 * the returned session for live visuals; call [CaptureSession.stop] to get the file.
 *
 * ```
 * val session = AudioCapture().record("hello.m4a")
 * // ... later
 * val recording = session.stop()
 * ```
 */
public suspend fun AudioCapture.record(fileName: String = "recording.m4a"): CaptureSession =
    start(CaptureConfig(file = FileOutput(recordingPath(fileName))))

/**
 * Streams raw PCM for as long as the flow is collected.
 *
 * The microphone opens when collection starts and closes when the collector is cancelled, so the
 * capture cannot outlive the code that uses it. Any [CaptureConfig.file] is ignored: use [AudioCapture.start]
 * when you also need a recording.
 */
public fun AudioCapture.stream(config: CaptureConfig = CaptureConfig()): Flow<AudioChunk> = flow {
    val session = start(config.copy(file = null, pcm = config.pcm ?: PcmOutput()))
    try {
        session.chunks.collect { emit(it) }
    } finally {
        session.cancel()
    }
}

/** The platform's [AudioCapture]. */
public expect fun AudioCapture(): AudioCapture
