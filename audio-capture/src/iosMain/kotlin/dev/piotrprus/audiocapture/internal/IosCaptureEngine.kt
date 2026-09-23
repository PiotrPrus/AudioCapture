package dev.piotrprus.audiocapture.internal

import dev.piotrprus.audiocapture.AudioCaptureException
import dev.piotrprus.audiocapture.CaptureConfig
import dev.piotrprus.audiocapture.InterruptionMode
import dev.piotrprus.audiocapture.IosSessionCategory
import dev.piotrprus.audiocapture.IosSessionMode
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
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
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetooth
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryOptionMixWithOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionCategoryRecord
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVAudioSessionModeMeasurement
import platform.AVFAudio.AVAudioSessionModeSpokenAudio
import platform.AVFAudio.AVAudioSessionModeVoiceChat
import platform.AVFAudio.AVAudioSessionPortDescription
import platform.AVFAudio.AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation
import platform.AVFAudio.availableInputs
import platform.AVFAudio.setActive
import platform.Foundation.NSError
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSRecursiveLock
import platform.darwin.NSObjectProtocol

/**
 * [CaptureEngine] over an [AVAudioEngine] input tap.
 *
 * The tap delivers whatever the hardware runs at (often 48 kHz, sometimes stereo). An
 * [AVAudioConverter] resamples that to the configured rate and channel count, with proper
 * filtering: asking the audio session for a preferred rate is only a hint iOS may ignore.
 *
 * Pausing stops the engine and deactivates the session, so the microphone indicator goes off and
 * other apps' audio can resume. A route change (headset plugged in or out) stops the engine and
 * may change the input format; the tap is rebuilt for the new format and capture carries on.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosCaptureEngine(private val config: CaptureConfig) : CaptureEngine {

    private val session = AVAudioSession.sharedInstance()
    private val lock = NSRecursiveLock()
    private val observers = mutableListOf<NSObjectProtocol>()
    private val targetFormat = AVAudioFormat(
        commonFormat = AVAudioPCMFormatFloat32,
        sampleRate = config.sampleRate.toDouble(),
        channels = config.channels.toUInt(),
        interleaved = true,
    )

    private var engine: AVAudioEngine? = null
    private var converter: AVAudioConverter? = null
    private var userPaused = false
    private lateinit var listener: CaptureEngine.Listener

    override fun start(listener: CaptureEngine.Listener) = locked {
        this.listener = listener
        if (config.ios.manageAudioSession) configureSession()
        val created = AVAudioEngine()
        engine = created
        config.voiceProcessing?.takeIf { it.echoCancel || it.noiseSuppress || it.autoGain }?.let { wanted ->
            nsCheck("Could not enable voice processing") { created.inputNode.setVoiceProcessingEnabled(true, it) }
            created.inputNode.setVoiceProcessingAGCEnabled(wanted.autoGain)
        }
        installTap(created)
        observe(created)
        startEngine(created)
    }

    override fun pause() = locked {
        val current = engine ?: return@locked
        userPaused = true
        current.stop()
        if (config.ios.manageAudioSession) deactivateSession()
    }

    override fun resume() = locked {
        val current = engine ?: return@locked
        userPaused = false
        if (current.running) return@locked
        if (config.ios.manageAudioSession) {
            nsCheck("Could not activate the audio session") { session.setActive(true, it) }
        }
        converter?.reset()
        startEngine(current)
    }

    override fun stop() = locked {
        val current = engine ?: return@locked
        engine = null
        observers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        observers.clear()
        current.inputNode.removeTapOnBus(0u)
        current.stop()
        converter = null
        if (config.ios.manageAudioSession) deactivateSession()
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
            IosSessionMode.SpokenAudio -> AVAudioSessionModeSpokenAudio
        }
        var options = 0uL
        if (config.ios.allowBluetooth) options = options or AVAudioSessionCategoryOptionAllowBluetooth
        if (config.ios.category == IosSessionCategory.PlayAndRecord) {
            if (config.ios.mixWithOthers) options = options or AVAudioSessionCategoryOptionMixWithOthers
            if (config.ios.defaultToSpeaker) options = options or AVAudioSessionCategoryOptionDefaultToSpeaker
        }
        nsCheck("Could not set the audio session category") { session.setCategory(category, mode, options, it) }
        config.device?.let { wanted ->
            val port = session.availableInputs.orEmpty()
                .filterIsInstance<AVAudioSessionPortDescription>()
                .firstOrNull { it.UID == wanted.id }
            if (port != null) nsCheck("Could not select ${wanted.name}") { session.setPreferredInput(port, it) }
        }
        nsCheck("Could not activate the audio session") { session.setActive(true, it) }
    }

    private fun deactivateSession() {
        session.setActive(false, AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation, null)
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
        input.installTapOnBus(0u, tapFrames, inputFormat) { buffer, _ ->
            if (buffer != null) convert(buffer, created, ratio)
        }
    }

    /** Runs on the audio thread. */
    private fun convert(buffer: AVAudioPCMBuffer, converter: AVAudioConverter, ratio: Double) {
        val capacity = (buffer.frameLength.toDouble() * ratio).toUInt() + CONVERTER_SLACK_FRAMES
        val out = AVAudioPCMBuffer(pCMFormat = targetFormat, frameCapacity = capacity)
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

    private fun observe(target: AVAudioEngine) {
        val center = NSNotificationCenter.defaultCenter
        observers += center.addObserverForName(AVAudioSessionInterruptionNotification, session, null) { note ->
            onInterruption(note)
        }
        observers += center.addObserverForName(AVAudioEngineConfigurationChangeNotification, target, null) { _ ->
            onConfigurationChange()
        }
    }

    private fun onInterruption(note: NSNotification?) {
        val info = note?.userInfo ?: return
        when ((info[AVAudioSessionInterruptionTypeKey] as? NSNumber)?.unsignedIntegerValue) {
            AVAudioSessionInterruptionTypeBegan -> listener.onInterruptionBegan()
            AVAudioSessionInterruptionTypeEnded -> {
                val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedIntegerValue ?: 0uL
                val shouldResume = options and AVAudioSessionInterruptionOptionShouldResume != 0uL
                if (config.interruption == InterruptionMode.None) {
                    // No session-level pause happened, so the engine picks itself back up.
                    if (shouldResume) runCatching { resume() }.onFailure { reportFailure(it) }
                } else {
                    listener.onInterruptionEnded(shouldResume)
                }
            }
        }
    }

    private fun onConfigurationChange() = locked {
        val current = engine ?: return@locked
        current.inputNode.removeTapOnBus(0u)
        try {
            installTap(current)
            if (!userPaused) startEngine(current)
        } catch (e: AudioCaptureException) {
            listener.onError(e)
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

    private inline fun nsCheck(what: String, call: (CPointer<ObjCObjectVar<NSError?>>) -> Boolean) {
        memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            if (!call(error.ptr)) {
                throw AudioCaptureException("$what: ${error.value?.localizedDescription ?: "unknown error"}")
            }
        }
    }

    private companion object {
        /** Resampler output can run a few frames past the exact ratio. */
        const val CONVERTER_SLACK_FRAMES = 64u
    }
}
