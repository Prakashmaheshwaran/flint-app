package com.flint.peakfocus.feature.settings.applock

/**
 * One persisted PIN verification record: algorithm cost, per-save random salt and derived hash.
 * The PIN itself is never stored. Instances are immutable copies of the caller's arrays.
 */
class AppPinRecord(
    val iterations: Int,
    salt: ByteArray,
    hash: ByteArray,
) {

    /** Random salt used for this record (defensively copied). */
    val salt: ByteArray = salt.copyOf()

    /** PBKDF2 output (defensively copied). */
    val hash: ByteArray = hash.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AppPinRecord) return false
        return iterations == other.iterations &&
            salt.contentEquals(other.salt) &&
            hash.contentEquals(other.hash)
    }

    override fun hashCode(): Int {
        var result = iterations
        result = 31 * result + salt.contentHashCode()
        result = 31 * result + hash.contentHashCode()
        return result
    }

    /** Never prints salt or hash: this string can reach logs, crash reports or test output. */
    override fun toString(): String = "AppPinRecord(iterations=$iterations, salt=<redacted>, hash=<redacted>)"
}

/**
 * Result of reading the persisted record.
 *
 * [Unreadable] is fail-closed: a PIN exists but cannot be verified (for example a newer schema or
 * a corrupted preferences value). Per ADR-012 §12 the stored bytes must never be treated as
 * "no PIN" and must never be deleted or overwritten automatically.
 */
sealed interface AppPinRecordRead {
    /** No PIN has ever been saved. */
    object Absent : AppPinRecordRead

    /** A well-formed record was decoded. */
    data class Valid(val record: AppPinRecord) : AppPinRecordRead

    /** Bytes are present but are not a record this build can verify. [reason] is diagnostics only. */
    data class Unreadable(val reason: String) : AppPinRecordRead
}

/**
 * Versioned single-string codec for [AppPinRecord]:
 *
 * `v1$pbkdf2-hmac-sha256$<iterations>$<salt lowercase hex>$<hash lowercase hex>`
 *
 * Hex is written by hand (no `java.util.Base64`, which is API 26+). Anything that is not a
 * canonical v1 record decodes to [AppPinRecordRead.Unreadable]; a `null` raw value means absent.
 */
object AppPinRecordCodec {

    private const val VERSION = "v1"
    private const val SEPARATOR = '$'
    private const val FIELD_COUNT = 5
    private const val MAX_ITERATION_DIGITS = 7

    fun encode(record: AppPinRecord): String = buildString {
        append(VERSION)
        append(SEPARATOR)
        append(AppPinHasher.ALGORITHM)
        append(SEPARATOR)
        append(record.iterations)
        append(SEPARATOR)
        append(hexEncode(record.salt))
        append(SEPARATOR)
        append(hexEncode(record.hash))
    }

    fun decode(raw: String?): AppPinRecordRead {
        if (raw == null) return AppPinRecordRead.Absent
        val fields = raw.split(SEPARATOR)
        if (fields.size != FIELD_COUNT) return AppPinRecordRead.Unreadable("unexpected field count")
        if (fields[0] != VERSION) return AppPinRecordRead.Unreadable("unsupported version")
        if (fields[1] != AppPinHasher.ALGORITHM) return AppPinRecordRead.Unreadable("unsupported algorithm")

        val iterations = parseCanonicalInt(fields[2])
            ?: return AppPinRecordRead.Unreadable("invalid iteration count")
        if (iterations !in AppPinHasher.MIN_ITERATIONS..AppPinHasher.MAX_ITERATIONS) {
            return AppPinRecordRead.Unreadable("iteration count out of range")
        }

        val salt = parseHex(fields[3]) ?: return AppPinRecordRead.Unreadable("invalid salt encoding")
        if (salt.size != AppPinHasher.SALT_BYTES) {
            return AppPinRecordRead.Unreadable("invalid salt length")
        }

        val hash = parseHex(fields[4]) ?: return AppPinRecordRead.Unreadable("invalid hash encoding")
        if (hash.size != AppPinHasher.HASH_BYTES) {
            return AppPinRecordRead.Unreadable("invalid hash length")
        }

        return AppPinRecordRead.Valid(AppPinRecord(iterations, salt, hash))
    }

    /** Canonical decimal only: ASCII digits, no sign, no leading zero. */
    private fun parseCanonicalInt(value: String): Int? {
        if (value.isEmpty() || value.length > MAX_ITERATION_DIGITS) return null
        if (value.length > 1 && value[0] == '0') return null
        if (value.any { it !in '0'..'9' }) return null
        return value.toIntOrNull()
    }

    private fun hexEncode(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        val out = CharArray(bytes.size * 2)
        for (index in bytes.indices) {
            val value = bytes[index].toInt() and 0xFF
            out[2 * index] = digits[value ushr 4]
            out[2 * index + 1] = digits[value and 0x0F]
        }
        return String(out)
    }

    /** Accepts either case; encoding is always lowercase. */
    private fun parseHex(value: String): ByteArray? {
        if (value.isEmpty() || value.length % 2 != 0) return null
        val out = ByteArray(value.length / 2)
        for (index in out.indices) {
            val high = hexDigit(value[2 * index]) ?: return null
            val low = hexDigit(value[2 * index + 1]) ?: return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }

    private fun hexDigit(char: Char): Int? = when (char) {
        in '0'..'9' -> char - '0'
        in 'a'..'f' -> char - 'a' + 10
        in 'A'..'F' -> char - 'A' + 10
        else -> null
    }
}
