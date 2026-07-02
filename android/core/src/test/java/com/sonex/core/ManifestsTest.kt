package com.sonex.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestsTest {

    private val localShape = """
        { "vad":   {"file":"silero_vad.onnx","version":"1.0","sha256":"abc"},
          "sound": {"file":"yamnet.tflite","version":"1.0","sha256":"def"} }
    """.trimIndent()

    private val serverShape = """
        { "models": {
            "vad":  {"id":1,"file":"silero_vad.onnx","version":"1.1","sha256":"abc","minAppVersion":1,"url":"/v1/models/1/download"},
            "home": {"id":9,"file":"home_lr.onnx","version":"3.2","sha256":"xyz","minAppVersion":2,"url":"/v1/models/9/download"}},
          "thresholds": {"sensitivity":0.7,"boostMarginDb":6.0} }
    """.trimIndent()

    @Test fun parses_local_script_shape() {
        val m = Manifests.parse(localShape)!!
        assertEquals(setOf("vad", "sound"), m.models.keys)
        assertEquals("silero_vad.onnx", m.models["vad"]!!.file)
        assertEquals(0.5, m.thresholds.sensitivity, 0.0) // defaults apply
    }

    @Test fun parses_server_shape_with_thresholds() {
        val m = Manifests.parse(serverShape)!!
        assertEquals("1.1", m.models["vad"]!!.version)
        assertEquals("/v1/models/9/download", m.models["home"]!!.url)
        assertEquals(0.7, m.thresholds.sensitivity, 0.0)
        assertEquals(6.0, m.thresholds.boostMarginDb, 0.0)
    }

    @Test fun garbage_parses_to_null() {
        assertNull(Manifests.parse(""))
        assertNull(Manifests.parse("not json"))
        assertNull(Manifests.parse("{}"))
        assertNull(Manifests.parse("""{"count": 3}"""))
    }

    @Test fun version_compare_is_numeric_not_lexicographic() {
        assertTrue(Manifests.isNewer("1.10", "1.9"))
        assertTrue(Manifests.isNewer("2.0", "1.99"))
        assertFalse(Manifests.isNewer("1.0", "1.0"))
        assertFalse(Manifests.isNewer("1.0", "1.0.1"))
        assertTrue(Manifests.isNewer("1.0.1", "1.0"))
    }

    @Test fun checksum_verify_accepts_only_matching_bytes() {
        val bytes = "model-weights".toByteArray()
        val good = ModelEntry("m.onnx", "1.0", Manifests.sha256Hex(bytes))
        val bad = good.copy(sha256 = "deadbeef")
        assertTrue(Manifests.verify(good, bytes))
        assertFalse("bad checksum must trigger fallback", Manifests.verify(bad, bytes))
        assertFalse(Manifests.verify(good, "tampered".toByteArray()))
    }

    @Test fun checksum_is_case_insensitive() {
        val bytes = byteArrayOf(1, 2, 3)
        val entry = ModelEntry("m", "1", Manifests.sha256Hex(bytes).uppercase())
        assertTrue(Manifests.verify(entry, bytes))
    }

    @Test fun min_app_version_gates_usability() {
        val entry = ModelEntry("m", "1", "x", minAppVersion = 3)
        assertFalse("old app must not load a too-new model", Manifests.usable(entry, appVersion = 2))
        assertTrue(Manifests.usable(entry, appVersion = 3))
    }

    @Test fun known_sha256_vector() {
        // sha256("abc") — classic test vector.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Manifests.sha256Hex("abc".toByteArray())
        )
    }
}
