package com.sonex.core

import kotlinx.serialization.Serializable

/**
 * Shared contract between the phone (brain) and the Android TV (output).
 * Both apps depend on this module so the wire format can never drift.
 */

/**
 * Listening / decision state the phone broadcasts.
 * WHISPER = one person being quiet: hold everything.
 * WHISPER_GROUP = several people whispering (louder, an actual hushed
 * conversation): still "whispering" to the user, but nudge the volume down a
 * touch so it doesn't sit on top of them.
 */
enum class RoomState { QUIET, TALKING, BOOST, WHISPER, WHISPER_GROUP }

/** What the TV should do when it receives a command. */
enum class Action { DUCK, MUTE, PAUSE, RESUME, BOOST, RESTORE }

/** A command sent phone -> TV over the LAN socket. */
@Serializable
data class Command(
    val action: Action,
    /** Target volume 0..100 for DUCK/BOOST. Ignored for others. */
    val level: Int = -1,
    val reason: String = ""
)

/** State the TV reports back so the phone can restore accurately. */
@Serializable
data class TvState(
    val deviceName: String,
    val currentVolume: Int,
    val isPlaying: Boolean
)

/** Pairing handshake: TV shows a 4-digit code, phone submits it. */
@Serializable
data class PairRequest(val code: String, val phoneName: String)

@Serializable
data class PairResponse(val ok: Boolean, val tvName: String, val error: String = "")

object Net {
    /** mDNS service type used for discovery on the local network. */
    const val SERVICE_TYPE = "_sonex._tcp."
    const val SERVICE_NAME = "SoNexTV"
    const val DEFAULT_PORT = 8787
}
