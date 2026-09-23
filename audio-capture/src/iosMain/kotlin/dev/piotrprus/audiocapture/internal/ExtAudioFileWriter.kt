package dev.piotrprus.audiocapture.internal

import dev.piotrprus.audiocapture.AudioCaptureException
import dev.piotrprus.audiocapture.AudioEncoder
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.AudioToolbox.AudioConverterGetProperty
import platform.AudioToolbox.AudioConverterGetPropertyInfo
import platform.AudioToolbox.AudioConverterRefVar
import platform.AudioToolbox.AudioConverterSetProperty
import platform.AudioToolbox.ExtAudioFileCreateWithURL
import platform.AudioToolbox.ExtAudioFileDispose
import platform.AudioToolbox.ExtAudioFileGetProperty
import platform.AudioToolbox.ExtAudioFileRef
import platform.AudioToolbox.ExtAudioFileRefVar
import platform.AudioToolbox.ExtAudioFileSetProperty
import platform.AudioToolbox.ExtAudioFileWrite
import platform.AudioToolbox.kAudioConverterApplicableEncodeBitRates
import platform.AudioToolbox.kAudioConverterEncodeBitRate
import platform.AudioToolbox.kAudioFileFlags_EraseFile
import platform.AudioToolbox.kAudioFileM4AType
import platform.AudioToolbox.kAudioFileWAVEType
import platform.AudioToolbox.kExtAudioFileProperty_AudioConverter
import platform.AudioToolbox.kAppleSoftwareAudioCodecManufacturer
import platform.AudioToolbox.kExtAudioFileProperty_ClientDataFormat
import platform.AudioToolbox.kExtAudioFileProperty_CodecManufacturer
import platform.AudioToolbox.kExtAudioFileProperty_ConverterConfig
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioStreamBasicDescription
import platform.CoreAudioTypes.AudioValueRange
import platform.CoreAudioTypes.kAudioFormatFlagIsFloat
import platform.CoreAudioTypes.kAudioFormatFlagIsPacked
import platform.CoreAudioTypes.kAudioFormatFlagIsSignedInteger
import platform.CoreAudioTypes.kAudioFormatLinearPCM
import platform.CoreAudioTypes.kAudioFormatMPEG4AAC
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFURLRef
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.darwin.OSStatus

