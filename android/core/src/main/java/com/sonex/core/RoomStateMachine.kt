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

    private var talkStreak = 0
    private var quietStreak = 0
    private var boostStreak = 0

    /**
     * Advance one frame. Returns the new state if it changed, else null.
     */
    fun step(kind: FrameKind): RoomState? {
        when (kind) {
            FrameKind.SPEECH -> { talkStreak++; quietStreak = 0; boostStreak = 0 }
            FrameKind.NOISE  -> { boostStreak++; quietStreak = 0; talkStreak = 0 }
            FrameKind.QUIET  -> { quietStreak++; talkStreak = 0; boostStreak = 0 }
        }
        val next = when {
            talkStreak >= talkOnFrames -> RoomState.TALKING
            boostStreak >= talkOnFrames -> RoomState.BOOST
            quietStreak >= quietOffFrames -> RoomState.QUIET
            else -> state
        }
        if (next == state) return null
        state = next
        return next
    }

    companion object {
        /**
         * Classify one frame from its loudness and shape against the
         * calibrated thresholds. SEAM for ML models later.
         */
        fun classify(db: Double, speechShaped: Boolean, trigger: Double, boostTrigger: Double): FrameKind =
            when {
                db > trigger && speechShaped -> FrameKind.SPEECH
                db > boostTrigger && !speechShaped -> FrameKind.NOISE
                else -> FrameKind.QUIET
            }
    }
}
