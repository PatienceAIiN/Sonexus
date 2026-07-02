package com.sonex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PairingProtocolTest {

    @Test fun code_is_always_four_digits() {
        val rng = Random(42)
        repeat(1000) {
            val code = PairingProtocol.newCode(rng)
            assertEquals(4, code.length)
            assertTrue(code.all(Char::isDigit))
            assertTrue("no leading zero — codes read naturally on TV", code.first() != '0')
        }
    }

    @Test fun pair_line_roundtrips() {
        val req = PairRequest("1234", "My Phone")
        val msg = PairingProtocol.parseLine(PairingProtocol.encodePair(req))
        assertEquals(req, (msg as PairingProtocol.Message.Pair).request)
    }

    @Test fun cmd_line_roundtrips() {
        val cmd = Command(Action.DUCK, level = 30, reason = "speech")
        val msg = PairingProtocol.parseLine(PairingProtocol.encodeCommand(cmd))
        assertEquals(cmd, (msg as PairingProtocol.Message.Cmd).command)
    }

    @Test fun paired_reply_roundtrips() {
        val res = PairResponse(true, "Sony Bravia")
        assertEquals(res, PairingProtocol.parsePaired(PairingProtocol.encodePaired(res)))
    }

    @Test fun state_report_roundtrips() {
        val state = TvState("Bravia", currentVolume = 7, isPlaying = true)
        assertEquals(state, PairingProtocol.parseState(PairingProtocol.encodeState(state)))
    }

    @Test fun garbage_lines_parse_to_null_not_crash() {
        assertNull(PairingProtocol.parseLine(""))
        assertNull(PairingProtocol.parseLine("GET / HTTP/1.1"))
        assertNull(PairingProtocol.parseLine("PAIR not-json"))
        assertNull(PairingProtocol.parseLine("CMD {\"broken\":"))
        assertNull(PairingProtocol.parsePaired("banana"))
        assertNull(PairingProtocol.parseState("PAIRED {}"))
    }

    @Test fun correct_code_pairs() {
        val res = PairingProtocol.pairResponseFor(PairRequest("1234", "Phone"), "1234", "TV")
        assertTrue(res.ok)
        assertEquals("TV", res.tvName)
        assertEquals("", res.error)
    }

    @Test fun wrong_code_is_rejected() {
        val res = PairingProtocol.pairResponseFor(PairRequest("0000", "Phone"), "1234", "TV")
        assertFalse(res.ok)
        assertEquals("Wrong code", res.error)
    }

    @Test fun full_handshake_phone_to_tv_and_back() {
        // Phone encodes, TV parses + decides, TV encodes, phone parses.
        val tvCode = PairingProtocol.newCode(Random(7))
        val wire = PairingProtocol.encodePair(PairRequest(tvCode, "Pixel"))
        val onTv = PairingProtocol.parseLine(wire) as PairingProtocol.Message.Pair
        val reply = PairingProtocol.pairResponseFor(onTv.request, tvCode, "Bravia")
        val onPhone = PairingProtocol.parsePaired(PairingProtocol.encodePaired(reply))
        assertTrue(onPhone!!.ok)
        assertEquals("Bravia", onPhone.tvName)
    }
}
