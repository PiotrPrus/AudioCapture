package dev.piotrprus.audiocapture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.startup.Initializer
import dev.piotrprus.audiocapture.internal.AacFileWriter
import dev.piotrprus.audiocapture.internal.AacSupport
import dev.piotrprus.audiocapture.internal.AndroidCaptureEngine
import dev.piotrprus.audiocapture.internal.DefaultCaptureSession
import dev.piotrprus.audiocapture.internal.WavFileWriter
import dev.piotrprus.audiocapture.internal.toInputDevice
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

    /**
     * Bluetooth headset microphones are left out: recording from them needs the app to route
     * audio through `AudioManager.setCommunicationDevice`, which this library does not do yet.
     */
    override fun inputDevices(): List<InputDevice> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .mapNotNull { it.toInputDevice() }
            .filter { it.type != InputDeviceType.Bluetooth }

    override fun isSupported(encoder: AudioEncoder, sampleRate: Int, channels: Int): Boolean = when (encoder) {
        AudioEncoder.AacLc -> AacSupport.supports(sampleRate, channels)
        AudioEncoder.Wav -> true
    }

    override suspend fun start(config: CaptureConfig): CaptureSession = withContext(Dispatchers.IO) {
        if (permission() != MicPermission.Granted) {
            throw AudioCaptureException("RECORD_AUDIO permission is not granted")
        }
        val writer = config.file?.let { file ->
            if (!isSupported(file.encoder, config.sampleRate, config.channels)) {
                throw AudioCaptureException("This device cannot encode ${file.encoder} at ${config.sampleRate} Hz with ${config.channels} channel(s)")
            }
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
}
