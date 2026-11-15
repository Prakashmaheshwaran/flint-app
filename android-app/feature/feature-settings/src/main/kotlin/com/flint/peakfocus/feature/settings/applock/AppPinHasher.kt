package com.flint.peakfocus.feature.settings.applock

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Slow, salted PIN hashing for the optional app-open PIN (F03 App-open PIN, Android).
 *
 * The iOS app stores a single fast SHA-256 of `pin + salt`. Android deliberately hardens this
 * with PBKDF2-HMAC-SHA256 at a high iteration count so an offline guess of a 4-6 digit PIN from
 * a dumped preferences file is expensive instead of instantaneous. The algorithm identifier is
 * persisted with every record ([AppPinRecord]) so a later change of cost can migrate instead of
 * silently reinterpreting old bytes.
 *
 * Only [javax.crypto] and the JDK/Android standard library are used: `java.util.Base64` and
 * `SecretKeyFactory("PBKDF2WithHmacSHA256")` are API 26+, while this module supports API 23.
 * A hand-written PBKDF2 over [Mac] works on every supported API level.
 */
object AppPinHasher {

    /** Versioned algorithm identity persisted inside every record. */
    const val ALGORITHM = "pbkdf2-hmac-sha256"

    /** Cost used for every new record. */
    const val ITERATIONS = 120_000

    /** Random salt length per record. A fresh salt is generated on every save. */
    const val SALT_BYTES = 16

    /** Derived key length (SHA-256 output). */
    const val HASH_BYTES = 32

    /**
     * Accepted iteration range when decoding a persisted record. Values outside this window are
     * treated as unreadable (fail closed) rather than recomputed at a cost the device cannot pay.
     */
    const val MIN_ITERATIONS = 10_000
    const val MAX_ITERATIONS = 5_000_000

    private const val HMAC_SHA256 = "HmacSHA256"

    /**
     * PBKDF2 (RFC 8018 §5.2) with HMAC-SHA256 as the pseudorandom function.
     *
     * Implemented directly over [Mac] so it runs on API 23. [password] must be non-empty:
     * [SecretKeySpec] rejects an empty key and callers already guarantee a non-empty PIN.
     * Zeroing [password] is the caller's responsibility ([hash] does it).
     */
    fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLength: Int,
    ): ByteArray {
        require(password.isNotEmpty()) { "password must not be empty" }
        require(iterations > 0) { "iterations must be positive" }
        require(keyLength > 0) { "key length must be positive" }

        val mac = Mac.getInstance(HMAC_SHA256)
        mac.init(SecretKeySpec(password, HMAC_SHA256))
        val blockLength = mac.macLength
        val derived = ByteArray(keyLength)
        var offset = 0
        var blockIndex = 1
        while (offset < keyLength) {
            // U1 = PRF(password, salt || INT_32_BE(blockIndex))
            mac.update(salt)
            mac.update(
                byteArrayOf(
                    (blockIndex ushr 24).toByte(),
                    (blockIndex ushr 16).toByte(),
                    (blockIndex ushr 8).toByte(),
                    blockIndex.toByte(),
                ),
            )
            var u = mac.doFinal()
            val t = u.copyOf()
            for (round in 2..iterations) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            u.fill(0)
            val take = minOf(blockLength, keyLength - offset)
            System.arraycopy(t, 0, derived, offset, take)
            t.fill(0)
            offset += take
            blockIndex++
        }
        return derived
    }

    /**
     * Hashes the exact UTF-8 bytes of [pin] (never trimmed or normalized) at [iterations].
     * The temporary password bytes are zeroed before returning.
     */
    fun hash(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        val password = pin.toByteArray(Charsets.UTF_8)
        try {
            return pbkdf2HmacSha256(password, salt, iterations, HASH_BYTES)
        } finally {
            password.fill(0)
        }
    }

    /** Constant-time verification of [pin] against a decoded [record]. */
    fun matches(pin: String, record: AppPinRecord): Boolean {
        val candidate = hash(pin, record.salt, record.iterations)
        return try {
            MessageDigest.isEqual(candidate, record.hash)
        } finally {
            candidate.fill(0)
        }
    }

    /** New record for [pin] with a fresh random salt at the current cost. */
    fun newRecord(pin: String, random: SecureRandom = SecureRandom()): AppPinRecord {
        val salt = ByteArray(SALT_BYTES)
        random.nextBytes(salt)
        return AppPinRecord(iterations = ITERATIONS, salt = salt, hash = hash(pin, salt, ITERATIONS))
    }
}
