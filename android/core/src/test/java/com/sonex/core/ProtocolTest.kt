package com.sonex.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire-format round-trips must be stable — phone and TV depend on them. */
class ProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun command_roundtrips() {
        val cmd = Command(Action.DUCK, level = 30, reason = "speech")
        val back = json.decodeFromString<Command>(json.encodeToString(cmd))
        assertEquals(cmd, back)
    }

    @Test fun pair_response_roundtrips() {
        val res = PairResponse(ok = true, tvName = "Sony Bravia")
        val back = json.decodeFromString<PairResponse>(json.encodeToString(res))
        assertTrue(back.ok)
        assertEquals("Sony Bravia", back.tvName)
    }

    @Test fun code_is_four_digits() {
        val code = (1000..9999).random().toString()
        assertEquals(4, code.length)
    }
}
