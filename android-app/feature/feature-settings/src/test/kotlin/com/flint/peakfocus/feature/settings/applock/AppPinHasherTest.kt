package com.flint.peakfocus.feature.settings.applock

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/** RFC 7914 §11 vectors, a JDK oracle cross-check and the rules the controller relies on. */
class AppPinHasherTest {

    @Test fun `matches RFC 7914 section 11 PBKDF2-HMAC-SHA256 vectors`() {
        val first = AppPinHasher.pbkdf2HmacSha256(
            password = "passwd".toByteArray(),
            salt = "salt".toByteArray(),
            iterations = 1,
            keyLength = 64,
        )
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            hex(first),
        )

        // RFC 7914 §11: first 32 bytes are 4d dc d8 f6 0b 98 be 21 ...; the brief's paste of this
        // vector had an extra "d" ("4dddcd8f"), which the JDK oracle below contradicts.
        val second = AppPinHasher.pbkdf2HmacSha256(
            password = "Password".toByteArray(),
            salt = "NaCl".toByteArray(),
            iterations = 80_000,
            keyLength = 64,
        )
        assertEquals(
            "4ddcd8f60b98be21830cee5ef22701f9641a4418d04c0414aeff08876b34ab56" +
                "a1d425a1225833549adb841b51c9b3176a272bdebba1d078478f62b397f33c8d",
            hex(second),
        )
    }

    @Test fun `agrees with the JDK PBKDF2WithHmacSHA256 oracle`() {
        val random = SecureRandom()
        val cases = listOf(
            Triple("1234", 1_000, 32),
            Triple("000000", 500, 32),
            Triple("987654", 4_096, 32),
            Triple("4321", 2_500, 64),
        )
        for ((pin, iterations, keyLength) in cases) {
            val salt = ByteArray(AppPinHasher.SALT_BYTES).also(random::nextBytes)
            assertArrayEquals(
                "oracle mismatch for $iterations iterations and $keyLength bytes",
                jdkPbkdf2(pin, salt, iterations, keyLength),
                AppPinHasher.pbkdf2HmacSha256(pin.toByteArray(), salt, iterations, keyLength),
            )
        }
    }

    @Test fun `hash is deterministic per salt and iteration count and matches only the exact pin`() {
        val salt = ByteArray(AppPinHasher.SALT_BYTES) { it.toByte() }
        val record = AppPinRecord(
            iterations = AppPinHasher.MIN_ITERATIONS,
            salt = salt,
            hash = AppPinHasher.hash("1379", salt, AppPinHasher.MIN_ITERATIONS),
        )

        assertTrue(AppPinHasher.matches("1379", record))
        assertFalse(AppPinHasher.matches("1379 ", record))
        assertFalse(AppPinHasher.matches(" 1379", record))
        assertFalse(AppPinHasher.matches("1378", record))

        val otherSalt = ByteArray(AppPinHasher.SALT_BYTES) { (it + 1).toByte() }
        val otherRecord = AppPinRecord(
            iterations = AppPinHasher.MIN_ITERATIONS,
            salt = otherSalt,
            hash = AppPinHasher.hash("1379", otherSalt, AppPinHasher.MIN_ITERATIONS),
        )
        assertNotEquals(otherRecord.hash.toList(), record.hash.toList())
        // A hash computed with one salt must never verify against a record that carries another.
        val crossSalt = AppPinRecord(record.iterations, otherSalt, record.hash)
        assertFalse(AppPinHasher.matches("1379", crossSalt))
    }

    @Test fun `new record uses current cost with a fresh random salt and a 32 byte hash`() {
        val record = AppPinHasher.newRecord("0000")
        assertEquals(AppPinHasher.ITERATIONS, record.iterations)
        assertEquals(AppPinHasher.SALT_BYTES, record.salt.size)
        assertEquals(AppPinHasher.HASH_BYTES, record.hash.size)
        assertTrue(AppPinHasher.matches("0000", record))

        val another = AppPinHasher.newRecord("0000")
        assertNotEquals(record.salt.toList(), another.salt.toList())
    }

    @Test fun `pbkdf2 requires a non-empty password`() {
        assertThrows(IllegalArgumentException::class.java) {
            AppPinHasher.pbkdf2HmacSha256(ByteArray(0), ByteArray(16), 1_000, 32)
        }
    }

    private fun jdkPbkdf2(pin: String, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, keyLength * 8)
        try {
            return factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            for (byte in bytes) {
                val value = byte.toInt() and 0xFF
                append(digits[value ushr 4])
                append(digits[value and 0x0F])
            }
        }
    }
}
