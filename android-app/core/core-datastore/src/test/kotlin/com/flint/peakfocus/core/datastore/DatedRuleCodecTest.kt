package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.model.StorageFailure
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A dated one-time schedule is the record's 12th field, written ONLY when it exists. Existing
 * 10/11-field records keep their exact shape, an older build that meets a dated row fails closed
 * (future/unavailable) instead of dropping the date, and strict decoding refuses any dated row
 * that could be read as a weekly window.
 */
class DatedRuleCodecTest {

    private val dated = BlockRule(
        id = "one",
        name = "One time",
        targets = BlockTargets(apps = setOf(AppRef("com.instagram.android", "Instagram"))),
        schedule = Schedule(startMinuteOfDay = 540, endMinuteOfDay = 660, date = LocalDate.parse("2026-11-23")),
        breakLevel = BreakLevel.HARDCORE,
    )

    private val weekly = dated.copy(id = "week", schedule = Schedule(setOf(1, 5), 540, 660))
    private val manual = dated.copy(id = "manual", schedule = null)

    private fun corrupting(block: () -> Unit): StorageFailure? = try {
        block(); null
    } catch (failure: StorageUnavailableException) {
        failure.reason
    }

    @Test
    fun `dated rule round trips through the 12th ISO date field`() {
        val encoded = PrefsCodec.encodeRules(listOf(dated))
        assertEquals(12, encoded.split('|').size)
        assertEquals("2026-11-23", encoded.split('|').last())
        assertEquals(listOf(dated), PrefsCodec.decodeRules(encoded))
        assertEquals(listOf(dated), StrictPrefsCodec.rules(encoded))
    }

    @Test
    fun `recurring and manual records keep their eleven field shape`() {
        for (rule in listOf(weekly, manual)) {
            val encoded = PrefsCodec.encodeRules(listOf(rule))
            assertEquals("${rule.id} keeps 11 fields", 11, encoded.split('|').size)
            assertEquals(listOf(rule), PrefsCodec.decodeRules(encoded))
            assertEquals(listOf(rule), StrictPrefsCodec.rules(encoded))
        }
    }
    @Test
    fun `Overnight dated rule round trips with its empty weekday gate`() {
        val overnight = dated.copy(schedule = dated.schedule!!.copy(startMinuteOfDay = 22 * 60, endMinuteOfDay = 6 * 60))
        val encoded = PrefsCodec.encodeRules(listOf(overnight))
        assertEquals(overnight, PrefsCodec.decodeRules(encoded).single())
        assertEquals(overnight, StrictPrefsCodec.rules(encoded).single())
    }

    @Test
    fun `an older build meeting a dated row reports a future store instead of dropping it`() {
        // 13 fields is what a LATER schema would write; the current strict reader must fail closed.
        val thirteen = PrefsCodec.encodeRules(listOf(dated)) + "|extra"
        assertEquals(StorageFailure.UNSUPPORTED_VERSION, corrupting { StrictPrefsCodec.rules(thirteen) })
    }

    @Test
    fun `strict decoding refuses a dated row that could read as a weekly window`() {
        val fields = PrefsCodec.encodeRules(listOf(dated)).split('|')
        val withDays = fields.toMutableList().apply { this[7] = "1,5" }.joinToString("|")
        assertEquals(StorageFailure.CORRUPT, corrupting { StrictPrefsCodec.rules(withDays) })
        val noWindow = fields.toMutableList().apply { this[8] = "-1"; this[9] = "-1" }.joinToString("|")
        assertEquals(StorageFailure.CORRUPT, corrupting { StrictPrefsCodec.rules(noWindow) })
        val zeroLength = fields.toMutableList().apply { this[9] = this[8] }.joinToString("|")
        assertEquals(StorageFailure.CORRUPT, corrupting { StrictPrefsCodec.rules(zeroLength) })
    }

    @Test
    fun `strict decoding refuses a malformed 12th field`() {
        val fields = PrefsCodec.encodeRules(listOf(dated)).split('|')
        for (bad in listOf("2026-13-40", "not-a-date", "", "2026-11-23T09:00")) {
            val encoded = fields.toMutableList().apply { this[11] = bad }.joinToString("|")
            assertEquals(bad, StorageFailure.CORRUPT, corrupting { StrictPrefsCodec.rules(encoded) })
        }
    }

    @Test
    fun `lenient decoding drops an undecodable date rather than inventing a weekly rule`() {
        val fields = PrefsCodec.encodeRules(listOf(dated)).split('|')
        val encoded = fields.toMutableList().apply { this[11] = "not-a-date" }.joinToString("|")
        val decoded = PrefsCodec.decodeRules(encoded).single()
        assertNull(decoded.schedule?.date)
        // The days are empty, so this damaged record is a "every day" window, never the Friday
        // pattern a weekly interpretation would invent.
        assertTrue(decoded.schedule!!.daysOfWeek.isEmpty())
    }
}
