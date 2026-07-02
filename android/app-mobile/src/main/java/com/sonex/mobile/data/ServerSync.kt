package com.sonex.mobile.data

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Thin client for the device-authenticated server API: registers this phone
 * once (per-device API key) and keeps consent toggles in sync. Every call
 * returns a human-readable status for the UI toast — never a raw stack trace.
 */
object ServerSync {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class RegisterIn(val device_name: String, val home_name: String)
    @Serializable private data class RegisterOut(val device_id: Int, val api_key: String)
    @Serializable private data class ConsentIn(val purpose: String, val granted: Boolean)

    sealed class Status(val message: String) {
        class Ok(message: String) : Status(message)
        class Failed(message: String) : Status(message)
    }

    /** Settings key -> server consent purpose. */
    val PURPOSES = mapOf(
        "c_upload" to "upload_clips",
        "c_telemetry" to "telemetry",
        "c_training" to "training",
        "c_wakeword" to "wake_word",
        "c_store_server" to "store_on_server"
    )

    private suspend fun ensureDevice(c: Context, base: String): String? {
        Prefs.deviceKey(c)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val (code, body) = http(
                    "$base/v1/devices/register", "POST",
                    json.encodeToString(RegisterIn.serializer(),
                        RegisterIn(Build.MODEL ?: "Android phone", "My Home"))
                )
                if (code !in 200..299) return@runCatching null
                val out = json.decodeFromString(RegisterOut.serializer(), body)
                Prefs.setDeviceKey(c, out.api_key)
                Prefs.setDeviceId(c, out.device_id.toString())
                out.api_key
            }.getOrNull()
        }
    }

    /** Push one consent change; returns a toast-ready status. */
    suspend fun syncConsent(c: Context, prefKey: String, granted: Boolean): Status {
        val purpose = PURPOSES[prefKey] ?: return Status.Failed("Unknown setting")
        val base = (Prefs.serverUrl(c) ?: "").removeSuffix("/")
        if (base.isBlank()) return Status.Failed("Saved on device (no server)")
        val key = ensureDevice(c, base)
            ?: return Status.Failed("Saved on device — server unreachable")
        return withContext(Dispatchers.IO) {
            try {
                val (code, _) = http(
                    "$base/v1/consents", "PUT",
                    json.encodeToString(ConsentIn.serializer(), ConsentIn(purpose, granted)),
                    mapOf("X-Device-Key" to key)
                )
                if (code in 200..299) Status.Ok("Synced with server ✓ (${if (granted) "ON" else "OFF"})")
                else Status.Failed(friendlyHttp(code))
            } catch (t: Throwable) {
                Status.Failed(friendlyNetwork(t))
            }
        }
    }

    /** In-app feedback -> growth team, with opt-in diagnostics (never audio). */
    suspend fun sendFeedback(c: Context, message: String, includeDiagnostics: Boolean): Status {
        val base = (Prefs.serverUrl(c) ?: "").removeSuffix("/")
        if (base.isBlank()) return Status.Failed("No server configured")
        val diag = if (!includeDiagnostics) "null" else {
            val cal = Prefs.currentCalibration(c)
            val entries = mapOf(
                "app_version" to (runCatching {
                    c.packageManager.getPackageInfo(c.packageName, 0).versionName
                }.getOrNull() ?: "?"),
                "android" to Build.VERSION.RELEASE,
                "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
                "calibration" to "floor=${cal.noiseFloorDb.toInt()}dB media=${cal.mediaBaselineDb.toInt()}dB talk=${cal.mediaPlusTalkDb.toInt()}dB sens=${cal.sensitivity}",
                "duck_level" to Prefs.duckLevel(c).toString(),
                "room" to "${Prefs.roomWidth(c)}x${Prefs.roomLength(c)}m",
                "paired_tv" to (Prefs.pairedTv(c) ?: "none"),
                "consents" to "upload=${Prefs.consentUploadClips(c)} telemetry=${Prefs.consentTelemetry(c)} training=${Prefs.consentTraining(c)} wake=${Prefs.consentWakeWord(c)}"
            )
            entries.entries.joinToString(",", "{", "}") {
                "\"${it.key}\":\"${it.value.replace("\"", "'")}\""
            }
        }
        val body = """{"email":"${Prefs.accountEmail(c) ?: ""}","message":${json.encodeToString(kotlinx.serialization.serializer<String>(), message)},"diagnostics":$diag}"""
        return withContext(Dispatchers.IO) {
            try {
                val (code, _) = http("$base/v1/feedback", "POST", body)
                if (code in 200..299) Status.Ok("Feedback sent — thank you! ✓")
                else Status.Failed(friendlyHttp(code))
            } catch (t: Throwable) { Status.Failed(friendlyNetwork(t)) }
        }
    }

    private fun http(
        url: String, method: String, body: String,
        headers: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000; conn.readTimeout = 8_000
        conn.requestMethod = method
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes().decodeToString() } ?: ""
            code to text
        } finally { conn.disconnect() }
    }

    fun friendlyNetwork(t: Throwable): String = when (t) {
        is UnknownHostException -> "No internet connection"
        is SocketTimeoutException -> "Server is waking up — try again in a moment"
        else -> "Couldn't reach the SoNex server"
    }

    fun friendlyHttp(code: Int): String = when (code) {
        401 -> "Session expired — sign in again"
        403 -> "Not allowed — check your consents"
        404 -> "Server doesn't support this yet"
        409 -> "Already exists"
        422 -> "The server rejected that value"
        in 500..599 -> "Server had a problem — try again"
        else -> "Something went wrong (code $code)"
    }
}
