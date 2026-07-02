package com.sonex.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * SoNex server auth (`/v1/auth/signup`, `/v1/auth/login`). Used when the user
 * enters a server URL at sign-in; with no server the app keeps the on-device
 * account, staying fully offline.
 */
object AuthApi {
    @Serializable private data class Credentials(val email: String, val password: String)
    @Serializable private data class Token(val access_token: String)
    @Serializable private data class Error(val detail: String = "")

    private val json = Json { ignoreUnknownKeys = true }

    sealed class Result {
        data class Success(val token: String) : Result()
        data class Failure(val message: String) : Result()
    }

    suspend fun login(baseUrl: String, email: String, password: String): Result =
        post("$baseUrl/v1/auth/login", email, password)

    suspend fun signup(baseUrl: String, email: String, password: String): Result =
        post("$baseUrl/v1/auth/signup", email, password)

    private suspend fun post(url: String, email: String, password: String): Result =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000; conn.readTimeout = 8_000
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                try {
                    conn.outputStream.use {
                        it.write(json.encodeToString(Credentials.serializer(), Credentials(email, password)).toByteArray())
                    }
                    val code = conn.responseCode
                    if (code in 200..299) {
                        val body = conn.inputStream.use { it.readBytes().decodeToString() }
                        Result.Success(json.decodeFromString(Token.serializer(), body).access_token)
                    } else {
                        val body = conn.errorStream?.use { it.readBytes().decodeToString() } ?: ""
                        val detail = runCatching { json.decodeFromString(Error.serializer(), body).detail }
                            .getOrDefault("")
                        Result.Failure(detail.ifBlank { "Server error ($code)" })
                    }
                } finally { conn.disconnect() }
            }.getOrElse { Result.Failure("Can't reach server: ${it.message}") }
        }
}
