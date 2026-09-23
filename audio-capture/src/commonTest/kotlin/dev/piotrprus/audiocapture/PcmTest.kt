package dev.piotrprus.audiocapture

import dev.piotrprus.audiocapture.internal.WavHeader
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PcmTest {

    @Test
    fun int16_is_little_endian_and_clamped() {
        val bytes = Pcm.floatsToInt16(floatArrayOf(0f, 1f, -1f, 2f, 0.5f))
        assertContentEquals(shortArrayOf(0, 32767, -32767, 32767, 16384), Pcm.int16ToShorts(bytes))
        assertEquals(0xFF.toByte(), bytes[2]) // 32767 = 0x7FFF, low byte first
        assertEquals(0x7F.toByte(), bytes[3])
    }

    @Test
    fun float32_round_trips() {
        val samples = floatArrayOf(0f, 0.25f, -0.75f, 1f)
        assertContentEquals(samples, Pcm.float32ToFloats(Pcm.floatsToFloat32(samples)))
    }

    @Test
    fun level_reports_peak_and_rms() {
        val level = Pcm.level(floatArrayOf(0.5f, -0.5f, 0.5f, -0.5f), 4)
        assertEquals(0.5f, level.peak)
        assertEquals(0.5f, level.rms)
        assertEquals(-6.0206f, level.peakDbfs, 0.001f)
    }

    @Test
    fun silence_is_the_decibel_floor() {
        assertEquals(AudioLevel.MIN_DBFS, AudioLevel.Silence.peakDbfs)
    }

    @Test
    fun chunk_reports_frames_and_duration() {
        val format = PcmFormat(sampleRate = 16_000, channels = 2, encoding = PcmEncoding.Int16)
        val chunk = AudioChunk(ByteArray(1_600 * 4), format, 0.milliseconds)
        assertEquals(1_600, chunk.frames)
        assertEquals(100.milliseconds, chunk.duration)
    }

    @Test
    fun chunk_level_matches_its_samples() {
        val format = PcmFormat(sampleRate = 8_000, channels = 1, encoding = PcmEncoding.Float32)
        val chunk = AudioChunk(Pcm.floatsToFloat32(floatArrayOf(0.5f, -0.5f)), format, 0.milliseconds)
        assertEquals(AudioLevel(peak = 0.5f, rms = 0.5f), chunk.level())
    }

    @Test
    fun encoder_follows_the_file_extension() {
        assertEquals(AudioEncoder.Wav, FileOutput("/a/take.WAV").encoder)
        assertEquals(AudioEncoder.AacLc, FileOutput("/a/memo.m4a").encoder)
    }

    @Test
    fun wav_header_describes_the_data() {
        val header = WavHeader.build(sampleRate = 16_000, channels = 1, dataBytes = 32_000)
        assertEquals("RIFF", header.decodeToString(0, 4))
        assertEquals("WAVE", header.decodeToString(8, 12))
        assertEquals(36 + 32_000, header.int32(4))
        assertEquals(16_000, header.int32(24))
        assertEquals(32_000, header.int32(28)) // byte rate
        assertEquals(32_000, header.int32(40))
    }

    @Test
    fun normalizer_adapts_to_a_hot_microphone_but_never_below_the_floor() {
        val normalizer = LevelNormalizer()
        val quiet = AudioLevel(peak = 0.2f, rms = 0.1f)
        val loud = AudioLevel(peak = 0.9f, rms = 0.5f)

        // Below the floor the reference stays put: plain division by the floor.
        assertEquals(0.2f / LevelNormalizer.DEFAULT_FLOOR, normalizer.normalize(quiet, 100.milliseconds), 0.0001f)

        // A second of loud speech raises the reference, so a medium level no longer pins the meter.
        val medium = AudioLevel(peak = 0.5f, rms = 0.3f)
        assertEquals(1f, LevelNormalizer().normalize(medium, 100.milliseconds))
        repeat(10) { normalizer.normalize(loud, 100.milliseconds) }
        assertEquals(0.5f / 0.87f, normalizer.normalize(medium, 100.milliseconds), 0.02f)

        // A long silence brings it back down, but only to the floor.
        normalizer.normalize(AudioLevel.Silence, 600.seconds)
        assertEquals(0.2f / LevelNormalizer.DEFAULT_FLOOR, normalizer.normalize(quiet, 100.milliseconds), 0.001f)
    }

    private fun ByteArray.int32(at: Int): Int =
        (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
            ((this[at + 2].toInt() and 0xFF) shl 16) or (this[at + 3].toInt() shl 24)
}
