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
    private val json = Json { ignoreUnknownKeys = true }

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

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

    fun serverUrl(c: Context): String? = sp(c).getString("server_url", null)
    fun setServerUrl(c: Context, v: String?) = sp(c).edit().putString("server_url", v).apply()

    fun deviceKey(c: Context): String? = sp(c).getString("device_key", null)
    fun setDeviceKey(c: Context, v: String) = sp(c).edit().putString("device_key", v).apply()

    fun deviceId(c: Context): String? = sp(c).getString("device_id", null)
    fun setDeviceId(c: Context, v: String) = sp(c).edit().putString("device_id", v).apply()

    fun authToken(c: Context): String? = sp(c).getString("auth_token", null)
    fun setAuthToken(c: Context, v: String?) = sp(c).edit().putString("auth_token", v).apply()

    // Consents — all false by default (privacy by default).
    fun consentUploadClips(c: Context) = sp(c).getBoolean("c_upload", false)
    fun consentTelemetry(c: Context) = sp(c).getBoolean("c_telemetry", false)
    fun consentTraining(c: Context) = sp(c).getBoolean("c_training", false)
    fun consentWakeWord(c: Context) = sp(c).getBoolean("c_wakeword", false)
    fun setConsent(c: Context, key: String, v: Boolean) = sp(c).edit().putBoolean(key, v).apply()

    /** "Delete all my data": wipes account, pairing, calibration, consents. */
    fun clearAll(c: Context) = sp(c).edit().clear().apply()
}
