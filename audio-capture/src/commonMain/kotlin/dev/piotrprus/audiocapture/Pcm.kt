package dev.piotrprus.audiocapture

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Sample conversions between the interleaved float frames engines deliver and the wire formats. */
internal object Pcm {

    fun encode(samples: FloatArray, count: Int, encoding: PcmEncoding): ByteArray = when (encoding) {
        PcmEncoding.Int16 -> floatsToInt16(samples, count)
        PcmEncoding.Float32 -> floatsToFloat32(samples, count)
    }

    fun floatsToInt16(samples: FloatArray, count: Int = samples.size): ByteArray {
        val out = ByteArray(count * 2)
        for (i in 0 until count) {
            val value = toInt16(samples[i])
            out[i * 2] = (value and 0xFF).toByte()
            out[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun floatsToFloat32(samples: FloatArray, count: Int = samples.size): ByteArray {
        val out = ByteArray(count * 4)
        for (i in 0 until count) {
            val bits = samples[i].toRawBits()
            out[i * 4] = (bits and 0xFF).toByte()
            out[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
        }
        return out
    }

    fun int16ToShorts(bytes: ByteArray): ShortArray = ShortArray(bytes.size / 2) { i ->
        ((bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF)).toShort()
    }

    fun int16ToFloats(bytes: ByteArray): FloatArray = FloatArray(bytes.size / 2) { i ->
        val value = (bytes[i * 2 + 1].toInt() shl 8) or (bytes[i * 2].toInt() and 0xFF)
        value.toShort() / 32768f
    }

    fun float32ToFloats(bytes: ByteArray): FloatArray = FloatArray(bytes.size / 4) { i ->
        Float.fromBits(
            (bytes[i * 4].toInt() and 0xFF) or
                ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or
                ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16) or
                (bytes[i * 4 + 3].toInt() shl 24),
        )
    }

    /** -1.0..1.0 to a signed 16-bit value, clamping anything the hardware overshot. */
    fun toInt16(sample: Float): Int = (sample.coerceIn(-1f, 1f) * 32767f).roundToInt()

    fun level(samples: FloatArray, count: Int): AudioLevel {
        if (count == 0) return AudioLevel.Silence
        var peak = 0f
        var sumSquares = 0.0
        for (i in 0 until count) {
            val s = samples[i]
            val magnitude = abs(s)
            if (magnitude > peak) peak = magnitude
            sumSquares += s * s
        }
        return AudioLevel(
            peak = peak.coerceAtMost(1f),
            rms = sqrt(sumSquares / count).toFloat().coerceAtMost(1f),
        )
    }
}
