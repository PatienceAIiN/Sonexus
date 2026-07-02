package com.sonex.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * SoNex server auth. Signup is a two-step flow: credentials first, then the
 * 6-digit code Brevo emails you. Password reset works the same way and the
 * server kills every old session the moment the password changes.
 */
object AuthApi {
    @Serializable private data class Credentials(val email: String, val password: String)
    @Serializable private data class VerifyIn(val email: String, val code: String)
    @Serializable private data class ForgotIn(val email: String)
    @Serializable private data class ResetIn(val email: String, val code: String, val new_password: String)
    @Serializable private data class Reply(
        val access_token: String = "",
        val detail: String = "",
        val pending: Boolean = false
    )

    private val json = Json { ignoreUnknownKeys = true }

    sealed class Result {
        /** Signed in — token in hand. */
        data class Success(val token: String) : Result()
        /** Server did something and wants the user to act (e.g. enter the code). */
        data class Info(val message: String) : Result()
        data class Failure(val message: String) : Result()
    }

    suspend fun login(base: String, email: String, password: String): Result =
        post("$base/v1/auth/login", json.encodeToString(Credentials.serializer(), Credentials(email, password)))

    suspend fun signup(base: String, email: String, password: String): Result =
        post("$base/v1/auth/signup", json.encodeToString(Credentials.serializer(), Credentials(email, password)))

    suspend fun verify(base: String, email: String, code: String): Result =
        post("$base/v1/auth/verify", json.encodeToString(VerifyIn.serializer(), VerifyIn(email, code)))

    suspend fun forgot(base: String, email: String): Result =
        post("$base/v1/auth/forgot", json.encodeToString(ForgotIn.serializer(), ForgotIn(email)))

    suspend fun reset(base: String, email: String, code: String, newPassword: String): Result =
        post("$base/v1/auth/reset", json.encodeToString(ResetIn.serializer(), ResetIn(email, code, newPassword)))

    private suspend fun post(url: String, body: String): Result = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 8_000; conn.readTimeout = 8_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            try {
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.use { it.readBytes().decodeToString() } ?: ""
                val reply = runCatching { json.decodeFromString(Reply.serializer(), text) }
                    .getOrDefault(Reply())
                when {
                    code in 200..299 && reply.access_token.isNotBlank() -> Result.Success(reply.access_token)
                    code in 200..299 -> Result.Info(reply.detail.ifBlank { "Done" })
                    else -> Result.Failure(reply.detail.ifBlank { friendlyAuth(code) })
                }
            } finally { conn.disconnect() }
        }.getOrElse { Result.Failure(ServerSync.friendlyNetwork(it)) }
    }

    private fun friendlyAuth(code: Int): String = when (code) {
        401 -> "Wrong email or password"
        403 -> "Email not verified — check your inbox for the code"
        409 -> "An account with this email already exists — try signing in"
        else -> ServerSync.friendlyHttp(code)
    }
}
