package com.sonex.core

/**
 * Phase 6: voice command understanding, on-device. The recogniser (Vosk)
 * produces a transcript; this pure parser maps it to an intent in English or
 * Hindi (Devanagari and romanised). Unit-tested with fixture transcripts.
 */
enum class VoiceIntent { LOWER_VOLUME, RAISE_VOLUME, STOP, PLAY, MUTE, RESTORE }

object IntentParser {

    /** Phrase fragments per intent. Matching is case-insensitive substring. */
    private val table: List<Pair<VoiceIntent, List<String>>> = listOf(
        VoiceIntent.MUTE to listOf(
            "mute", "silence",
            "आवाज़ बंद", "आवाज बंद", "म्यूट", "awaaz band", "awaz band"
        ),
        VoiceIntent.LOWER_VOLUME to listOf(
            "lower volume", "volume down", "lower the volume", "turn it down", "quieter", "reduce volume",
            "आवाज़ कम", "आवाज कम", "वॉल्यूम कम", "धीमा करो",
            "awaaz kam", "awaz kam", "volume kam", "dheema karo", "dhima karo"
        ),
        VoiceIntent.RAISE_VOLUME to listOf(
            "raise volume", "volume up", "raise the volume", "turn it up", "louder", "increase volume",
            "आवाज़ बढ़ाओ", "आवाज बढ़ाओ", "वॉल्यूम बढ़ाओ", "तेज़ करो", "तेज करो",
            "awaaz badhao", "awaz badhao", "volume badhao", "tez karo", "volume zyada"
        ),
        VoiceIntent.STOP to listOf(
            "stop", "pause",
            "रुको", "रोको", "बंद करो", "ruko", "roko", "band karo"
        ),
        VoiceIntent.PLAY to listOf(
            "play", "resume", "continue",
            "चलाओ", "चालू करो", "chalao", "chalu karo", "shuru karo"
        ),
        VoiceIntent.RESTORE to listOf(
            "normal volume", "restore volume", "unmute",
            "आवाज़ वापस", "आवाज वापस", "awaaz wapas", "awaz wapas", "volume wapas"
        )
    )

    /** Intent plus an optional amount: "increase volume by 20" -> (RAISE, 20). */
    data class Parsed(val intent: VoiceIntent, val amount: Int? = null)

    private val numberWords = mapOf(
        "ten" to 10, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "das" to 10, "bees" to 20, "tees" to 30, "chalis" to 40, "pachas" to 50
    )

    /** Parse transcript + extract "by N" / "to N" style amounts (EN + HI). */
    fun parseWithAmount(transcript: String): Parsed? {
        val intent = parse(transcript) ?: return null
        val t = transcript.lowercase()
        val digits = Regex("\\b(\\d{1,3})\\b").find(t)?.groupValues?.get(1)?.toIntOrNull()
        val word = numberWords.entries.firstOrNull { t.contains(it.key) }?.value
        val amount = (digits ?: word)?.coerceIn(1, 100)
        return Parsed(intent, amount)
    }

    /**
     * Map a transcript to an intent, or null if nothing matches.
     * Earlier table entries win, so "mute" beats the "stop" in "stop muting".
     */
    fun parse(transcript: String): VoiceIntent? {
        val t = transcript.lowercase().trim()
        if (t.isEmpty()) return null
        for ((intent, phrases) in table) {
            if (phrases.any { t.contains(it) }) return intent
        }
        return null
    }

    /** Translate an intent into the wire Command applied to phone + TV. */
    fun toCommand(intent: VoiceIntent, duckPercent: Int, boostPercent: Int): Command = when (intent) {
        VoiceIntent.LOWER_VOLUME -> Command(Action.DUCK, duckPercent, "voice")
        VoiceIntent.RAISE_VOLUME -> Command(Action.BOOST, boostPercent, "voice")
        VoiceIntent.MUTE -> Command(Action.MUTE, reason = "voice")
        VoiceIntent.STOP -> Command(Action.PAUSE, reason = "voice")
        VoiceIntent.PLAY -> Command(Action.RESUME, reason = "voice")
        VoiceIntent.RESTORE -> Command(Action.RESTORE, reason = "voice")
    }
}

/**
 * Wake-word gate: commands are only accepted for a short window after the wake
 * word ("SoNex") fires. Pure and clock-agnostic — callers pass timestamps.
 */
class WakeWordGate(private val windowMs: Long = 8_000) {
    private var armedAt: Long = Long.MIN_VALUE

    /** The wake word was heard. */
    fun arm(nowMs: Long) { armedAt = nowMs }

    /** True (and disarms) if a command at [nowMs] falls inside the window. */
    fun tryAccept(nowMs: Long): Boolean {
        val open = armedAt != Long.MIN_VALUE && nowMs - armedAt in 0..windowMs
        if (open) armedAt = Long.MIN_VALUE // one command per wake
        return open
    }

    companion object {
        /** Wake word spotted in a transcript ("sonex", common mishearings). */
        fun containsWakeWord(transcript: String): Boolean {
            val t = transcript.lowercase()
            return listOf("sonex", "so nex", "sonics", "सोनेक्स").any { t.contains(it) }
        }
    }
}
