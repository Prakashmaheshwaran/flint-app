package com.flint.peakfocus.feature.settings.applock

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The persisted record format: one canonical v1 string, unreadable anything else. */
class AppPinRecordCodecTest {

    private val valid = AppPinRecord(
        iterations = 120_000,
        salt = ByteArray(AppPinHasher.SALT_BYTES) { it.toByte() },
        hash = ByteArray(AppPinHasher.HASH_BYTES) { (it + 7).toByte() },
    )

    @Test fun `round trips the canonical record`() {
        val raw = AppPinRecordCodec.encode(valid)
        assertEquals(
            "v1${SEP}${AppPinHasher.ALGORITHM}${SEP}120000${SEP}${hex(valid.salt)}${SEP}${hex(valid.hash)}",
            raw,
        )
        assertTrue(raw.startsWith("v1${SEP}${AppPinHasher.ALGORITHM}${SEP}120000${SEP}"))
        assertTrue(raw.endsWith(hex(valid.hash)))
        assertEquals(AppPinRecordRead.Valid(valid), AppPinRecordCodec.decode(raw))
    }

    @Test fun `null decodes as absent`() {
        assertEquals(AppPinRecordRead.Absent, AppPinRecordCodec.decode(null))
    }

    @Test fun `every malformed or unknown shape is unreadable`() {
        val saltHex = "00".repeat(AppPinHasher.SALT_BYTES)
        val hashHex = "11".repeat(AppPinHasher.HASH_BYTES)
        val malformed = listOf(
            "",
            "not-a-record",
            // unknown version and unknown algorithm
            raw(version = "v2"),
            raw(algorithm = "sha-256"),
            raw(algorithm = "pbkdf2-hmac-sha1"),
            // iteration count: out of range, non-numeric, non-canonical
            raw(iterations = "9999"),
            raw(iterations = "5000001"),
            raw(iterations = "12000x"),
            raw(iterations = "0120000"),
            raw(iterations = ""),
            raw(iterations = "-120000"),
            // bad hex, wrong lengths, empty fields
            raw(salt = "zz" + saltHex.drop(2)),
            raw(salt = saltHex.drop(2)),
            raw(salt = saltHex + "00"),
            raw(hash = "zz" + hashHex.drop(2)),
            raw(hash = hashHex.drop(2)),
            raw(hash = hashHex + "00"),
            raw(salt = ""),
            raw(hash = ""),
            // extra fields, missing fields, trailing separator, stray whitespace
            "${raw()}${SEP}",
            "${raw()}${SEP}extra",
            raw().substringBeforeLast(SEP),
            " ${raw()}",
            "${raw()} ",
        )

        for (candidate in malformed) {
            val read = AppPinRecordCodec.decode(candidate)
            assertTrue(
                "expected unreadable for <$candidate> but was $read",
                read is AppPinRecordRead.Unreadable,
            )
            assertTrue((read as AppPinRecordRead.Unreadable).reason.isNotBlank())
        }
    }

    @Test fun `records are compared by content`() {
        val same = AppPinRecord(valid.iterations, valid.salt, valid.hash)
        assertEquals(valid, same)
        assertEquals(valid.hashCode(), same.hashCode())
        assertNotEquals(valid, AppPinRecord(valid.iterations + 1, valid.salt, valid.hash))
        assertNotEquals(valid, AppPinRecord(valid.iterations, ByteArray(16) { 9 }, valid.hash))
        assertNotEquals(valid, AppPinRecord(valid.iterations, valid.salt, ByteArray(32) { 9 }))
    }

    @Test fun `toString never prints the salt or hash`() {
        val text = valid.toString()
        assertTrue(text.contains("iterations=120000"))
        assertTrue(text.contains("redacted"))
        assertFalse("salt leaked: $text", text.contains(hex(valid.salt)))
        assertFalse("hash leaked: $text", text.contains(hex(valid.hash)))
        assertFalse("salt bytes leaked: $text", text.contains("00010203040506070809"))
    }

    @Test fun `record copies its byte arrays`() {
        val salt = ByteArray(AppPinHasher.SALT_BYTES) { it.toByte() }
        val hash = ByteArray(AppPinHasher.HASH_BYTES) { (it + 7).toByte() }
        val record = AppPinRecord(120_000, salt, hash)
        salt.fill(0)
        hash.fill(0)
        assertArrayEquals(ByteArray(AppPinHasher.SALT_BYTES) { it.toByte() }, record.salt)
        assertEquals(7.toByte(), record.hash[0])
        assertEquals(AppPinHasher.HASH_BYTES, record.hash.size)
    }

    @Test fun `uppercase hex is accepted but encoded lowercase`() {
        val read = AppPinRecordCodec.decode(raw(salt = "000102030405060708090A0B0C0D0E0F"))
        assertTrue(read is AppPinRecordRead.Valid)
        val encoded = AppPinRecordCodec.encode((read as AppPinRecordRead.Valid).record)
        assertEquals(encoded, encoded.lowercase())
        assertTrue(encoded.contains("000102030405060708090a0b0c0d0e0f${SEP}" + "11".repeat(32)))
    }

    private fun raw(
        version: String = "v1",
        algorithm: String = AppPinHasher.ALGORITHM,
        iterations: String = "120000",
        salt: String = "00".repeat(AppPinHasher.SALT_BYTES),
        hash: String = "11".repeat(AppPinHasher.HASH_BYTES),
    ): String = listOf(version, algorithm, iterations, salt, hash).joinToString(SEP)

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

    private companion object {
        const val SEP = "\u0024"
    }
}
