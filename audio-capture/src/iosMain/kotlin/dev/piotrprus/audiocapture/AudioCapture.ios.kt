package dev.piotrprus.audiocapture

import dev.piotrprus.audiocapture.internal.DefaultCaptureSession
import dev.piotrprus.audiocapture.internal.ExtAudioFileWriter
import dev.piotrprus.audiocapture.internal.IosCaptureEngine
import dev.piotrprus.audiocapture.internal.IosPermission
import dev.piotrprus.audiocapture.internal.toInputDevice
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothHFP
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonCategoryChange
import platform.AVFAudio.availableInputs
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplicationDidBecomeActiveNotification

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
     * iOS only lists inputs while the audio session category can record, and only lists Bluetooth
     * headsets while the category allows the hands-free profile. When either is missing, the
     * category is switched to `playAndRecord` with `allowBluetoothHFP` just long enough to read the
     * list and then put back with its original mode and options.
     *
     * While a capture session is running the category is left alone, since switching it would
     * re-route the recording. Bluetooth headsets are then listed only if that session allows them.
     */
    override fun inputDevices(): List<InputDevice> {
        val category = session.category
        val mode = session.mode
        val options = session.categoryOptions
        val canRecord = category == AVAudioSessionCategoryRecord || category == AVAudioSessionCategoryPlayAndRecord
        val listsBluetooth = (options and AVAudioSessionCategoryOptionAllowBluetoothHFP) != 0uL
        val switched = (!canRecord || !listsBluetooth) && IosCaptureEngine.activeSessions.value == 0
        if (switched) {
            session.setCategory(
                if (canRecord) category else AVAudioSessionCategoryPlayAndRecord,
                mode,
                (if (canRecord) options else 0uL) or AVAudioSessionCategoryOptionAllowBluetoothHFP,
                null,
            )
        }
        try {
            return session.availableInputs.orEmpty()
                .filterIsInstance<AVAudioSessionPortDescription>()
                .map { it.toInputDevice() }
        } finally {
            if (switched && category != null) {
                session.setCategory(category, mode, options, null)
            }
        }
    }

    /**
     * Re-reads [inputDevices] on every audio route change except a category change, and whenever
     * the app becomes active (a headset may have connected while it was in the background).
     *
     * Category changes are skipped on purpose: [inputDevices] switches the category to read the
     * list, so reacting to them would make it trigger itself in an endless loop. The list is read
     * off the main thread because switching the category can take tens of milliseconds, and a burst
     * of notifications is read once.
     */
    override fun inputDevicesFlow(): Flow<List<InputDevice>> = callbackFlow {
        send(Unit)
        val center = NSNotificationCenter.defaultCenter
        val observers = listOf(
            center.addObserverForName(AVAudioSessionRouteChangeNotification, session, null) { note ->
                val reason = (note?.userInfo?.get(AVAudioSessionRouteChangeReasonKey) as? NSNumber)?.unsignedIntegerValue
                if (reason != AVAudioSessionRouteChangeReasonCategoryChange) trySend(Unit)
            },
            center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, null) { _ -> trySend(Unit) },
        )
        awaitClose { observers.forEach { center.removeObserver(it) } }
    }.conflate()
        .map { inputDevices() }
        .flowOn(Dispatchers.IO)
        .distinctUntilChanged()

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
