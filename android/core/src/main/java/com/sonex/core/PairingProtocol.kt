package com.sonex.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.random.Random

/**
 * Line-oriented wire framing between phone and TV. One message per line:
 *
 *   phone -> TV:  "PAIR {PairRequest json}"  |  "CMD {Command json}"
 *   TV -> phone:  "PAIRED {PairResponse json}"  |  "STATE {TvState json}"
 *
 * Both sides call this object so the framing can never drift.
 */
object PairingProtocol {
    const val PAIR_PREFIX = "PAIR "
    const val CMD_PREFIX = "CMD "
    const val PAIRED_PREFIX = "PAIRED "
    const val STATE_PREFIX = "STATE "

    private val json = Json { ignoreUnknownKeys = true }

    /** A parsed inbound line on the TV side. */
    sealed class Message {
        data class Pair(val request: PairRequest) : Message()
        data class Cmd(val command: Command) : Message()
    }

    /** 4-digit pairing code the TV displays. */
    fun newCode(rng: Random = Random.Default): String = rng.nextInt(1000, 10000).toString()

    fun encodePair(req: PairRequest): String = PAIR_PREFIX + json.encodeToString(req)
    fun encodeCommand(cmd: Command): String = CMD_PREFIX + json.encodeToString(cmd)
    fun encodePaired(res: PairResponse): String = PAIRED_PREFIX + json.encodeToString(res)
    fun encodeState(state: TvState): String = STATE_PREFIX + json.encodeToString(state)

    /** Parse a line arriving at the TV. Null for garbage — never throws. */
    fun parseLine(line: String): Message? = runCatching {
        when {
            line.startsWith(PAIR_PREFIX) ->
                Message.Pair(json.decodeFromString<PairRequest>(line.removePrefix(PAIR_PREFIX).trim()))
            line.startsWith(CMD_PREFIX) ->
                Message.Cmd(json.decodeFromString<Command>(line.removePrefix(CMD_PREFIX).trim()))
            else -> null
        }
    }.getOrNull()

    /** Parse the TV's "PAIRED ..." reply on the phone. Null for garbage. */
    fun parsePaired(line: String): PairResponse? = runCatching {
        json.decodeFromString<PairResponse>(line.removePrefix(PAIRED_PREFIX).trim())
    }.getOrNull()

    /** Parse the TV's "STATE ..." report on the phone. Null for garbage. */
    fun parseState(line: String): TvState? = runCatching {
        json.decodeFromString<TvState>(line.removePrefix(STATE_PREFIX).trim())
    }.getOrNull()

    /** TV-side pairing decision: the submitted code must match exactly. */
    fun pairResponseFor(req: PairRequest, expectedCode: String, tvName: String): PairResponse =
        if (req.code == expectedCode) PairResponse(true, tvName)
        else PairResponse(false, tvName, "Wrong code")
}
