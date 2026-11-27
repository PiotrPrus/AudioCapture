package dev.piotrprus.audiocapture

import kotlin.math.log10
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

public enum class PcmEncoding(internal val bytesPerSample: Int) {
    /** Signed 16-bit little-endian. What most speech APIs and WAV files use. */
    Int16(2),

    /** IEEE 754 32-bit float little-endian, -1.0..1.0. */
    Float32(4),
}

public data class PcmFormat(
    val sampleRate: Int,
    val channels: Int,
    val encoding: PcmEncoding,
) {
    /** Bytes in one frame: one sample for every channel. */
    val bytesPerFrame: Int get() = channels * encoding.bytesPerSample
}

/**
 * One block of interleaved PCM: [CaptureConfig.chunkDuration] of audio.
 *
 * A *frame* is one sample per channel, so in mono a frame is a single sample. [bytes] is
 * little-endian in [format]; if you just want numbers, call [toFloatArray]. [timestamp] counts captured audio from the start of the
 * session, not wall time, so it does not jump across a pause.
 */
public class AudioChunk(
    public val bytes: ByteArray,
    public val format: PcmFormat,
    public val timestamp: Duration,
) {
    public val frames: Int get() = bytes.size / format.bytesPerFrame

    public val duration: Duration
        get() = (frames * 1_000_000L / format.sampleRate).microseconds

    /** Samples as `Short`s. Only valid for [PcmEncoding.Int16]. */
    public fun toShortArray(): ShortArray {
        check(format.encoding == PcmEncoding.Int16) { "Chunk is ${format.encoding}, not Int16" }
        return Pcm.int16ToShorts(bytes)
    }

    /** Peak and RMS loudness of this chunk, for waveforms and meters. */
    public fun level(): AudioLevel {
        val samples = toFloatArray()
        return Pcm.level(samples, samples.size)
    }

    /** Samples as -1.0..1.0 floats, whatever the encoding. The easiest way to work with the audio. */
    public fun toFloatArray(): FloatArray = when (format.encoding) {
        PcmEncoding.Int16 -> Pcm.int16ToFloats(bytes)
        PcmEncoding.Float32 -> Pcm.float32ToFloats(bytes)
    }
}

/**
 * Loudness of one chunk, linear 0..1 relative to full scale.
 *
 * Both values use the plain sample-value convention: a full-scale square wave reads 0 dBFS peak
 * and RMS, a full-scale sine reads 0 dBFS peak and about -3 dBFS RMS (not the AES17 convention,
 * which adds 3 dB to RMS). Conversational speech close to a phone typically peaks around -20 to
 * -10 dBFS.
 *
 * Use [peakDbfs] or [rmsDbfs] for meters in decibels, or feed a [LevelNormalizer] for a 0..1 value
 * that behaves the same across devices.
 */
public data class AudioLevel(
    val peak: Float,
    val rms: Float,
) {
    public val peakDbfs: Float get() = toDbfs(peak)
    public val rmsDbfs: Float get() = toDbfs(rms)

    public companion object {
        /** Floor for the decibel values; digital silence would otherwise be minus infinity. */
        public const val MIN_DBFS: Float = -160f

        public val Silence: AudioLevel = AudioLevel(0f, 0f)

        private fun toDbfs(linear: Float): Float =
            if (linear <= 0f) MIN_DBFS else (20 * log10(linear)).coerceAtLeast(MIN_DBFS)
    }
}

/**
 * A microphone the system can record from.
 *
 * Several devices of the same [type] can be listed at once, for example two Bluetooth headsets
 * on iOS. Each is its own [InputDevice] with its own [id] and [stableKey]. Their [name]s can
 * match (two pairs of the same headphones), so apps that show them side by side should tell them
 * apart themselves.
 */
public data class InputDevice(
    /**
     * Platform identifier for this connection: the `AudioDeviceInfo` id on Android, the port UID
     * on iOS. On Android it changes when a device disconnects and connects again.
     */
    val id: String,
    val name: String,
    val type: InputDeviceType,
    /**
     * Identifies the same physical device across reconnects, for saving the user's choice: the
     * device type and address on Android (the Bluetooth address for Bluetooth devices), the port
     * UID on iOS. A [CaptureConfig.device] whose [id] is gone is matched again by this key.
     */
    val stableKey: String = id,
)

public enum class InputDeviceType {
    BuiltIn,
    WiredHeadset,
    Bluetooth,
    Usb,
    LineIn,

    /** CarPlay audio on iOS. On Android a car connected over Bluetooth is [Bluetooth], named after the car. */
    Car,
    Other,
}
