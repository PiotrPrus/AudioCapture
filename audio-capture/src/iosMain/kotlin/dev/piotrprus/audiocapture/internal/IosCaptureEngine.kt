package dev.piotrprus.audiocapture.internal

import dev.piotrprus.audiocapture.AudioCaptureException
import dev.piotrprus.audiocapture.CaptureConfig
import dev.piotrprus.audiocapture.ExperimentalVoiceProcessing
import dev.piotrprus.audiocapture.InputDevice
import dev.piotrprus.audiocapture.InterruptionMode
import dev.piotrprus.audiocapture.IosBluetoothInput
import dev.piotrprus.audiocapture.IosDucking
import dev.piotrprus.audiocapture.IosSessionCategory
import dev.piotrprus.audiocapture.IosSessionMode
import dev.piotrprus.audiocapture.VoiceProcessing
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.AVFAudio.AVAudioConverter
import platform.AVFAudio.AVAudioConverterInputStatus_HaveData
import platform.AVFAudio.AVAudioConverterInputStatus_NoDataNow
import platform.AVFAudio.AVAudioConverterOutputStatus_Error
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioEngineConfigurationChangeNotification
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPCMFormatFloat32
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothHFP
import platform.AVFAudio.AVAudioSessionCategoryOptionBluetoothHighQualityRecording
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionReasonAppWasSuspended
import platform.AVFAudio.AVAudioSessionInterruptionReasonKey
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionMediaServicesWereResetNotification
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.AVAudioVoiceProcessingOtherAudioDuckingConfiguration
import platform.AVFAudio.AVAudioVoiceProcessingOtherAudioDuckingLevelDefault
import platform.AVFAudio.AVAudioVoiceProcessingOtherAudioDuckingLevelMax
import platform.AVFAudio.AVAudioVoiceProcessingOtherAudioDuckingLevelMid
import platform.AVFAudio.AVAudioVoiceProcessingOtherAudioDuckingLevelMin
import platform.AVFAudio.availableInputs
import platform.AVFAudio.currentRoute
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSThread
import platform.darwin.NSObjectProtocol

