package dev.piotrprus.audiocapture

import dev.piotrprus.audiocapture.internal.DefaultCaptureSession
import dev.piotrprus.audiocapture.internal.ExtAudioFileWriter
import dev.piotrprus.audiocapture.internal.IosCaptureEngine
import dev.piotrprus.audiocapture.internal.IosPermission
import dev.piotrprus.audiocapture.internal.toInputDevice
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.availableInputs
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask

public actual fun AudioCapture(): AudioCapture = IosAudioCapture()

@OptIn(ExperimentalForeignApi::class)
private class IosAudioCapture : AudioCapture {

    private val session get() = AVAudioSession.sharedInstance()

    override fun permission(): MicPermission = IosPermission.current()

    override suspend fun requestPermission(): MicPermission = IosPermission.request()

    override fun recordingPath(fileName: String): String {
        val support = NSFileManager.defaultManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask)
            .firstOrNull() as? NSURL ?: error("No Application Support directory")
        val directory = support.URLByAppendingPathComponent("recordings") ?: error("Bad recordings directory")
        NSFileManager.defaultManager.createDirectoryAtURL(directory, withIntermediateDirectories = true, attributes = null, error = null)
        return directory.URLByAppendingPathComponent(fileName)?.path ?: error("Bad file name $fileName")
    }

    /**
     * iOS only lists inputs while the audio session category can record. When it cannot (the
     * default, `soloAmbient`), the category is switched to `playAndRecord` just long enough to
     * read the list and then put back with its original mode and options.
     */
    override fun inputDevices(): List<InputDevice> {
        val category = session.category
        val mode = session.mode
        val options = session.categoryOptions
        val canRecord = category == AVAudioSessionCategoryRecord || category == AVAudioSessionCategoryPlayAndRecord
        if (!canRecord) session.setCategory(AVAudioSessionCategoryPlayAndRecord, null)
        try {
            return session.availableInputs.orEmpty()
                .filterIsInstance<AVAudioSessionPortDescription>()
                .map { it.toInputDevice() }
        } finally {
            if (!canRecord && category != null) {
                session.setCategory(category, mode, options, null)
            }
        }
    }

    override fun isSupported(encoder: AudioEncoder, sampleRate: Int, channels: Int): Boolean = when (encoder) {
        AudioEncoder.AacLc -> sampleRate in 8_000..48_000 && channels in 1..2
        AudioEncoder.Wav -> true
    }

    override suspend fun start(config: CaptureConfig): CaptureSession = withContext(Dispatchers.IO) {
        // Without permission iOS delivers silence rather than an error, so never start without it.
        if (IosPermission.request() != MicPermission.Granted) {
            throw AudioCaptureException("Microphone permission was refused")
        }
        val writer = config.file?.let { file ->
            if (!isSupported(file.encoder, config.sampleRate, config.channels)) {
                throw AudioCaptureException("${file.encoder} files cannot be ${config.sampleRate} Hz with ${config.channels} channel(s)")
            }
            ExtAudioFileWriter(file.path, file.encoder, config.sampleRate, config.channels, file.bitRate)
        }
        DefaultCaptureSession(config, IosCaptureEngine(config), writer).also { it.begin() }
    }
}
