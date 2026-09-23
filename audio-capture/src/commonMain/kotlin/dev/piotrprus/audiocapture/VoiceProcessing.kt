package dev.piotrprus.audiocapture

/**
 * Marks the [VoiceProcessing] constructor: its result differs a lot between platforms and may change.
 *
 * On iOS all three effects run through Apple's voice-processing unit, which is tuned for calls:
 * it changes the tone of the voice noticeably, which is fine for a call and unwelcome in a
 * recording. On Android the per-device effects are usually subtler.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Voice processing sounds very different per platform; on iOS it noticeably changes the tone of the voice.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CONSTRUCTOR)
public annotation class ExperimentalVoiceProcessing

/**
 * Platform echo cancellation, noise suppression and gain control, applied before you get the audio.
 *
 * - **Android:** `AcousticEchoCanceler`, `NoiseSuppressor` and `AutomaticGainControl`, each only
 *   where the device provides it.
 * - **iOS:** any of the three switches on voice processing on the input node, which always does
 *   echo cancellation and noise suppression together; [autoGain] only toggles its gain control.
 *   The result sounds like a phone call. Needs [IosSessionCategory.PlayAndRecord].
 *
 * For a lighter touch on Android without this class, try [AndroidAudioSource.VoiceCommunication].
 */
public data class VoiceProcessing @ExperimentalVoiceProcessing constructor(
    val echoCancel: Boolean = true,
    val noiseSuppress: Boolean = true,
    val autoGain: Boolean = false,
    /**
     * Apply on iOS at all. Turn off to keep the Android effects while leaving iOS audio
     * untouched, since Apple's processing changes the voice so much.
     */
    val applyOnIos: Boolean = true,
    /** How much iOS lowers other apps' audio while voice processing runs (iOS 17+). */
    val iosDucking: IosDucking = IosDucking.Default,
)

/** `AVAudioVoiceProcessingOtherAudioDuckingLevel`. */
public enum class IosDucking {
    /** Apple's default, which lowers other audio a lot. */
    Default,
    Min,
    Mid,
    Max,
}
