package dev.piotrprus.audiocapture

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What to capture and where it goes.
 *
 * The defaults suit speech: 16 kHz mono PCM16 in 100 ms chunks, the format most speech-to-text
 * APIs expect. Set [file] to also record, or set [stream] to `null` to only record.
 */
public data class CaptureConfig(
    /**
     * Samples per second, per channel. 16 000, 44 100 and 48 000 work on all current phones;
     * Android only guarantees 44 100, and [AudioCapture.start] throws if the device refuses a rate.
     * AAC files are limited to 8 000–48 000.
     */
    val sampleRate: Int = 16_000,
    /**
     * 1 (mono) or 2 (stereo). Whether stereo is two real microphones or one duplicated depends on
     * the device, the input and (on Android) the audio source; [AndroidAudioSource.Camcorder] is
     * the most likely to give real stereo.
     */
    val channels: Int = 1,
    /** How much audio each [AudioChunk] holds and how often [CaptureSession.level] updates. */
    val chunkDuration: Duration = 100.milliseconds,
    /** Raw PCM delivered through [CaptureSession.chunks], or `null` for none. */
    val stream: StreamOutput? = StreamOutput(),
    /** A file written while capturing, or `null` for none. */
    val file: FileOutput? = null,
    /** Preferred microphone, from [AudioCapture.inputDevices]. `null` is the system default. */
    val device: InputDevice? = null,
    /**
     * Echo cancellation, noise suppression and gain control, or `null` for the unprocessed signal.
     * Experimental: see [ExperimentalVoiceProcessing].
     */
    val voiceProcessing: VoiceProcessing? = null,
    val interruption: InterruptionMode = InterruptionMode.Pause,
    val android: AndroidOptions = AndroidOptions(),
    val ios: IosOptions = IosOptions(),
) {
    init {
        require(sampleRate in 8_000..96_000) { "sampleRate must be 8000..96000, was $sampleRate" }
        require(channels == 1 || channels == 2) { "channels must be 1 or 2, was $channels" }
        require(chunkDuration >= 10.milliseconds) { "chunkDuration must be at least 10 ms" }
        require(stream != null || file != null) { "Set stream, file, or both" }
    }

    internal val framesPerChunk: Int
        get() = (sampleRate * chunkDuration.inWholeMicroseconds / 1_000_000).toInt().coerceAtLeast(1)
}

public data class StreamOutput(
    val encoding: PcmEncoding = PcmEncoding.Int16,
    /** Chunks kept while nobody collects. 50 × 100 ms = 5 s. Older chunks are dropped. */
    val bufferedChunks: Int = 50,
) {
    init {
        require(bufferedChunks > 0) { "bufferedChunks must be positive" }
    }
}

public data class FileOutput(
    /** Absolute path. An existing file is overwritten. */
    val path: String,
    val encoder: AudioEncoder = AudioEncoder.AacLc,
    /**
     * Bits per second for compressed encoders; ignored for [AudioEncoder.Wav]. On iOS it is
     * lowered to the highest rate the encoder supports for the sample rate and channel count
     * (about 50 kbit/s for 16 kHz mono).
     */
    val bitRate: Int = 64_000,
)

public enum class AudioEncoder {
    /** AAC-LC in an MPEG-4 container. Use a `.m4a` path. */
    AacLc,

    /** Uncompressed 16-bit PCM in a RIFF container. Use a `.wav` path. */
    Wav,
}

/**
 * What happens when the system takes the microphone away mid-session. See [PauseReason.Interruption]
 * for what counts as one on each platform. Android reports interruptions from API 29.
 */
public enum class InterruptionMode {
    /**
     * Stay [CaptureState.Recording]. Android keeps delivering the silence the system feeds in;
     * iOS restarts capture when the system allows it and fails the session when it does not.
     */
    None,

    /**
     * Move to [CaptureState.Paused] with [PauseReason.Interruption], release the microphone, and
     * wait for [CaptureSession.resume].
     */
    Pause,

    /** Pause, then resume by itself when the system hands the microphone back. */
    PauseResume,
}

public data class AndroidOptions(
    val audioSource: AndroidAudioSource = AndroidAudioSource.VoiceRecognition,
)

/** `MediaRecorder.AudioSource`. Decides which processing the device applies before you get the audio. */
public enum class AndroidAudioSource {
    Default,
    Mic,
    Camcorder,

    /** Tuned for recognition: no processing aimed at human listeners. Best for speech-to-text. */
    VoiceRecognition,

    /** Tuned for calls: echo cancellation and gain control where the device provides them. */
    VoiceCommunication,

    /**
     * As raw as the device allows. Devices that do not support it behave like [Default] without
     * any error; `AudioManager.getProperty(PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)` tells which.
     */
    Unprocessed,

    /**
     * A low-latency path meant for live performance (karaoke, monitoring), not a quality profile.
     * API 29+; falls back to [Mic] below that.
     */
    VoicePerformance,
}

public data class IosOptions(
    /**
     * Let the library configure and activate `AVAudioSession` for each session and deactivate it
     * afterwards. Turn off if your app manages the audio session itself; the category must then
     * allow recording before [AudioCapture.start].
     */
    val manageAudioSession: Boolean = true,
    val category: IosSessionCategory = IosSessionCategory.PlayAndRecord,
    val mode: IosSessionMode = IosSessionMode.Default,
    /**
     * Whether a Bluetooth headset may be the microphone. Off by default: recording through
     * [IosBluetoothInput.Hfp] switches the headset to its call profile (8–16 kHz) for all audio on
     * the device while the session is active.
     */
    val bluetoothInput: IosBluetoothInput = IosBluetoothInput.Off,
    /** Keep other apps' audio playing. [IosSessionCategory.PlayAndRecord] only. */
    val mixWithOthers: Boolean = false,
    /** Route output to the speaker rather than the receiver. [IosSessionCategory.PlayAndRecord] only. */
    val defaultToSpeaker: Boolean = true,
    /**
     * Deactivate the audio session on [CaptureSession.pause] so other apps' audio can resume.
     * Off by default: deactivating also stops your own app's playback, and reactivating makes
     * [CaptureSession.resume] take about half a second. The microphone is released either way.
     */
    val deactivateOnPause: Boolean = false,
)

public enum class IosBluetoothInput {
    /** Record from the phone (or a wired headset) even when a Bluetooth headset is connected. */
    Off,

    /** Allow the Bluetooth hands-free profile: works with every headset, at call quality (8–16 kHz). */
    Hfp,

    /**
     * Prefer high-quality Bluetooth recording on AirPods that support it (iOS 26+), falling back to
     * [Hfp] elsewhere.
     */
    HighQuality,
}

public enum class IosSessionCategory {
    /** Record only; silences other audio. */
    Record,

    /** Record and play at the same time. Needed for voice processing and [IosOptions.mixWithOthers]. */
    PlayAndRecord,
}

public enum class IosSessionMode {
    Default,

    /** Minimal system processing, with input gain control turned off. Good for analysis. */
    Measurement,

    /**
     * For voice-over-IP apps. Uses Apple's voice processing and limits routes to voice-capable
     * ones; the voice sounds like a call.
     */
    VoiceChat,
}
