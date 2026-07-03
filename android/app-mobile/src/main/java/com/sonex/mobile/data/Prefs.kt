package com.sonex.mobile.data

import android.content.Context
import android.util.Base64
import com.sonex.mobile.audio.Calibration
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Simple SharedPreferences-backed store. All data-sharing consents default OFF. */
object Prefs {
    private const val FILE = "sonex_prefs"
    private const val SECURE_FILE = "sonex_secure"
    private val json = Json { ignoreUnknownKeys = true }

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Hardware-backed encryption for credentials; plain prefs if Keystore is broken. */
    private fun secure(c: Context) = runCatching {
        val key = androidx.security.crypto.MasterKey.Builder(c)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build()
        androidx.security.crypto.EncryptedSharedPreferences.create(
            c, SECURE_FILE, key,
            androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse { sp(c) }

    // ---- Theme (system | dark | light), observable for live switching ----
    val themeState = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun themeMode(c: Context): String = sp(c).getString("theme_mode", "light") ?: "light"
    fun setThemeMode(c: Context, v: String) {
        sp(c).edit().putString("theme_mode", v).apply()
        themeState.value = v
    }

    fun isLoggedIn(c: Context) = sp(c).getBoolean("logged_in", false)
    fun setLoggedIn(c: Context, v: Boolean) = sp(c).edit().putBoolean("logged_in", v).apply()

    fun pairedTv(c: Context): String? = sp(c).getString("paired_tv", null)
    fun setPairedTv(c: Context, name: String) = sp(c).edit().putString("paired_tv", name).apply()

    fun duckLevel(c: Context) = sp(c).getInt("duck_level", 30)
    fun setDuckLevel(c: Context, v: Int) = sp(c).edit().putInt("duck_level", v).apply()

    fun currentCalibration(c: Context): Calibration =
        sp(c).getString("calibration", null)?.let { json.decodeFromString(it) } ?: Calibration()

    fun saveCalibration(c: Context, cal: Calibration) =
        sp(c).edit().putString("calibration", json.encodeToString(cal)).apply()

    // ---- Local account (Phase 1: no server; first sign-in creates it) ----

    fun hasAccount(c: Context) = sp(c).contains("acct_hash")

    fun accountEmail(c: Context): String? = sp(c).getString("acct_email", null)

    fun setAccountEmail(c: Context, email: String) =
        sp(c).edit().putString("acct_email", email.trim().lowercase()).apply()

    /** Create the local account. Overwrites any existing one. */
    fun createAccount(c: Context, email: String, password: CharArray) {
        val salt = PasswordHasher.newSalt()
        val hash = PasswordHasher.hash(password, salt)
        sp(c).edit()
            .putString("acct_email", email.trim().lowercase())
            .putString("acct_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("acct_hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .apply()
    }

    /** Verify credentials against the stored local account. */
    fun verifyAccount(c: Context, email: String, password: CharArray): Boolean {
        val p = sp(c)
        val storedEmail = p.getString("acct_email", null) ?: return false
        val salt = p.getString("acct_salt", null) ?: return false
        val hash = p.getString("acct_hash", null) ?: return false
        if (storedEmail != email.trim().lowercase()) return false
        return PasswordHasher.verify(
            password,
            Base64.decode(salt, Base64.NO_WRAP),
            Base64.decode(hash, Base64.NO_WRAP)
        )
    }

    /**
     * Sign in: create the account on first run, verify afterwards.
     * Assumes inputs already passed [AuthValidator].
     */
    fun signIn(c: Context, email: String, password: CharArray): AuthResult = when {
        !hasAccount(c) -> {
            createAccount(c, email, password)
            setLoggedIn(c, true)
            AuthResult.Success(created = true)
        }
        verifyAccount(c, email, password) -> {
            setLoggedIn(c, true)
            AuthResult.Success(created = false)
        }
        else -> AuthResult.Failure("Email or password doesn't match this device's account")
    }

    // ---- Per-output-device rules (Phase 7) ----

    fun targetRule(c: Context, targetId: String): com.sonex.core.TargetRule =
        sp(c).getString("rule_$targetId", null)
            ?.let { runCatching { com.sonex.core.TargetRule.valueOf(it) }.getOrNull() }
            ?: com.sonex.core.TargetRule.DUCK

    fun setTargetRule(c: Context, targetId: String, rule: com.sonex.core.TargetRule) =
        sp(c).edit().putString("rule_$targetId", rule.name).apply()

    // ---- Server link (Phase 3/4: events, consented clips, OTA models) ----

    /** SoNex cloud. Overridable in Settings; empty string there = offline mode. */
    const val DEFAULT_SERVER = "https://sonexus.onrender.com"

    fun serverUrl(c: Context): String? =
        sp(c).getString("server_url", DEFAULT_SERVER)?.ifBlank { null }
    fun setServerUrl(c: Context, v: String?) = sp(c).edit().putString("server_url", v).apply()

    fun deviceKey(c: Context): String? = secure(c).getString("device_key", null)
    fun setDeviceKey(c: Context, v: String) = secure(c).edit().putString("device_key", v).apply()

    fun deviceId(c: Context): String? = secure(c).getString("device_id", null)
    fun setDeviceId(c: Context, v: String) = secure(c).edit().putString("device_id", v).apply()

    fun authToken(c: Context): String? = secure(c).getString("auth_token", null)
    fun setAuthToken(c: Context, v: String?) = secure(c).edit().putString("auth_token", v).apply()

    /**
     * Sign out: drop credentials + session, keep calibration/pairing/consents.
     * Also stops listening — the mic stays off until the user presses Start.
     */
    fun logout(c: Context) {
        secure(c).edit().remove("auth_token").apply()
        setLoggedIn(c, false)
        setListeningEnabled(c, false)
        c.stopService(android.content.Intent(c, com.sonex.mobile.audio.ListeningService::class.java))
    }

    // ---- Room geometry (metres) — tunes sensitivity/coverage ----
    fun roomWidth(c: Context) = sp(c).getFloat("room_w", 0f)
    fun roomLength(c: Context) = sp(c).getFloat("room_l", 0f)
    fun setRoomSize(c: Context, w: Float, l: Float) =
        sp(c).edit().putFloat("room_w", w).putFloat("room_l", l).apply()

    fun roomPreset(c: Context): String? = sp(c).getString("room_preset", null)
    fun setRoomPreset(c: Context, p: com.sonex.core.RoomProfile.Preset) {
        sp(c).edit().putString("room_preset", p.name).apply()
        setRoomSize(c, p.widthM.toFloat(), p.lengthM.toFloat())
    }

    // ---- Master listening switch: OFF until the user presses Start, and it
    // never flips itself — only the Start/Stop button changes it. ----
    fun listeningEnabled(c: Context) = sp(c).getBoolean("listening_on", false)
    fun setListeningEnabled(c: Context, v: Boolean) =
        sp(c).edit().putBoolean("listening_on", v).apply()

    // ---- UX ----
    @Suppress("UNUSED_PARAMETER")
    fun hapticsEnabled(c: Context) = true // always on — consistent tactile feel

    /** Keep my data on device only (true) or allow server storage (false). */
    fun storeOnDeviceOnly(c: Context) = !sp(c).getBoolean("c_store_server", false)

    // Consents — all false by default (privacy by default).
    fun consentUploadClips(c: Context) = sp(c).getBoolean("c_upload", false)
    fun consentTelemetry(c: Context) = sp(c).getBoolean("c_telemetry", false)
    fun consentTraining(c: Context) = sp(c).getBoolean("c_training", false)
    fun consentWakeWord(c: Context) = sp(c).getBoolean("c_wakeword", false)
    fun setConsent(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean(key, v).apply()

    /** "Delete all my data": wipes account, pairing, calibration, consents — and stops the mic. */
    fun clearAll(c: Context) {
        c.stopService(android.content.Intent(c, com.sonex.mobile.audio.ListeningService::class.java))
        sp(c).edit().clear().apply()
        secure(c).edit().clear().apply()
    }
}
