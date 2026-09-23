package dev.piotrprus.audiocapture

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One run of the microphone, from [AudioCapture.start] to [stop] or [cancel]. */
public interface CaptureSession {

    /**
     * The format [chunks] are delivered in.
     *
     * Always the sample rate and channel count that were requested. When the hardware runs at a
     * different rate the audio is resampled (by the system on Android, by the library on iOS), so
     * this never silently differs from [CaptureConfig].
     */
    public val format: PcmFormat

    public val state: StateFlow<CaptureState>

    /** Loudness of the most recent chunk; updated once per [CaptureConfig.chunkDuration]. */
    public val level: StateFlow<AudioLevel>

    /**
     * The microphone the system is actually recording from, or `null` when it does not say.
     * It can differ from [CaptureConfig.device]: the system has the final word on routing.
     */
    public val inputDevice: InputDevice?

    /**
     * The voice processing that actually took effect, which can be less than was asked for: an
     * Android device may lack an effect, and iOS cannot switch its effects separately. `null`
     * when none is active.
     */
    public val voiceProcessing: VoiceProcessing?

    /**
     * Raw PCM, one chunk per [CaptureConfig.chunkDuration].
     *
     * Empty when [CaptureConfig.stream] is `null`. Can be collected once; a second collector fails
     * with [IllegalStateException] (use `shareIn` to fan out). Chunks are queued until collected,
     * up to [StreamOutput.bufferedChunks], after which the oldest are dropped. The
     * flow completes when the session stops, or fails with [AudioCaptureException] when the
     * microphone fails.
     */
    public val chunks: Flow<AudioChunk>

    /**
     * Stops delivering audio and releases the microphone, keeping the session and its file open.
     * Works while [CaptureState.Recording] or paused by an interruption; no-op otherwise.
     */
    public fun pause()

    /**
     * Picks up after [pause] or an interruption. No-op unless [state] is [CaptureState.Paused].
     *
     * [state] turns back to [CaptureState.Recording] once audio flows again. On iOS that takes a
     * few hundred milliseconds while the audio session reactivates.
     */
    public fun resume()

    /**
     * Stops capturing, finishes the file and completes [chunks].
     *
     * @return the finished file, or `null` when the session had no [CaptureConfig.file] or captured
     *   no audio at all (the empty file is deleted).
     */
    public suspend fun stop(): Recording?

    /** Stops capturing and deletes the file, if there was one. */
    public suspend fun cancel()
}

public sealed interface CaptureState {
    public data object Recording : CaptureState

    public data class Paused(val reason: PauseReason) : CaptureState

    /** Terminal. [error] is set when the microphone failed rather than being stopped. */
    public data class Stopped(val error: AudioCaptureException? = null) : CaptureState
}

public enum class PauseReason {
    /** [CaptureSession.pause] was called. */
    User,

    /**
     * The system took the microphone away.
     *
     * On iOS: a phone call, Siri, an alarm, or another app's audio session that does not mix with
     * yours. On Android (API 29+) the system silenced the recorder, most often because the app
     * went to the background without a microphone foreground service, or another app with
     * priority started capturing. Below API 29 Android reports no interruptions.
     */
    Interruption,
}

/** A finished file. */
public data class Recording(
    val path: String,
    val encoder: AudioEncoder,
    /** Audio actually captured; paused time is not included. */
    val durationMillis: Long,
)

public class AudioCaptureException(message: String, cause: Throwable? = null) : Exception(message, cause)

public enum class MicPermission {
    Granted,
    Denied,

    /** iOS only: never asked. The system prompts the first time a session starts. */
    NotDetermined,
}
