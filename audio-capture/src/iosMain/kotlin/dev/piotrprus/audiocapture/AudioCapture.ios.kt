package dev.piotrprus.audiocapture

import dev.piotrprus.audiocapture.internal.DefaultCaptureSession
import dev.piotrprus.audiocapture.internal.ExtAudioFileWriter
import dev.piotrprus.audiocapture.internal.IosCaptureEngine
import dev.piotrprus.audiocapture.internal.toInputDevice
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFAudio.availableInputs

public actual fun AudioCapture(): AudioCapture = IosAudioCapture()

@OptIn(ExperimentalForeignApi::class)
private class IosAudioCapture : AudioCapture {

    private val session get() = AVAudioSession.sharedInstance()

    override fun permission(): MicPermission = when (session.recordPermission) {
        AVAudioSessionRecordPermissionGranted -> MicPermission.Granted
        AVAudioSessionRecordPermissionDenied -> MicPermission.Denied
        else -> MicPermission.NotDetermined
    }

    /**
     * iOS only lists inputs while the audio session category can record, so a category that
     * cannot (the default, `soloAmbient`) is switched to `playAndRecord` first. That does not
     * activate the session or interrupt other audio.
     */
    override fun inputDevices(): List<InputDevice> {
        val category = session.category
        if (category != AVAudioSessionCategoryRecord && category != AVAudioSessionCategoryPlayAndRecord) {
            session.setCategory(AVAudioSessionCategoryPlayAndRecord, null)
        }
        return session.availableInputs.orEmpty()
            .filterIsInstance<AVAudioSessionPortDescription>()
            .map { it.toInputDevice() }
    }

    override fun isSupported(encoder: AudioEncoder, sampleRate: Int, channels: Int): Boolean = when (encoder) {
        AudioEncoder.AacLc -> sampleRate in 8_000..48_000 && channels in 1..2
        AudioEncoder.Wav -> true
    }

    override suspend fun start(config: CaptureConfig): CaptureSession = withContext(Dispatchers.IO) {
        if (permission() == MicPermission.Denied) {
            throw AudioCaptureException("Microphone permission was denied")
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
