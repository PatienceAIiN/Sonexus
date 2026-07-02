package com.sonex.mobile.voice

import android.util.Log
import com.sonex.core.IntentParser
import com.sonex.core.VoiceIntent
import com.sonex.core.WakeWordGate
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File

/**
 * Phase 6: on-device voice control. PCM frames are tapped off the existing
 * DetectionEngine mic stream (one AudioRecord, no contention) and fed to Vosk
 * recognisers for English + Hindi. Transcripts flow through the wake-word gate
 * ("SoNex, …") and the pure IntentParser. Nothing leaves the device.
 *
 * Wake word today is transcript-spotting via Vosk. The dedicated openWakeWord
 * "SoNex" model needs custom recordings — flagged to the project owner; it
 * drops into [WakeWordGate.arm] when trained, with no other code changes.
 */
class VoiceController(
    private val gate: WakeWordGate = WakeWordGate(),
    private val now: () -> Long = System::currentTimeMillis,
    private val onWake: () -> Unit = {},
    private val onIntent: (VoiceIntent) -> Unit
) {
    /** Feed one final transcript. Returns the intent if one was accepted. */
    fun onTranscript(text: String): VoiceIntent? {
        if (text.isBlank()) return null
        val t = now()
        if (WakeWordGate.containsWakeWord(text)) { gate.arm(t); onWake() }
        val intent = IntentParser.parse(text) ?: return null
        if (!gate.tryAccept(t)) return null // no wake word => ignore commands
        onIntent(intent)
        return intent
    }
}

/** Streams 16kHz PCM into one or more Vosk models; emits final transcripts. */
class VoskTranscriptSource(
    modelDirs: List<File>,
    private val onTranscript: (String) -> Unit
) : AutoCloseable {

    private val recognizers: List<Recognizer> = modelDirs.mapNotNull { dir ->
        runCatching { Recognizer(Model(dir.absolutePath), 16_000f) }
            .onFailure { Log.w("SonexVoice", "Vosk model failed to load: $dir", it) }
            .getOrNull()
    }

    val available: Boolean get() = recognizers.isNotEmpty()

    fun accept(buf: ShortArray, n: Int) {
        if (recognizers.isEmpty()) return
        for (r in recognizers) {
            runCatching {
                if (r.acceptWaveForm(buf, n)) {
                    val text = JSONObject(r.result).optString("text")
                    if (text.isNotBlank()) onTranscript(text)
                }
            }
        }
    }

    override fun close() { recognizers.forEach { runCatching { it.close() } } }

    companion object {
        /** Installed voice model dirs: filesDir/voice/<lang>/ (en, hi). */
        fun installedModels(baseDir: File): List<File> =
            File(baseDir, "voice").listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
    }
}
