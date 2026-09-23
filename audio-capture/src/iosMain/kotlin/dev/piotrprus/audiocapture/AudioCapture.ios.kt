package dev.piotrprus.audiocapture

import dev.piotrprus.audiocapture.internal.DefaultCaptureSession
import dev.piotrprus.audiocapture.internal.ExtAudioFileWriter
import dev.piotrprus.audiocapture.internal.IosCaptureEngine
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionPortBluetoothHFP
import platform.AVFAudio.AVAudioSessionPortBuiltInMic
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionPortHeadsetMic
import platform.AVFAudio.AVAudioSessionPortLineIn
import platform.AVFAudio.AVAudioSessionPortUSBAudio
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
        return session.availableInputs.orEmpty().filterIsInstance<AVAudioSessionPortDescription>().map { port ->
            InputDevice(
                id = port.UID,
                name = port.portName,
                type = when (port.portType) {
                    AVAudioSessionPortBuiltInMic -> InputDeviceType.BuiltIn
                    AVAudioSessionPortHeadsetMic -> InputDeviceType.WiredHeadset
                    AVAudioSessionPortBluetoothHFP -> InputDeviceType.Bluetooth
                    AVAudioSessionPortUSBAudio -> InputDeviceType.Usb
                    AVAudioSessionPortLineIn -> InputDeviceType.LineIn
                    else -> InputDeviceType.Other
                },
            )
        }
    }

    override fun isSupported(encoder: AudioEncoder): Boolean = true

    override suspend fun start(config: CaptureConfig): CaptureSession = withContext(Dispatchers.IO) {
        if (permission() == MicPermission.Denied) {
            throw AudioCaptureException("Microphone permission was denied")
        }
        val writer = config.file?.let { file ->
            ExtAudioFileWriter(file.path, file.encoder, config.sampleRate, config.channels, file.bitRate)
        }
        DefaultCaptureSession(config, IosCaptureEngine(config), writer).also { it.begin() }
    }
}
