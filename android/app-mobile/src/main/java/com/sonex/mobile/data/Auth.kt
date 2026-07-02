package com.sonex.mobile.data

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Phase 1 auth: a local, on-device account. There is no SoNex server yet, so
 * the first sign-in creates the account and later sign-ins must match it.
 * Validation + hashing are framework-free so they run in plain JVM tests.
 * When the portal ships, this swaps for a server call behind the same API.
 */
object AuthValidator {
    private val emailRegex = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    const val MIN_PASSWORD_LENGTH = 8

    fun emailError(email: String): String? = when {
        email.isBlank() -> "Email is required"
        !emailRegex.matches(email.trim()) -> "Enter a valid email address"
        else -> null
    }

    fun passwordError(password: String): String? = when {
        password.isEmpty() -> "Password is required"
        password.length < MIN_PASSWORD_LENGTH -> "Password must be at least $MIN_PASSWORD_LENGTH characters"
        else -> null
    }
}

/** PBKDF2-based password hashing. Pure JVM — no Android dependencies. */
object PasswordHasher {
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    fun hash(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun verify(password: CharArray, salt: ByteArray, expectedHash: ByteArray): Boolean =
        constantTimeEquals(hash(password, salt), expectedHash)

    /** Compare without early exit so timing doesn't leak match length. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}

/** Result of a sign-in attempt against the local account store. */
sealed class AuthResult {
    /** Account created (first run) or credentials matched. */
    data class Success(val created: Boolean) : AuthResult()
    data class Failure(val message: String) : AuthResult()
}
