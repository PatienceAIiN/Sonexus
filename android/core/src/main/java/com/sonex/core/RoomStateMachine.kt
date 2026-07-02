package com.sonex.core

/** What a single mic frame looks like after classification. */
enum class FrameKind { SPEECH, NOISE, QUIET }

/**
 * The anti-flicker state machine behind the detect -> duck -> restore loop.
 * Pure and deterministic: feed it one classified frame at a time, it tells you
 * when the room state genuinely changed. Streak counters mean a single loud
 * frame (a cough, a clink) never toggles the volume.
 */
class RoomStateMachine(
    /** Frames of sustained speech/noise before switching (~0.5s at 30ms). */
    private val talkOnFrames: Int = 17,
    /** Frames of sustained quiet before restoring. */
    private val quietOffFrames: Int
) {
    var state: RoomState = RoomState.QUIET
        private set

    // Leaky integrators, not consecutive-run counters. Real speech has gaps
    // between syllables every few frames; a hard reset on one quiet frame
    // means 0.5s of *uninterrupted* voicing never happens and the volume
    // never ducks. Matching frames score +2, others drain -1, so bursty
    // speech accumulates while isolated blips decay away.
    private var talkScore = 0
    private var quietScore = 0
    private var boostScore = 0

    private val talkOn = talkOnFrames * 2   // +2 per hit => same time-to-trigger
    private val quietOff = quietOffFrames * 2

    /**
     * Advance one frame. Returns the new state if it changed, else null.
     */
    fun step(kind: FrameKind): RoomState? {
        fun leak(score: Int, hit: Boolean, cap: Int) =
            if (hit) minOf(cap, score + 2) else maxOf(0, score - 1)

        talkScore = leak(talkScore, kind == FrameKind.SPEECH, talkOn)
        boostScore = leak(boostScore, kind == FrameKind.NOISE, talkOn)
        quietScore = leak(quietScore, kind == FrameKind.QUIET, quietOff)

        val next = when {
            talkScore >= talkOn -> RoomState.TALKING
            boostScore >= talkOn -> RoomState.BOOST
            quietScore >= quietOff -> RoomState.QUIET
            else -> state
        }
        if (next == state) return null
        // Entering a state drains the counters that argue for the old one,
        // so the next transition starts from a clean slate.
        when (next) {
            RoomState.TALKING -> { quietScore = 0; boostScore = 0 }
            RoomState.BOOST -> { quietScore = 0; talkScore = 0 }
            RoomState.QUIET -> { talkScore = 0; boostScore = 0 }
        }
        state = next
        return next
    }

    companion object {
        /**
         * Classify one frame from its loudness and shape against the
         * calibrated thresholds. SEAM for ML models later.
         *
         * @param inSpeechState pass true while already tracking speech: the
         *   trigger drops by [hysteresisDb] so a speaker trailing off doesn't
         *   flicker the state (enter high, exit low — classic hysteresis).
         */
        fun classify(
            db: Double,
            speechShaped: Boolean,
            trigger: Double,
            boostTrigger: Double,
            inSpeechState: Boolean = false,
            hysteresisDb: Double = 3.0
        ): FrameKind {
            val effectiveTrigger = if (inSpeechState) trigger - hysteresisDb else trigger
            return when {
                db > effectiveTrigger && speechShaped -> FrameKind.SPEECH
                db > boostTrigger && !speechShaped -> FrameKind.NOISE
                else -> FrameKind.QUIET
            }
        }
    }
}
