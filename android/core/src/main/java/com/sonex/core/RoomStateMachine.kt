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
            whisperShaped: Boolean = speechShaped
        ): FrameKind {
            val effectiveTrigger = if (inSpeechState) trigger - hysteresisDb else trigger
            // A cooler/fan/motor can mimic speech's zero-crossing band, but it
            // holds a near-constant level. Steady => interference noise:
            // BOOST over it, never duck for it.
            val steady = dbSwingDb < ModulationTracker.STEADY_SWING_DB
            val talksLikeAPerson = speechShaped && !steady
            // A clearly UNVOICED sound (breathy, high zero-crossing, not steady
            // machinery) is a whisper even when it's loud — this is what stops
            // "I whispered but it showed Talking". It never becomes SPEECH.
            val unvoicedBreath = whisperShaped && !speechShaped && !steady
            return when {
                db > effectiveTrigger && talksLikeAPerson -> FrameKind.SPEECH
                // Machine noise only — a breathy whisper must NOT be boosted as noise.
                db > boostTrigger && ((!speechShaped && !whisperShaped) || steady) -> FrameKind.NOISE
                // Whisper band: breathy-shaped + slightly modulated (a flat hum
                // isn't a person). Soft whispers sit below the trigger; a loud
                // *unvoiced* whisper is allowed above it too. The louder it is,
                // the more it's several people => group whisper (gentle duck).
                whisperShaped && dbSwingDb >= ModulationTracker.MIN_WHISPER_SWING_DB &&
                    noiseFloorDb != null && db > noiseFloorDb + WHISPER_MARGIN_DB &&
                    (db <= effectiveTrigger || unvoicedBreath) ->
                    if (db >= noiseFloorDb + WHISPER_MARGIN_DB + WHISPER_GROUP_GAP_DB)
                        FrameKind.WHISPER_GROUP else FrameKind.WHISPER
                else -> FrameKind.QUIET
            }
        }
    }
}
