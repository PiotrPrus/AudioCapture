package dev.piotrprus.audiocapture.internal

import dev.piotrprus.audiocapture.AudioCaptureException
import dev.piotrprus.audiocapture.InputDevice
import dev.piotrprus.audiocapture.VoiceProcessing

/**
 * The platform half of a session: owns the microphone and hands over interleaved float frames at
 * exactly the configured sample rate and channel count. Everything else (chunking, levels, files,
 * state) is common code in [DefaultCaptureSession].
 */
internal interface CaptureEngine {

    /**
     * Opens the microphone and starts delivering to [listener]. Called once.
     *
     * @throws AudioCaptureException when the microphone cannot be opened.
     */
    fun start(listener: Listener)

    /** Releases the microphone until [resume]. */
    fun pause()

    /** Reopens the microphone. Must be a no-op when the engine is already running. */
    fun resume()

    /** Releases the microphone for good. Must be safe to call more than once. */
    fun stop()

    /** The microphone the system is actually recording from, when it says. */
    val routedDevice: InputDevice?

    /** The effects that actually took effect, or `null` for none. */
    val appliedVoiceProcessing: VoiceProcessing?

    /** Called from whatever thread the platform uses; implementations must not block. */
    interface Listener {
        /** Interleaved -1.0..1.0 samples; the size is a whole number of frames. */
        fun onAudio(samples: FloatArray)

        fun onInterruptionBegan()

        fun onInterruptionEnded(shouldResume: Boolean)

        fun onError(error: AudioCaptureException)
    }
}

/** Encodes interleaved float frames into a file. Called from one thread at a time. */
internal interface AudioFileWriter {
    fun write(samples: FloatArray, count: Int)

    /** Finishes the file so it is playable. */
    fun close()

    /** Closes if needed and removes the file. */
    fun delete()
}
