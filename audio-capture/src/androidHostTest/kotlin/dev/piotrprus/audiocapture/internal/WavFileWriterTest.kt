package dev.piotrprus.audiocapture.internal

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class WavFileWriterTest {

    @Test
    fun header_is_patched_with_the_final_size() {
        val file = File.createTempFile("capture", ".wav")
        val writer = WavFileWriter(file.path, sampleRate = 16_000, channels = 1)

        writer.write(FloatArray(1_600) { 0.5f }, 1_600)
        writer.write(FloatArray(800) { -0.5f }, 400)
        writer.close()

        val bytes = file.readBytes()
        assertEquals(WavHeader.SIZE + 2_000 * 2, bytes.size)
        assertEquals(WavHeader.build(16_000, 1, 4_000L).toList(), bytes.copyOf(WavHeader.SIZE).toList())
        file.delete()
    }

    @Test
    fun delete_removes_the_file() {
        val file = File.createTempFile("capture", ".wav")
        WavFileWriter(file.path, sampleRate = 16_000, channels = 1).delete()
        assertFalse(file.exists())
    }
}
