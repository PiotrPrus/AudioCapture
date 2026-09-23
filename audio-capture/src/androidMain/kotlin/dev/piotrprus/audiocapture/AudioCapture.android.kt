package dev.piotrprus.audiocapture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.startup.Initializer
import dev.piotrprus.audiocapture.internal.AacFileWriter
import dev.piotrprus.audiocapture.internal.AndroidCaptureEngine
import dev.piotrprus.audiocapture.internal.DefaultCaptureSession
import dev.piotrprus.audiocapture.internal.WavFileWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

public actual fun AudioCapture(): AudioCapture = AudioCapture(
    AudioCaptureInitializer.context
        ?: error(
            "AudioCapture was not initialised. Keep androidx.startup's InitializationProvider in " +
                "the merged manifest, or call AudioCapture(context).",
        ),
)

/** For apps that disable `androidx.startup` initializers. Any context works; only the application context is kept. */
public fun AudioCapture(context: Context): AudioCapture = AndroidAudioCapture(context.applicationContext)

/** Captures the application context at startup so the no-argument `AudioCapture()` works. */
public class AudioCaptureInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        Companion.context = context.applicationContext
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

    internal companion object {
        @Volatile
        var context: Context? = null
    }
}

private class AndroidAudioCapture(private val context: Context) : AudioCapture {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    override fun permission(): MicPermission =
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            MicPermission.Granted
        } else {
            MicPermission.Denied
        }

    override fun inputDevices(): List<InputDevice> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { device ->
            val type = device.inputType() ?: return@mapNotNull null
            // Phones list each built-in microphone separately ("bottom", "back"); the address tells them apart.
            val address = device.address.orEmpty()
            val name = device.productName.toString() + if (address.isNotBlank()) " ($address)" else ""
            InputDevice(id = device.id.toString(), name = name, type = type)
        }

    override fun isSupported(encoder: AudioEncoder): Boolean = true

    override suspend fun start(config: CaptureConfig): CaptureSession = withContext(Dispatchers.IO) {
        if (permission() != MicPermission.Granted) {
            throw AudioCaptureException("RECORD_AUDIO permission is not granted")
        }
        val writer = config.file?.let { file ->
            try {
                when (file.encoder) {
                    AudioEncoder.AacLc -> AacFileWriter(file.path, config.sampleRate, config.channels, file.bitRate)
                    AudioEncoder.Wav -> WavFileWriter(file.path, config.sampleRate, config.channels)
                }
            } catch (e: Exception) {
                throw AudioCaptureException("Could not create ${file.path}", e)
            }
        }
        DefaultCaptureSession(config, AndroidCaptureEngine(context, config), writer).also { it.begin() }
    }

    /** `null` for inputs that are not microphones: the telephony uplink, tuners, loopback, echo reference. */
    private fun AudioDeviceInfo.inputType(): InputDeviceType? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> InputDeviceType.BuiltIn
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> InputDeviceType.WiredHeadset
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> InputDeviceType.Bluetooth
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> InputDeviceType.Usb
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> InputDeviceType.LineIn
        AudioDeviceInfo.TYPE_TELEPHONY, AudioDeviceInfo.TYPE_REMOTE_SUBMIX,
        AudioDeviceInfo.TYPE_FM_TUNER, AudioDeviceInfo.TYPE_TV_TUNER, TYPE_ECHO_REFERENCE,
        -> null
        else -> when {
            Build.VERSION.SDK_INT >= 26 && type == AudioDeviceInfo.TYPE_USB_HEADSET -> InputDeviceType.Usb
            Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET -> InputDeviceType.Bluetooth
            else -> InputDeviceType.Other
        }
    }

    private companion object {
        /**
         * `AudioDeviceInfo.TYPE_ECHO_REFERENCE`, a system API missing from the public SDK. Pixels
         * list it as an input named after the phone; it carries playback, not the microphone.
         */
        const val TYPE_ECHO_REFERENCE = 28
    }
}
