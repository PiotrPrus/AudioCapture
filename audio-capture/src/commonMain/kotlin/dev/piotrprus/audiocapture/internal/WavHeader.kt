package dev.piotrprus.audiocapture.internal

/** The 44-byte RIFF header of a 16-bit PCM WAV file. */
internal object WavHeader {
    const val SIZE = 44

    fun build(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
        val out = ByteArray(SIZE)
        var at = 0
        fun ascii(text: String) = text.forEach { out[at++] = it.code.toByte() }
        fun int32(value: Long) = repeat(4) { out[at++] = ((value shr (8 * it)) and 0xFF).toByte() }
        fun int16(value: Int) = repeat(2) { out[at++] = ((value shr (8 * it)) and 0xFF).toByte() }

        val blockAlign = channels * 2
        ascii("RIFF")
        int32(36 + dataBytes)
        ascii("WAVE")
        ascii("fmt ")
        int32(16)
        int16(1) // PCM
        int16(channels)
        int32(sampleRate.toLong())
        int32(sampleRate.toLong() * blockAlign)
        int16(blockAlign)
        int16(16)
        ascii("data")
        int32(dataBytes)
        return out
    }
}