/**
 * Writes files through `ExtAudioFile`, which encodes the float frames it is given into the file's
 * format.
 *
 * Not `AVAudioFile`: that one only finishes its file when the object is deallocated (or on
 * `close()`, which needs iOS 18), and Kotlin/Native gives no control over when that happens.
 * `ExtAudioFileDispose` finishes the file at a known moment.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ExtAudioFileWriter(
    private val path: String,
    encoder: AudioEncoder,
    sampleRate: Int,
    private val channels: Int,
    bitRate: Int,
) : AudioFileWriter {

    private val file: ExtAudioFileRef
    private var closed = false

    init {
        NSURL.fileURLWithPath(path).URLByDeletingLastPathComponent?.path?.let { directory ->
            NSFileManager.defaultManager.createDirectoryAtPath(
                directory,
                withIntermediateDirectories = true,
                attributes = null,
                error = null,
            )
        }
        file = memScoped {
            val fileFormat = alloc<AudioStreamBasicDescription>().apply {
                mSampleRate = sampleRate.toDouble()
                mChannelsPerFrame = channels.toUInt()
                when (encoder) {
                    AudioEncoder.AacLc -> {
                        mFormatID = kAudioFormatMPEG4AAC
                        mFramesPerPacket = 1024u
                    }
                    AudioEncoder.Wav -> {
                        mFormatID = kAudioFormatLinearPCM
                        mFormatFlags = kAudioFormatFlagIsSignedInteger or kAudioFormatFlagIsPacked
                        mBitsPerChannel = 16u
                        mFramesPerPacket = 1u
                        mBytesPerFrame = (2 * channels).toUInt()
                        mBytesPerPacket = (2 * channels).toUInt()
                    }
                }
            }
            val fileType = when (encoder) {
                AudioEncoder.AacLc -> kAudioFileM4AType
                AudioEncoder.Wav -> kAudioFileWAVEType
            }
            val ref = alloc<ExtAudioFileRefVar>()
            @Suppress("UNCHECKED_CAST")
            val url = CFBridgingRetain(NSURL.fileURLWithPath(path)) as CFURLRef
            try {
                check(
                    ExtAudioFileCreateWithURL(url, fileType, fileFormat.ptr, null, kAudioFileFlags_EraseFile, ref.ptr),
                    "create $path",
                )
            } finally {
                CFRelease(url)
            }
            val created = ref.value ?: throw AudioCaptureException("Could not create $path")

            // The software encoder keeps working through interruptions, where a hardware codec
            // can be taken away mid-file. Must be set before the client format.
            if (encoder == AudioEncoder.AacLc) {
                val manufacturer = alloc<UIntVar>().apply { value = kAppleSoftwareAudioCodecManufacturer }
                ExtAudioFileSetProperty(created, kExtAudioFileProperty_CodecManufacturer, sizeOf<UIntVar>().toUInt(), manufacturer.ptr)
            }

            val clientFormat = alloc<AudioStreamBasicDescription>().apply {
                mSampleRate = sampleRate.toDouble()
                mChannelsPerFrame = channels.toUInt()
                mFormatID = kAudioFormatLinearPCM
                mFormatFlags = kAudioFormatFlagIsFloat or kAudioFormatFlagIsPacked
                mBitsPerChannel = 32u
                mFramesPerPacket = 1u
                mBytesPerFrame = (4 * channels).toUInt()
                mBytesPerPacket = (4 * channels).toUInt()
            }
            check(
                ExtAudioFileSetProperty(
                    created,
                    kExtAudioFileProperty_ClientDataFormat,
                    sizeOf<AudioStreamBasicDescription>().toUInt(),
                    clientFormat.ptr,
                ),
                "set the client format",
            )
            if (encoder == AudioEncoder.AacLc) setBitRate(created, bitRate)
            created
        }
    }

    override fun write(samples: FloatArray, count: Int) {
        if (count == 0) return
        samples.usePinned { pinned ->
            memScoped {
                val buffers = alloc<AudioBufferList>()
                buffers.mNumberBuffers = 1u
                buffers.mBuffers[0].mNumberChannels = channels.toUInt()
                buffers.mBuffers[0].mDataByteSize = (count * 4).toUInt()
                buffers.mBuffers[0].mData = pinned.addressOf(0)
                check(ExtAudioFileWrite(file, (count / channels).toUInt(), buffers.ptr), "write")
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        check(ExtAudioFileDispose(file), "finish $path")
    }

    override fun delete() {
        runCatching { close() }
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    /**
     * Picks the highest bit rate the encoder supports at or below [bitRate], or its lowest when
     * even that is higher. The ceiling depends on sample rate and channels (16 kHz mono tops out
     * well below 64 kbit/s), and an unsupported value is not rejected when set: the encoder takes
     * it and then fails every write.
     *
     * Every early return leaves the encoder's own default bit rate in place, which always works;
     * only a value that was never validated can break the file.
     */
    private fun setBitRate(target: ExtAudioFileRef, bitRate: Int) = memScoped {
        val converter = alloc<AudioConverterRefVar>()
        val converterSize = alloc<UIntVar>().apply { value = sizeOf<AudioConverterRefVar>().toUInt() }
        if (ExtAudioFileGetProperty(target, kExtAudioFileProperty_AudioConverter, converterSize.ptr, converter.ptr) != 0) {
            return@memScoped
        }
        val rangesSize = alloc<UIntVar>()
        if (AudioConverterGetPropertyInfo(converter.value, kAudioConverterApplicableEncodeBitRates, rangesSize.ptr, null) != 0) {
            return@memScoped
        }
        val count = (rangesSize.value.toLong() / sizeOf<AudioValueRange>()).toInt()
        if (count == 0) return@memScoped
        val ranges = allocArray<AudioValueRange>(count)
        if (AudioConverterGetProperty(converter.value, kAudioConverterApplicableEncodeBitRates, rangesSize.ptr, ranges) != 0) {
            return@memScoped
        }
        val supported = (0 until count).map { ranges[it].mMaximum }
        val chosen = supported.filter { it <= bitRate }.maxOrNull() ?: supported.min()
        val rate = alloc<UIntVar>().apply { value = chosen.toUInt() }
        if (AudioConverterSetProperty(converter.value, kAudioConverterEncodeBitRate, sizeOf<UIntVar>().toUInt(), rate.ptr) != 0) {
            return@memScoped
        }
        // Tells ExtAudioFile to pick up the converter's new settings.
        val noConfig = alloc<COpaquePointerVar>()
        ExtAudioFileSetProperty(target, kExtAudioFileProperty_ConverterConfig, sizeOf<COpaquePointerVar>().toUInt(), noConfig.ptr)
    }

    private fun check(status: OSStatus, what: String) {
        if (status != 0) throw AudioCaptureException("Could not $what (OSStatus $status)")
    }
}
