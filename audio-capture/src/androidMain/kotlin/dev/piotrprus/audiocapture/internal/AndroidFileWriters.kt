package dev.piotrprus.audiocapture.internal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.piotrprus.audiocapture.Pcm
import java.io.File
import java.io.RandomAccessFile

/** 16-bit PCM WAV. The header is written up front and patched with the real sizes on [close]. */
internal class WavFileWriter(
    private val path: String,
    private val sampleRate: Int,
    private val channels: Int,
) : AudioFileWriter {

    private val file: RandomAccessFile
    private var dataBytes = 0L
    private var closed = false

    init {
        File(path).parentFile?.mkdirs()
        file = RandomAccessFile(path, "rw")
        file.setLength(0)
        file.write(WavHeader.build(sampleRate, channels, 0))
    }

    override fun write(samples: FloatArray, count: Int) {
        val bytes = Pcm.floatsToInt16(samples, count)
        file.write(bytes)
        dataBytes += bytes.size
    }

    override fun close() {
        if (closed) return
        closed = true
        file.use {
            it.seek(0)
            it.write(WavHeader.build(sampleRate, channels, dataBytes))
        }
    }

    override fun delete() {
        runCatching { close() }
        File(path).delete()
    }
}

/**
 * AAC-LC in MPEG-4 through [MediaCodec] and [MediaMuxer].
 *
 * Presentation times come from the number of frames fed in, not the clock, so a pause leaves no
 * gap in the file.
 */
internal class AacFileWriter(
    private val path: String,
    private val sampleRate: Int,
    private val channels: Int,
    bitRate: Int,
) : AudioFileWriter {

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var muxerStarted = false
    private var framesQueued = 0L
    private var closed = false

    init {
        File(path).parentFile?.mkdirs()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_BYTES)
        }
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            codec.release()
            throw e
        }
    }

    override fun write(samples: FloatArray, count: Int) {
        val pcm = Pcm.floatsToInt16(samples, count)
        val frameBytes = channels * 2
        var offset = 0
        while (offset < pcm.size) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val buffer = codec.getInputBuffer(index) ?: error("No input buffer $index")
                buffer.clear()
                val length = minOf(buffer.remaining() / frameBytes * frameBytes, pcm.size - offset)
                buffer.put(pcm, offset, length)
                codec.queueInputBuffer(index, 0, length, presentationTimeUs(), 0)
                framesQueued += length / frameBytes
                offset += length
            }
            drain(endOfStream = false)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            var index: Int
            do {
                index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index < 0) drain(endOfStream = false)
            } while (index < 0)
            codec.queueInputBuffer(index, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(endOfStream = true)
        } finally {
            runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
        }
    }

    override fun delete() {
        runCatching { close() }
        File(path).delete()
    }

    private fun presentationTimeUs(): Long = framesQueued * 1_000_000L / sampleRate

    private fun drain(endOfStream: Boolean) {
        var idleWaits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idleWaits > MAX_EOS_WAITS) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index) ?: error("No output buffer $index")
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_INPUT_BYTES = 16 * 1024

        /** 200 × 10 ms: give up on a codec that never signals end of stream rather than hang stop(). */
        const val MAX_EOS_WAITS = 200
    }
}
