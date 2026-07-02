package com.sonex.core

/**
 * Maps model outputs to a FrameKind. This is the decision half of the ML seam:
 * the models (Silero VAD, YAMNet) produce numbers, this pure function turns
 * them into DUCK/BOOST/QUIET decisions against the calibrated thresholds.
 *
 * The dB gates stay in place even with perfect models: a whisper across the
 * room IS speech, but it doesn't compete with the TV, so we don't duck for it.
 */
object MlDecision {
    const val DEFAULT_SPEECH_PROB = 0.5

    /**
     * @param speechProb   Silero VAD speech probability 0..1, null if VAD unavailable.
     * @param soundIsSpeech YAMNet verdict on the window: true = dominated by
     *                      speech-like classes, false = non-speech, null = unavailable.
     */
    fun decide(
        speechProb: Double?,
        soundIsSpeech: Boolean?,
        db: Double,
        trigger: Double,
        boostTrigger: Double,
        speechThreshold: Double = DEFAULT_SPEECH_PROB
    ): FrameKind {
        val speaking = when {
            speechProb != null -> speechProb >= speechThreshold
            soundIsSpeech != null -> soundIsSpeech
            else -> return FrameKind.QUIET // no model signal at all — caller should use the heuristic
        }
        return when {
            speaking && db > trigger -> FrameKind.SPEECH
            // Loud and confidently non-speech => ambient noise worth boosting over.
            !speaking && db > boostTrigger && soundIsSpeech == false -> FrameKind.NOISE
            else -> FrameKind.QUIET
        }
    }
}
