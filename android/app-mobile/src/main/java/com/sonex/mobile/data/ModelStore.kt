package com.sonex.mobile.data

import android.content.Context
import android.util.Log
import com.sonex.core.Manifests
import com.sonex.core.ModelEntry
import com.sonex.core.ModelManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Loads model files from app storage, verifies sha256 against the manifest,
 * and exposes them to the DetectionEngine. OTA: checks the server manifest,
 * downloads newer models, verifies, and hot-swaps — keeping the last-known-good
 * file whenever anything fails. Never changes app code paths.
 */
class ModelStore(context: Context, private val appVersion: Int = 1) {

    companion object { private const val TAG = "SonexModels" }

    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val manifestFile = File(dir, "manifest.json")
    private val verified = mutableMapOf<String, Boolean>() // file name -> checksum ok (per process)

    fun manifest(): ModelManifest? =
        manifestFile.takeIf { it.isFile }?.readText()?.let(Manifests::parse)

    /**
     * The verified model file for a kind ("vad", "sound", "home"), or null —
     * in which case the engine uses the heuristic fallback.
     */
    fun verifiedFile(kind: String): File? {
        val entry = manifest()?.models?.get(kind) ?: return null
        if (!Manifests.usable(entry, appVersion)) return null
        val f = File(dir, entry.file)
        if (!f.isFile) return null
        val ok = verified.getOrPut(entry.file) {
            Manifests.verify(entry, f.readBytes()).also {
                if (!it) Log.w(TAG, "Checksum mismatch for ${entry.file} — refusing to load")
            }
        }
        return if (ok) f else null
    }

    /**
     * OTA sync against `GET /v1/models/manifest`. For each entry that is newer
     * than what we hold and compatible with this app version: download to a
     * temp file, verify sha256, then atomically swap. On any failure the
     * previous file and manifest entry stay in place (last-known-good).
     * Returns the kinds that were updated.
     */
    suspend fun sync(baseUrl: String, deviceKey: String, deviceId: String): List<String> =
        withContext(Dispatchers.IO) {
            val remoteText = runCatching {
                httpGet("$baseUrl/v1/models/manifest?device=$deviceId", deviceKey).decodeToString()
            }.getOrElse { Log.w(TAG, "Manifest fetch failed", it); return@withContext emptyList() }
            val remote = Manifests.parse(remoteText) ?: return@withContext emptyList()
            val local = manifest()

            val updated = mutableListOf<String>()
            val mergedModels = (local?.models ?: emptyMap()).toMutableMap()
            for ((kind, entry) in remote.models) {
                if (!Manifests.usable(entry, appVersion)) continue
                val current = local?.models?.get(kind)
                if (current != null && !Manifests.isNewer(entry.version, current.version)) continue
                if (downloadVerified(baseUrl, deviceKey, entry)) {
                    mergedModels[kind] = entry
                    verified.remove(entry.file)
                    updated += kind
                }
            }
            if (updated.isNotEmpty()) {
                // Persist the merged manifest only after every file is in place.
                manifestFile.writeText(encodeManifest(ModelManifest(mergedModels, remote.thresholds)))
                Log.i(TAG, "OTA updated models: $updated")
            }
            updated
        }

    private fun downloadVerified(baseUrl: String, deviceKey: String, entry: ModelEntry): Boolean {
        val url = if (entry.url.startsWith("http")) entry.url else baseUrl + entry.url
        return runCatching {
            val bytes = httpGet(url, deviceKey)
            if (!Manifests.verify(entry, bytes)) {
                Log.w(TAG, "Downloaded ${entry.file} failed checksum — keeping last-known-good")
                return false
            }
            val tmp = File(dir, entry.file + ".tmp")
            tmp.writeBytes(bytes)
            tmp.renameTo(File(dir, entry.file))
        }.getOrElse { Log.w(TAG, "Download failed for ${entry.file}", it); false }
    }

    private fun httpGet(url: String, deviceKey: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000; conn.readTimeout = 30_000
        conn.setRequestProperty("X-Device-Key", deviceKey)
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode} for $url")
            return conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
    }

    private fun encodeManifest(m: ModelManifest): String {
        val json = kotlinx.serialization.json.Json
        val models = m.models.mapValues {
            json.encodeToJsonElement(ModelEntry.serializer(), it.value)
        }
        val root = kotlinx.serialization.json.JsonObject(
            mapOf(
                "models" to kotlinx.serialization.json.JsonObject(models),
                "thresholds" to json.encodeToJsonElement(
                    com.sonex.core.ManifestThresholds.serializer(), m.thresholds
                )
            )
        )
        return root.toString()
    }
}
