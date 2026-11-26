package dev.piotrprus.audiocapture

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.startup.Initializer
import dev.piotrprus.audiocapture.internal.AacFileWriter
import dev.piotrprus.audiocapture.internal.AacSupport
import dev.piotrprus.audiocapture.internal.AndroidCaptureEngine
import dev.piotrprus.audiocapture.internal.DefaultCaptureSession
import dev.piotrprus.audiocapture.internal.PermissionRequester
import dev.piotrprus.audiocapture.internal.WavFileWriter
import dev.piotrprus.audiocapture.internal.toInputDevice
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
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
        if (PermissionRequester.isGranted(context)) MicPermission.Granted else MicPermission.Denied

    override suspend fun requestPermission(): MicPermission =
        if (PermissionRequester.request(context)) MicPermission.Granted else MicPermission.Denied

    override fun recordingPath(fileName: String): String {
        val directory = File(context.filesDir, "recordings").apply { mkdirs() }
        return File(directory, fileName).absolutePath
    }

    override fun inputDevices(): List<InputDevice> =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .mapNotNull { it.toInputDevice() }

    override fun inputDevicesFlow(): Flow<List<InputDevice>> = callbackFlow {
        val callback = object : AudioDeviceCallback() {
            // Also called once on registration with the devices already connected.
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                trySend(inputDevices())
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                trySend(inputDevices())
            }
        }
        trySend(inputDevices())
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        awaitClose { audioManager.unregisterAudioDeviceCallback(callback) }
    }.conflate().distinctUntilChanged()

    override fun isSupported(encoder: AudioEncoder, sampleRate: Int, channels: Int): Boolean = when (encoder) {
        AudioEncoder.AacLc -> AacSupport.supports(sampleRate, channels)
        AudioEncoder.Wav -> true
    }

    override suspend fun start(config: CaptureConfig): CaptureSession = withContext(Dispatchers.IO) {
        if (requestPermission() != MicPermission.Granted) {
            throw AudioCaptureException("Microphone permission was refused")
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
