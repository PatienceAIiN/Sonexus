package com.sonex.tv

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * TV in-app updates: on launch, ask the server for the latest published build,
 * compare version codes, and (on the user's OK) download the APK and hand it to
 * the system installer — same flow as the phone app.
 */
object TvUpdater {
    private const val SERVER = "https://sonexus.onrender.com"

    @Serializable
    data class Release(val version_code: Int, val version_name: String = "", val url: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun installedVersionCode(c: Context): Long =
        runCatching {
            c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode
        }.getOrDefault(0)

    /** The newer TV release, or null (up to date / unreachable). */
    suspend fun check(c: Context): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL("$SERVER/v1/app/releases").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 15_000
            try {
                if (conn.responseCode != 200) return@runCatching null
                val body = conn.inputStream.use { it.readBytes().decodeToString() }
                json.decodeFromString<Map<String, Release>>(body)["tv"]
                    ?.takeIf { it.version_code > installedVersionCode(c) }
            } finally { conn.disconnect() }
        }.getOrNull()
    }

    /** Download + launch installer. Returns an error message or null on success. */
    suspend fun downloadAndInstall(
        c: Context, release: Release, onProgress: (Float) -> Unit
    ): String? = withContext(Dispatchers.IO) {
        try {
            val apk = File(c.cacheDir, "sonex-tv-update.apk")
            val conn = URL(release.url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 60_000
            try {
                if (conn.responseCode !in 200..299) return@withContext "Download failed (${conn.responseCode})"
                val total = conn.contentLengthLong.coerceAtLeast(1)
                conn.inputStream.use { input ->
                    apk.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            onProgress(done.toFloat() / total)
                        }
                    }
                }
            } finally { conn.disconnect() }
            val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", apk)
            c.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            null
        } catch (t: Throwable) {
            "Couldn't update: ${t.message ?: "network error"}"
        }
    }
}
