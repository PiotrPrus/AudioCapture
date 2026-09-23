package dev.piotrprus.audiocapture

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** One run of the microphone, from [AudioCapture.start] to [stop] or [cancel]. */
public interface CaptureSession {

    /**
     * The format [chunks] are delivered in.
     *
     * Always the sample rate and channel count that were requested: when the hardware runs at a
     * different rate the library converts, so this never silently differs from [CaptureConfig].
     */
    public val format: PcmFormat

    public val state: StateFlow<CaptureState>

    /** Loudness of the most recent chunk; updated once per [CaptureConfig.chunkDuration]. */
    public val level: StateFlow<AudioLevel>

    /**
     * Raw PCM, one chunk per [CaptureConfig.chunkDuration].
     *
     * Empty when [CaptureConfig.stream] is `null`. Meant for a single collector: chunks are queued
     * until collected, up to [StreamOutput.bufferedChunks], after which the oldest are dropped. The
     * flow completes when the session stops, or fails with [AudioCaptureException] when the
     * microphone fails.
     */
    public val chunks: Flow<AudioChunk>

    /**
     * Stops delivering audio and releases the microphone, keeping the session and its file open.
     * No-op unless [state] is [CaptureState.Recording].
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
     * @return the finished file, or `null` when the session had no [CaptureConfig.file].
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

    /** The system took the microphone: a phone call, Siri, another app recording. */
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
