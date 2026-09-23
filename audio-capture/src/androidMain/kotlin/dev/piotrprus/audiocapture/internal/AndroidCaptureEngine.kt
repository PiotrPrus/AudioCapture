package dev.piotrprus.audiocapture.internal

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import androidx.annotation.RequiresApi
import dev.piotrprus.audiocapture.AndroidAudioSource
import dev.piotrprus.audiocapture.AudioCaptureException
import dev.piotrprus.audiocapture.CaptureConfig
import dev.piotrprus.audiocapture.InterruptionMode

/**
 * [CaptureEngine] over [AudioRecord], reading on a dedicated thread.
 *
 * Asks for float samples and falls back to 16-bit on devices that refuse them. Pausing stops the
 * recorder and its thread so the microphone indicator goes off; resuming starts both again.
 *
 * Interruptions (API 29+) come from the recording callback's `isClientSilenced`: while another app
 * or a call holds the microphone the system feeds this recorder silence. The recorder keeps
 * running so the callback can report when the microphone comes back; the session drops the
 * silence meanwhile.
 */
internal class AndroidCaptureEngine(
    context: Context,
    private val config: CaptureConfig,
) : CaptureEngine {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val lock = Any()

    private var record: AudioRecord? = null
    private var floatSamples = true
    private var thread: Thread? = null
    private val effects = mutableListOf<AudioEffect>()
    private var recordingCallback: Any? = null
    private lateinit var listener: CaptureEngine.Listener

    @Volatile
    private var reading = false

    @Volatile
    private var silenced = false

    override fun start(listener: CaptureEngine.Listener) {
        this.listener = listener
        val created = createRecord()
        record = created
        attachEffects(created.audioSessionId)
        config.device?.let { wanted ->
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.id.toString() == wanted.id }
                ?.let(created::setPreferredDevice)
        }
        if (Build.VERSION.SDK_INT >= 29 && config.interruption != InterruptionMode.None) {
            registerInterruptions(created)
        }
        startReading(created)
    }

    override fun pause() {
        synchronized(lock) { stopReading() }
    }

    override fun resume() {
        synchronized(lock) {
            val current = record ?: return
            if (thread == null) {
                silenced = false
                startReading(current)
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            stopReading()
            if (Build.VERSION.SDK_INT >= 29) unregisterInterruptions()
            effects.forEach { runCatching { it.release() } }
            effects.clear()
            record?.release()
            record = null
        }
    }

    private fun startReading(target: AudioRecord) {
        target.startRecording()
        if (target.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw AudioCaptureException("The microphone is in use by another app")
        }
        reading = true
        thread = Thread({ readLoop(target) }, "AudioCapture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private fun stopReading() {
        val running = thread ?: return
        reading = false
        // Unblocks the read in progress.
        runCatching { record?.stop() }
        running.join(JOIN_TIMEOUT_MS)
        thread = null
    }

    private fun readLoop(target: AudioRecord) {
        val size = config.framesPerChunk * config.channels
        val floats = FloatArray(size)
        val shorts = if (floatSamples) null else ShortArray(size)
        while (reading) {
            val read = if (shorts == null) {
                target.read(floats, 0, size, AudioRecord.READ_BLOCKING)
            } else {
                target.read(shorts, 0, size)
            }
            when {
                read > 0 -> {
                    val whole = read - read % config.channels
                    val out = if (shorts == null) {
                        floats.copyOf(whole)
                    } else {
                        FloatArray(whole) { shorts[it] / 32768f }
                    }
                    if (whole > 0) listener.onAudio(out)
                }
                // Negative values are error codes, not short reads. After stop() they are expected.
                read < 0 -> {
                    if (reading) listener.onError(AudioCaptureException("AudioRecord read failed with code $read"))
                    return
                }
            }
        }
    }

    @SuppressLint("MissingPermission") // AndroidAudioCapture checks before creating the engine.
    private fun createRecord(): AudioRecord {
        val channelMask = if (config.channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        for (encoding in listOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
            val minBuffer = AudioRecord.getMinBufferSize(config.sampleRate, channelMask, encoding)
            if (minBuffer <= 0) continue
            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            // Four chunks of headroom keep the recorder from overrunning while a chunk is handed off.
            val bufferSize = maxOf(minBuffer, config.framesPerChunk * config.channels * bytesPerSample * 4)
            val created = runCatching {
                AudioRecord.Builder()
                    .setAudioSource(audioSource())
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(encoding)
                            .setSampleRate(config.sampleRate)
                            .setChannelMask(channelMask)
                            .build(),
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            }.getOrNull() ?: continue
            if (created.state == AudioRecord.STATE_INITIALIZED) {
                floatSamples = encoding == AudioFormat.ENCODING_PCM_FLOAT
                return created
            }
            created.release()
        }
        throw AudioCaptureException(
            "This device cannot record ${config.sampleRate} Hz with ${config.channels} channel(s) " +
                "from ${config.android.audioSource}",
        )
    }

    private fun audioSource(): Int = when (config.android.audioSource) {
        AndroidAudioSource.Default -> MediaRecorder.AudioSource.DEFAULT
        AndroidAudioSource.Mic -> MediaRecorder.AudioSource.MIC
        AndroidAudioSource.Camcorder -> MediaRecorder.AudioSource.CAMCORDER
        AndroidAudioSource.VoiceRecognition -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        AndroidAudioSource.VoiceCommunication -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        AndroidAudioSource.Unprocessed -> MediaRecorder.AudioSource.UNPROCESSED
        AndroidAudioSource.VoicePerformance ->
            if (Build.VERSION.SDK_INT >= 29) MediaRecorder.AudioSource.VOICE_PERFORMANCE else MediaRecorder.AudioSource.MIC
    }

    private fun attachEffects(sessionId: Int) {
        val wanted = config.voiceProcessing ?: return
        if (wanted.echoCancel && AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(sessionId)?.let { effects += it.apply { enabled = true } }
        }
        if (wanted.noiseSuppress && NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(sessionId)?.let { effects += it.apply { enabled = true } }
        }
        if (wanted.autoGain && AutomaticGainControl.isAvailable()) {
            AutomaticGainControl.create(sessionId)?.let { effects += it.apply { enabled = true } }
        }
    }

    @RequiresApi(29)
    private fun registerInterruptions(target: AudioRecord) {
        val callback = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                val mine = configs.firstOrNull { it.clientAudioSessionId == target.audioSessionId } ?: return
                val nowSilenced = mine.isClientSilenced
                if (nowSilenced == silenced) return
                silenced = nowSilenced
                if (nowSilenced) listener.onInterruptionBegan() else listener.onInterruptionEnded(shouldResume = true)
            }
        }
        target.registerAudioRecordingCallback(appContext.mainExecutor, callback)
        recordingCallback = callback
    }

    @RequiresApi(29)
    private fun unregisterInterruptions() {
        val callback = recordingCallback as? AudioManager.AudioRecordingCallback ?: return
        record?.unregisterAudioRecordingCallback(callback)
        recordingCallback = null
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 1_000L
    }
}
