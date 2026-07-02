package com.sonex.tv

import com.sonex.core.Action
import com.sonex.core.Command
import com.sonex.core.PairRequest
import com.sonex.core.PairingProtocol
import com.sonex.core.VolumePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TV-side behaviour of the protocol, exactly as TvServer drives it:
 * parse the inbound line, decide, apply volume via VolumePolicy.
 */
class TvHandshakeTest {

    @Test fun accepts_matching_code_and_reports_tv_name() {
        val line = PairingProtocol.encodePair(PairRequest("4321", "Pixel 9"))
        val msg = PairingProtocol.parseLine(line) as PairingProtocol.Message.Pair
        val res = PairingProtocol.pairResponseFor(msg.request, "4321", "Bravia XR")
        assertTrue(res.ok)
        assertEquals("Bravia XR", res.tvName)
    }

    @Test fun rejects_wrong_code_without_leaking_it() {
        val msg = PairingProtocol.parseLine(
            PairingProtocol.encodePair(PairRequest("1111", "Pixel 9"))
        ) as PairingProtocol.Message.Pair
        val res = PairingProtocol.pairResponseFor(msg.request, "4321", "Bravia XR")
        assertFalse(res.ok)
        assertFalse("reply must not contain the real code", res.error.contains("4321"))
    }

    @Test fun duck_then_restore_returns_tv_to_original_volume() {
        val policy = VolumePolicy(maxVolume = 100)
        var volume = 60

        val duck = (PairingProtocol.parseLine(
            PairingProtocol.encodeCommand(Command(Action.DUCK, 30, "speech"))
        ) as PairingProtocol.Message.Cmd).command
        policy.apply(duck, volume)?.let { volume = it }
        assertEquals(30, volume)

        val restore = (PairingProtocol.parseLine(
            PairingProtocol.encodeCommand(Command(Action.RESTORE, reason = "quiet"))
        ) as PairingProtocol.Message.Cmd).command
        policy.apply(restore, volume)?.let { volume = it }
        assertEquals(60, volume)
    }

    @Test fun malformed_traffic_is_ignored() {
        assertNull(PairingProtocol.parseLine("SSH-2.0-OpenSSH_9.8")) // port scanner noise
        assertNull(PairingProtocol.parseLine("PAIR {\"code\": 12}"))  // wrong types...
    }
}
