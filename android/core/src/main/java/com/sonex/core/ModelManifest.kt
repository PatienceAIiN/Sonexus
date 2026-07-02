package com.sonex.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.InputStream
import java.security.MessageDigest

/**
 * OTA model manifest. Models are data, not code: the app selects model files
 * at runtime purely from this manifest, so retraining never needs an app
 * update — only a new file + manifest entry.
 */
@Serializable
data class ModelEntry(
    val file: String,
    val version: String,
    val sha256: String,
    val minAppVersion: Int = 1,
    /** Server download path; empty for local-only manifests. */
    val url: String = "",
    val id: Int = 0
)

@Serializable
data class ManifestThresholds(
    val sensitivity: Double = 0.5,
    val boostMarginDb: Double = 8.0
)

data class ModelManifest(
    /** Keyed by kind: "vad", "sound", "home". */
    val models: Map<String, ModelEntry>,
    val thresholds: ManifestThresholds = ManifestThresholds()
)

object Manifests {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Parse either shape: the server's `{"models": {...}, "thresholds": {...}}`
     * or the local script's flat `{"vad": {...}, "sound": {...}}`.
     * Null for garbage — never throws.
     */
    fun parse(text: String): ModelManifest? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val modelsObj: JsonObject =
            (root["models"] as? JsonObject) ?: JsonObject(root.filterValues { it is JsonObject })
        val models = modelsObj.mapValues { (_, v) -> json.decodeFromJsonElement(ModelEntry.serializer(), v) }
        val thresholds = root["thresholds"]?.let {
            json.decodeFromJsonElement(ManifestThresholds.serializer(), it)
        } ?: ManifestThresholds()
        if (models.isEmpty()) null else ModelManifest(models, thresholds)
    }.getOrNull()

    /** Dotted-numeric version compare: "1.10" is newer than "1.9". */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val b = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** An entry is usable if this app is new enough for it. */
    fun usable(entry: ModelEntry, appVersion: Int): Boolean = appVersion >= entry.minAppVersion

    fun sha256Hex(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = stream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(bytes: ByteArray): String = sha256Hex(bytes.inputStream())

    /** Constant-shape verification used before any model is trusted. */
    fun verify(entry: ModelEntry, bytes: ByteArray): Boolean =
        sha256Hex(bytes).equals(entry.sha256, ignoreCase = true)
}
