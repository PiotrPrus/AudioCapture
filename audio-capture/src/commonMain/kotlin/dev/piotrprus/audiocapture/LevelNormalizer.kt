package dev.piotrprus.audiocapture

import kotlin.math.exp
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Turns [AudioLevel.peak] into a 0..1 value for visuals that looks the same on every device.
 *
 * Dividing by full scale leaves a meter nearly flat, because ordinary speech peaks far below it.
 * Dividing by a fixed reference measured on one phone pins the meter at 1.0 on another whose input
 * runs hotter (input gain varies with the platform, the audio source or mode, and whether gain
 * control is on; the iOS record path often runs hotter than Android's). So the reference adapts: it
 * rises within [attack] when the microphone turns out louder than assumed, and falls back over
 * [release] so a pause does not make the next quiet word read as a shout.
 *
 * The reference never drops below [floor]. Without that floor this would be an automatic gain
 * that turns room noise into full-scale readings.
 *
 * Not thread-safe; use one instance per session.
 */
public class LevelNormalizer(
    private val floor: Float = DEFAULT_FLOOR,
    private val attack: Duration = 400.milliseconds,
    private val release: Duration = 20.seconds,
) {
    init {
        require(floor > 0f && floor <= 1f) { "floor must be in (0, 1], was $floor" }
    }

    private var reference = floor

    /** Forget what the previous session learned. */
    public fun reset() {
        reference = floor
    }

    /**
     * @param elapsed audio the level covers, normally [AudioChunk.duration] or
     *   [CaptureConfig.chunkDuration]. Keeps the response independent of chunk size.
     * @return 0..1
     */
    public fun normalize(level: AudioLevel, elapsed: Duration): Float {
        val peak = level.peak
        val seconds = elapsed.inWholeMicroseconds / 1_000_000f
        reference = if (peak > reference) {
            reference + (peak - reference) * (1f - exp(-seconds / attack.inSeconds()))
        } else {
            floor + (reference - floor) * exp(-seconds / release.inSeconds())
        }.coerceAtLeast(floor)
        return (peak / reference).coerceIn(0f, 1f)
    }

    private fun Duration.inSeconds(): Float = inWholeMicroseconds / 1_000_000f

    public companion object {
        /**
         * Peak of conversational speech measured on an Android phone with
         * [AndroidAudioSource.VoiceRecognition] (no gain control), relative to full scale. A
         * starting point, not a constant of nature: pass your own floor for other setups.
         */
        public const val DEFAULT_FLOOR: Float = 0.43f
    }
}
