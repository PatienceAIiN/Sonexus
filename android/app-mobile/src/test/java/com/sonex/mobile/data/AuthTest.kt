package com.sonex.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthValidatorTest {

    @Test fun accepts_normal_emails() {
        assertNull(AuthValidator.emailError("user@example.com"))
        assertNull(AuthValidator.emailError("first.last+tag@sub.domain.co"))
        assertNull(AuthValidator.emailError("  padded@example.com  ".trim()))
    }

    @Test fun rejects_blank_email() {
        assertEquals("Email is required", AuthValidator.emailError(""))
        assertEquals("Email is required", AuthValidator.emailError("   "))
    }

    @Test fun rejects_malformed_emails() {
        assertNotNull(AuthValidator.emailError("no-at-sign"))
        assertNotNull(AuthValidator.emailError("two@@example.com"))
        assertNotNull(AuthValidator.emailError("user@nodot"))
        assertNotNull(AuthValidator.emailError("@example.com"))
        assertNotNull(AuthValidator.emailError("user@.com"))
    }

    @Test fun rejects_empty_password() {
        assertEquals("Password is required", AuthValidator.passwordError(""))
    }

    @Test fun rejects_short_password() {
        assertNotNull(AuthValidator.passwordError("short12"))
        assertNull(AuthValidator.passwordError("longenough8"))
    }
}

class PasswordHasherTest {

    @Test fun same_password_same_salt_matches() {
        val salt = PasswordHasher.newSalt()
        assertTrue(PasswordHasher.verify("hunter2hunter2".toCharArray(), salt,
            PasswordHasher.hash("hunter2hunter2".toCharArray(), salt)))
    }

    @Test fun wrong_password_fails() {
        val salt = PasswordHasher.newSalt()
        val stored = PasswordHasher.hash("correct-horse".toCharArray(), salt)
        assertFalse(PasswordHasher.verify("battery-staple".toCharArray(), salt, stored))
    }

    @Test fun same_password_different_salt_differs() {
        val pw = "same-password".toCharArray()
        val a = PasswordHasher.hash(pw.copyOf(), PasswordHasher.newSalt())
        val b = PasswordHasher.hash(pw.copyOf(), PasswordHasher.newSalt())
        assertFalse("salting must make hashes unique", a.contentEquals(b))
    }

    @Test fun salts_are_random_and_sized() {
        val a = PasswordHasher.newSalt()
        val b = PasswordHasher.newSalt()
        assertEquals(16, a.size)
        assertFalse(a.contentEquals(b))
    }

    @Test fun constant_time_equals_handles_lengths_and_content() {
        assertTrue(PasswordHasher.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3)))
        assertFalse(PasswordHasher.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2)))
        assertFalse(PasswordHasher.constantTimeEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 4)))
    }

    @Test fun unicode_passwords_roundtrip() {
        val salt = PasswordHasher.newSalt()
        val pw = "pässwörd-日本語-🎧".toCharArray()
        assertTrue(PasswordHasher.verify(pw.copyOf(), salt, PasswordHasher.hash(pw.copyOf(), salt)))
    }
}
