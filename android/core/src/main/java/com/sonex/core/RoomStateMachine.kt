package com.sonex.core

/** What a single mic frame looks like after classification. */
enum class FrameKind { SPEECH, NOISE, QUIET, WHISPER, WHISPER_GROUP }

/**
 * The anti-flicker state machine behind the detect -> duck -> restore loop.
 * Pure and deterministic: feed it one classified frame at a time, it tells you
 * when the room state genuinely changed. Streak counters mean a single loud
 * frame (a cough, a clink) never toggles the volume.
 */
class RoomStateMachine(
    /** Frames of sustained speech/noise before switching (~0.3s at 30ms) — snappy. */
    private val talkOnFrames: Int = 10,
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
    private var whisperScore = 0
    private var whisperGroupScore = 0

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
        // People-first: a talking frame drains the boost score harder than a plain
        // miss, so a conversation cuts THROUGH a running cooler/fan (which would
        // otherwise keep boosting) and ducks instead. Speech always wins.
        if (kind == FrameKind.SPEECH) boostScore = maxOf(0, boostScore - 2)
        // Any whisper frame counts as "someone is whispering"; only the louder
        // group-whisper frames also build the group score. When the group score
        // drains away the state falls back to a plain (hold-everything) whisper.
        val anyWhisper = kind == FrameKind.WHISPER || kind == FrameKind.WHISPER_GROUP
        whisperScore = leak(whisperScore, anyWhisper, talkOn)
        whisperGroupScore = leak(whisperGroupScore, kind == FrameKind.WHISPER_GROUP, talkOn)
        // Whispers also count toward quiet for restore purposes? No — whisper
        // means "hold / barely touch", so it feeds neither quiet nor talk.
        quietScore = leak(quietScore, kind == FrameKind.QUIET, quietOff)

        val next = when {
            talkScore >= talkOn -> RoomState.TALKING
            boostScore >= talkOn -> RoomState.BOOST
            whisperGroupScore >= talkOn -> RoomState.WHISPER_GROUP
            whisperScore >= talkOn -> RoomState.WHISPER
            quietScore >= quietOff -> RoomState.QUIET
            else -> state
        }
        if (next == state) return null
        // Entering a state drains the counters that argue for the old one,
        // so the next transition starts from a clean slate.
        when (next) {
            RoomState.TALKING -> { quietScore = 0; boostScore = 0; whisperScore = 0; whisperGroupScore = 0 }
            RoomState.BOOST -> { quietScore = 0; talkScore = 0; whisperScore = 0; whisperGroupScore = 0 }
            RoomState.QUIET -> { talkScore = 0; boostScore = 0; whisperScore = 0; whisperGroupScore = 0 }
            RoomState.WHISPER -> { quietScore = 0; talkScore = 0; boostScore = 0 }
            RoomState.WHISPER_GROUP -> { quietScore = 0; talkScore = 0; boostScore = 0 }
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
        const val WHISPER_MARGIN_DB = 6.0
        /** A whisper this far above the floor is several people, not one —
         *  louder than a lone breath but still below normal speech. */
        const val WHISPER_GROUP_GAP_DB = 6.0
        /** Floor-relative gates: talking is ~this far above the room's ambient
         *  floor, machine/boost noise even further. Makes detection self-adapt to
         *  RAW-mic levels (which are quieter than AGC), so it fires no matter the
         *  absolute loudness — never "stuck on Listening" next to a loud cooler. */
        const val TALK_OVER_FLOOR_DB = 10.0   // mild gossip is ~10 dB over ambient, not 14
        const val BOOST_OVER_FLOOR_DB = 22.0
        /** A whisper/murmur fluctuates in ZCR (breath vs. consonants); a steady
         *  cooler/fan does not. Below this ZCR-flux it's a machine, not a person. */
        const val WHISPER_MIN_FLUX = 0.015

        fun classify(
            db: Double,
            speechShaped: Boolean,
            trigger: Double,
            boostTrigger: Double,
            inSpeechState: Boolean = false,
            hysteresisDb: Double = 3.0,
            noiseFloorDb: Double? = null,
            /** p90-p10 level swing over ~1s: speech pulses, machinery doesn't. */
            dbSwingDb: Double = 99.0,
            /** Whispers are unvoiced (higher ZCR); defaults to speechShaped. */
            whisperShaped: Boolean = speechShaped,
            /** p90-p10 fluctuation of ZCR over ~1s: a talker's varies, a machine's
             *  doesn't — level-independent, so it survives loud-machine masking. */
            zcrFluxRatio: Double = 0.0
        ): FrameKind {
            val effectiveTrigger = if (inSpeechState) trigger - hysteresisDb else trigger
            // Cap the gates relative to the live floor so quiet RAW-mic audio still
            // crosses them (min => never LESS sensitive than the calibration).
            val talkGate = if (noiseFloorDb != null)
                minOf(effectiveTrigger, noiseFloorDb + TALK_OVER_FLOOR_DB) else effectiveTrigger
            val boostGate = if (noiseFloorDb != null)
                minOf(boostTrigger, noiseFloorDb + BOOST_OVER_FLOOR_DB) else boostTrigger
            // A cooler/fan/motor can mimic speech's zero-crossing band, but it
            // holds a near-constant level. Steady => interference noise:
            // BOOST over it, never duck for it.
            val steady = dbSwingDb < ModulationTracker.STEADY_SWING_DB
            // A person is voice-shaped AND changing — either in level (normal) OR
            // in ZCR (still detectable when a loud machine flattens the level).
            val changing = !steady || zcrFluxRatio >= ZcrTracker.SPEECH_FLUX
            val talksLikeAPerson = speechShaped && changing
            // A clearly UNVOICED sound (breathy, high zero-crossing, not steady
            // machinery) is a whisper even when it's loud — this is what stops
            // Only three outcomes now (Whisper removed for stability):
            //  SPEECH = a person talking (voice-shaped + changing, above talk gate)
            //  NOISE  = loud non-speech / machinery / dog bark (above boost gate)
            //  QUIET  = everything else.
            return when {
                db > talkGate && talksLikeAPerson -> FrameKind.SPEECH
                db > boostGate && !talksLikeAPerson -> FrameKind.NOISE
                else -> FrameKind.QUIET
            }
        }
    }
}