/**
 * [CaptureEngine] over an [AVAudioEngine] input tap.
 *
 * The tap delivers whatever the hardware runs at (often 48 kHz, sometimes stereo). An
 * [AVAudioConverter] resamples that to the configured rate and channel count, with proper
 * filtering: asking the audio session for a preferred rate is only a hint iOS may ignore.
 *
 * System events (interruptions, route changes, media services resets) arrive on one serial queue
 * and are handled under the same lock as the app's calls:
 * - A route change stops the engine and may change the input format; the tap is rebuilt for the
 *   new format and capture restarts unless the session is paused or interrupted.
 * - A media services reset invalidates every audio object; the session and engine are rebuilt.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalVoiceProcessing::class)
internal class IosCaptureEngine(private val config: CaptureConfig) : CaptureEngine {

    private val session = AVAudioSession.sharedInstance()
    private val lock = NSRecursiveLock()
    private val events = NSOperationQueue().apply {
        maxConcurrentOperationCount = 1
        name = "AudioCapture"
    }
    private val sessionObservers = mutableListOf<NSObjectProtocol>()
    private var engineObserver: NSObjectProtocol? = null
    private val targetFormat = AVAudioFormat(
        commonFormat = AVAudioPCMFormatFloat32,
        sampleRate = config.sampleRate.toDouble(),
        channels = config.channels.toUInt(),
        interleaved = true,
    )
    private val voiceProcessing = config.voiceProcessing?.takeIf {
        it.applyOnIos && (it.echoCancel || it.noiseSuppress || it.autoGain)
    }

    private var engine: AVAudioEngine? = null
    private var converter: AVAudioConverter? = null
    private var stopped = false
    private var userPaused = false
    private var interrupted = false
    private lateinit var listener: CaptureEngine.Listener

    override val routedDevice: InputDevice?
        get() = session.currentRoute.inputs.filterIsInstance<AVAudioSessionPortDescription>().firstOrNull()?.toInputDevice()

    override val appliedVoiceProcessing: VoiceProcessing?
        // iOS voice processing always cancels echo and suppresses noise together.
        get() = voiceProcessing?.let { VoiceProcessing(echoCancel = true, noiseSuppress = true, autoGain = it.autoGain) }

    override fun start(listener: CaptureEngine.Listener) = locked {
        this.listener = listener
        if (voiceProcessing != null && config.ios.category != IosSessionCategory.PlayAndRecord) {
            throw AudioCaptureException("Voice processing on iOS needs IosSessionCategory.PlayAndRecord")
        }
        if (config.ios.manageAudioSession) configureSession()
        observeSession()
        buildEngine()
    }

    override fun pause() = locked {
        val current = engine ?: return@locked
        userPaused = true
        current.stop()
        if (config.ios.manageAudioSession && config.ios.deactivateOnPause) deactivateSession()
    }

    override fun resume() = locked {
        userPaused = false
        interrupted = false
        restart()
    }

    override fun stop() = locked {
        if (stopped) return@locked
        stopped = true
        sessionObservers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        sessionObservers.clear()
        tearDownEngine()
        if (config.ios.manageAudioSession) {
            // A preferred input would otherwise outlive the session and steer the app's other audio.
            if (config.device != null) session.setPreferredInput(null, null)
            deactivateSession()
        }
    }

    private fun restart() {
        val current = engine ?: return
        if (current.running) return
        if (config.ios.manageAudioSession) {
            retryingMediaServices("Could not activate the audio session") { session.setActive(true, it) }
        }
        converter?.reset()
        startEngine(current)
    }

    private fun configureSession() {
        val category = when (config.ios.category) {
            IosSessionCategory.Record -> AVAudioSessionCategoryRecord
            IosSessionCategory.PlayAndRecord -> AVAudioSessionCategoryPlayAndRecord
        }
        val mode = when (config.ios.mode) {
            IosSessionMode.Default -> AVAudioSessionModeDefault
            IosSessionMode.Measurement -> AVAudioSessionModeMeasurement
            IosSessionMode.VoiceChat -> AVAudioSessionModeVoiceChat
        }
        var options = 0uL
        when (config.ios.bluetoothInput) {
            IosBluetoothInput.Off -> Unit
            IosBluetoothInput.Hfp -> options = options or AVAudioSessionCategoryOptionAllowBluetoothHFP
            IosBluetoothInput.HighQuality -> {
                options = options or AVAudioSessionCategoryOptionAllowBluetoothHFP
                if (isAtLeast(26)) options = options or AVAudioSessionCategoryOptionBluetoothHighQualityRecording
            }
        }
        if (config.ios.category == IosSessionCategory.PlayAndRecord) {
            if (config.ios.mixWithOthers) options = options or AVAudioSessionCategoryOptionMixWithOthers
            if (config.ios.defaultToSpeaker) options = options or AVAudioSessionCategoryOptionDefaultToSpeaker
        }
        retryingMediaServices("Could not set the audio session category") { session.setCategory(category, mode, options, it) }
        config.device?.let { wanted ->
            val port = session.availableInputs.orEmpty()
                .filterIsInstance<AVAudioSessionPortDescription>()
                .firstOrNull { it.UID == wanted.id }
                ?: throw AudioCaptureException("${wanted.name} is no longer connected")
            nsCheck("Could not select ${wanted.name}") { session.setPreferredInput(port, it) }
        }
        retryingMediaServices("Could not activate the audio session") { session.setActive(true, it) }
    }

    private fun deactivateSession() {
        session.setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
    }

    private fun buildEngine() {
        val created = AVAudioEngine()
        engine = created
        voiceProcessing?.let { wanted ->
            nsCheck("Could not enable voice processing") { created.inputNode.setVoiceProcessingEnabled(true, it) }
            created.inputNode.setVoiceProcessingAGCEnabled(wanted.autoGain)
            if (isAtLeast(17)) {
                created.inputNode.voiceProcessingOtherAudioDuckingConfiguration =
                    cValue<AVAudioVoiceProcessingOtherAudioDuckingConfiguration> {
                        enableAdvancedDucking = wanted.iosDucking != IosDucking.Default
                        duckingLevel = when (wanted.iosDucking) {
                            IosDucking.Default -> AVAudioVoiceProcessingOtherAudioDuckingLevelDefault
                            IosDucking.Min -> AVAudioVoiceProcessingOtherAudioDuckingLevelMin
                            IosDucking.Mid -> AVAudioVoiceProcessingOtherAudioDuckingLevelMid
                            IosDucking.Max -> AVAudioVoiceProcessingOtherAudioDuckingLevelMax
                        }
                    }
            }
        }
        installTap(created)
        engineObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            AVAudioEngineConfigurationChangeNotification,
            created,
            events,
        ) { _ -> onConfigurationChange() }
        if (!userPaused && !interrupted) startEngine(created)
    }

    private fun tearDownEngine() {
        engineObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        engineObserver = null
        engine?.let {
            it.inputNode.removeTapOnBus(0u)
            it.stop()
        }
        engine = null
        converter = null
    }

    private fun installTap(target: AVAudioEngine) {
        val input = target.inputNode
        val inputFormat = input.outputFormatForBus(0u)
        if (inputFormat.sampleRate <= 0.0 || inputFormat.channelCount == 0u) {
            throw AudioCaptureException("No microphone input is available")
        }
        val created = AVAudioConverter(fromFormat = inputFormat, toFormat = targetFormat)
        if (inputFormat.channelCount > targetFormat.channelCount) created.downmix = true
        converter = created
        val ratio = targetFormat.sampleRate / inputFormat.sampleRate
        val tapFrames = (inputFormat.sampleRate * config.chunkDuration.inWholeMilliseconds / 1000).toUInt()
        // One output buffer per tap, reused for every callback; grown only if a callback is larger.
        var output: AVAudioPCMBuffer? = null
        input.installTapOnBus(0u, tapFrames, inputFormat) { buffer, _ ->
            if (buffer != null) {
                val needed = (buffer.frameLength.toDouble() * ratio).toUInt() + CONVERTER_SLACK_FRAMES
                val out = output?.takeIf { it.frameCapacity >= needed }
                    ?: AVAudioPCMBuffer(pCMFormat = targetFormat, frameCapacity = needed).also { output = it }
                convert(buffer, out, created)
            }
        }
    }

    /** Runs on the tap's thread. */
    private fun convert(buffer: AVAudioPCMBuffer, out: AVAudioPCMBuffer, converter: AVAudioConverter) {
        out.frameLength = 0u
        var supplied = false
        val failure = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            val status = converter.convertToBuffer(out, error.ptr) { _, inputStatus ->
                if (supplied) {
                    inputStatus?.pointed?.value = AVAudioConverterInputStatus_NoDataNow
                    null
                } else {
                    supplied = true
                    inputStatus?.pointed?.value = AVAudioConverterInputStatus_HaveData
                    buffer
                }
            }
            if (status == AVAudioConverterOutputStatus_Error) error.value?.localizedDescription ?: "unknown error" else null
        }
        if (failure != null) {
            listener.onError(AudioCaptureException("Audio conversion failed: $failure"))
            return
        }
        val frames = out.frameLength.toInt()
        val data = out.floatChannelData?.get(0) ?: return
        if (frames == 0) return
        listener.onAudio(FloatArray(frames * config.channels) { data[it] })
    }

    private fun observeSession() {
        val center = NSNotificationCenter.defaultCenter
        sessionObservers += center.addObserverForName(AVAudioSessionInterruptionNotification, session, events) { note ->
            onInterruption(note)
        }
        sessionObservers += center.addObserverForName(AVAudioSessionMediaServicesWereResetNotification, session, events) { _ ->
            onMediaServicesReset()
        }
    }

    private fun onInterruption(note: NSNotification?) = locked {
        if (stopped) return@locked
        val info = note?.userInfo ?: return@locked
        when ((info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedIntegerValue) {
            AVAudioSessionInterruptionTypeBegan -> {
                val reason = (info[AVAudioSessionInterruptionReasonKey] as? NSNumber)?.unsignedIntegerValue
                if (reason == AVAudioSessionInterruptionReasonAppWasSuspended) {
                    // Delivered on return from suspension about an interruption that already ended:
                    // pick straight back up instead of pausing.
                    if (!userPaused) runCatching { restart() }.onFailure(::reportFailure)
                    return@locked
                }
                interrupted = true
                listener.onInterruptionBegan()
            }
            AVAudioSessionInterruptionTypeEnded -> {
                val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedIntegerValue ?: 0uL
                val shouldResume = options and AVAudioSessionInterruptionOptionShouldResume != 0uL
                if (config.interruption == InterruptionMode.None) {
                    interrupted = false
                    // No session-level pause happened, so the engine picks itself back up, or the
                    // session ends rather than report Recording with no audio.
                    if (userPaused) return@locked
                    if (shouldResume) {
                        runCatching { restart() }.onFailure(::reportFailure)
                    } else {
                        listener.onError(AudioCaptureException("The system did not allow capture to resume after an interruption"))
                    }
                } else {
                    listener.onInterruptionEnded(shouldResume)
                }
            }
        }
    }

    private fun onConfigurationChange() = locked {
        val current = engine ?: return@locked
        if (stopped) return@locked
        current.inputNode.removeTapOnBus(0u)
        try {
            installTap(current)
            if (!userPaused && !interrupted) startEngine(current)
        } catch (e: AudioCaptureException) {
            listener.onError(e)
        }
    }

    private fun onMediaServicesReset() = locked {
        if (stopped) return@locked
        try {
            // Every audio object from before the reset is invalid; rebuild all of them.
            tearDownEngine()
            if (config.ios.manageAudioSession) configureSession()
            buildEngine()
        } catch (e: Exception) {
            reportFailure(e)
        }
    }

    private fun startEngine(target: AVAudioEngine) {
        target.prepare()
        nsCheck("Could not start the audio engine") { target.startAndReturnError(it) }
    }

    private fun reportFailure(error: Throwable) {
        listener.onError(error as? AudioCaptureException ?: AudioCaptureException("Audio engine failed", error))
    }

    private inline fun locked(block: () -> Unit) {
        lock.lock()
        try {
            block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Retries once after a short wait when the media server is resetting (`'msrv'`), which happens
     * around the first permission prompt and after the server crashes.
     */
    private fun retryingMediaServices(what: String, call: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean) {
        try {
            nsCheck(what, call)
        } catch (e: AudioCaptureException) {
            if (e.message?.contains(MEDIA_SERVICES_FAILED.toString()) != true) throw e
            NSThread.sleepForTimeInterval(MEDIA_SERVICES_RETRY_SECONDS)
            nsCheck(what, call)
        }
    }

    private fun nsCheck(what: String, call: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean) {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!call(error.ptr)) {
                val cause = error.value
                throw AudioCaptureException("$what: ${cause?.localizedDescription ?: "unknown error"} (${cause?.code ?: 0})")
            }
        }
    }

    private companion object {
        /** Resampler output can run a few frames past the exact ratio. */
        const val CONVERTER_SLACK_FRAMES = 64u

        /** `AVAudioSessionErrorCodeMediaServicesFailed`, the four-char code `'msrv'`. */
        const val MEDIA_SERVICES_FAILED = 0x6D737276L

        const val MEDIA_SERVICES_RETRY_SECONDS = 0.2
    }
}
