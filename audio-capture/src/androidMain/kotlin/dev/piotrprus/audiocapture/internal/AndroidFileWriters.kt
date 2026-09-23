package dev.piotrprus.audiocapture.internal

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import dev.piotrprus.audiocapture.AudioCaptureException
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
        // RIFF sizes are 32-bit; past 4 GB the header would wrap and the file read as garbage.
        if (dataBytes + bytes.size > MAX_DATA_BYTES) {
            throw AudioCaptureException("WAV files cannot exceed 4 GB; the recording so far is kept")
        }
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

    private companion object {
        const val MAX_DATA_BYTES = 0xFFFF_FFFFL - (WavHeader.SIZE - 8)
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
    private var samplesMuxed = 0
    private var closed = false

    init {
        File(path).parentFile?.mkdirs()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AacSupport.clampBitRate(bitRate))
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
        var waits = 0
        while (offset < pcm.size) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index < 0 && ++waits > MAX_EOS_WAITS) throw AudioCaptureException("The AAC encoder stopped accepting input")
            if (index >= 0) {
                waits = 0
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
        var failure: Throwable? = null
        try {
            var index = -1
            for (attempt in 0 until MAX_EOS_WAITS) {
                index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) break
                drain(endOfStream = false)
            }
            if (index < 0) throw AudioCaptureException("The AAC encoder stopped accepting input")
            codec.queueInputBuffer(index, 0, 0, presentationTimeUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(endOfStream = true)
        } catch (e: Throwable) {
            failure = e
        } finally {
            runCatching { codec.stop() }
            codec.release()
            // MediaMuxer.stop() throws when no sample was written; that file would be unplayable.
            if (muxerStarted) runCatching { muxer.stop() }.onFailure { failure = failure ?: it }
            muxer.release()
        }
        failure?.let { throw it as? AudioCaptureException ?: AudioCaptureException("Could not finish $path", it) }
        if (samplesMuxed == 0) throw AudioCaptureException("No audio was encoded into $path")
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
                        samplesMuxed++
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

/** What the device's AAC encoder can do, from `MediaCodecList`. */
internal object AacSupport {
    private val capabilities: MediaCodecInfo.AudioCapabilities? by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { it.isEncoder && it.supportedTypes.any { type -> type.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) } }
            .firstNotNullOfOrNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_AUDIO_AAC).audioCapabilities }.getOrNull() }
    }

    fun supports(sampleRate: Int, channels: Int): Boolean {
        val audio = capabilities ?: return false
        return audio.isSampleRateSupported(sampleRate) && audio.maxInputChannelCount >= channels
    }

    /** Keeps the bit rate inside what the encoder accepts, which otherwise fails configure(). */
    fun clampBitRate(bitRate: Int): Int = capabilities?.bitrateRange?.clamp(bitRate) ?: bitRate
}
