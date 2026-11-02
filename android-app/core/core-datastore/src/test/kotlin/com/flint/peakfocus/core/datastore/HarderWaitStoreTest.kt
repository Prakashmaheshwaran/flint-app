package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class HarderWaitStoreTest {
    @Test fun `PC-21 literal v1 pending wait fixture round trips with exact revisions and scope`() {
        val fixture = "H|1\nC|rule:work|1793181600000|2\nP|DELETE|1793181600000|10|boot|900000\nO|rule:work|1793181600000|revision-3\nT|app:a"
        val value = HarderWaitCodec.decode(fixture)
        assertEquals(fixture, HarderWaitCodec.encode(value))
        assertEquals(2, value.counts.values.single())
        assertEquals(899_999L, HarderWaitPolicy().remainingMs(value.pending!!, AccessClockStamp(0, 11, "boot")))
        assertNull(HarderWaitPolicy().remainingMs(value.pending!!, AccessClockStamp(0, 11, "new-boot")))
    }

    @Test fun `PC-71 malformed unknown duplicate and oversized wait records are unavailable`() {
        val invalid = listOf("H|2", "H|1\nC|rule:a|day|0\nC|rule:a|day|1", "H|1\nC|rule:a|day|-1",
            "H|1\nP|DELETE|1|1|boot|60000", "H|1\nP|DELETE|1|1|boot|1\nO|rule:a|day|r",
            "H|1\nT|broken%xx", "H|1\nC|rule:a|day|1\n", "H|1\n" + "x".repeat(4_194_304))
        invalid.forEach { fixture ->
            try { HarderWaitCodec.decode(fixture); fail("Accepted malformed ledger") }
            catch (_: StorageUnavailableException) { }
        }
    }

    @Test fun `PC-70 literal current ten and eleven field rules preserve ids rights and targets`() {
        val old10 = "legacy|Work|true|HARDCORE|false|a~App||1,2,3,4,5|540|660"
        val old11 = "$old10|-1"
        for (fixture in listOf(old10, old11)) {
            val rule = StrictPrefsCodec.rules(fixture).single()
            assertEquals("legacy", rule.id); assertEquals(BreakLevel.HARDCORE, rule.breakLevel)
            assertEquals(setOf(AppRef("a", "App")), rule.targets.apps)
            assertEquals(rule, StrictPrefsCodec.rules(PrefsCodec.encodeRules(listOf(rule))).single())
        }
    }

    @Test fun `PC-70 tierless and explicit-tier limits agree between strict and compatibility decoders`() {
        val old = "app~30"; val current = "app~30~HARDCORE"
        assertEquals(TimeLimit("app", 30, BreakLevel.HARDCORE), StrictPrefsCodec.timeLimits(old, BreakLevel.HARDCORE).single())
        assertEquals(current, PrefsCodec.encodeTimeLimits(StrictPrefsCodec.timeLimits(old, BreakLevel.HARDCORE)))
        assertEquals(PrefsCodec.decodeTimeLimits(current), StrictPrefsCodec.timeLimits(current))
        try { StrictPrefsCodec.timeLimits("app~30~unknown"); fail("Unknown tier defaulted") }
        catch (cause: StorageUnavailableException) { assertEquals(StorageFailure.UNSUPPORTED_VERSION, cause.reason) }
    }

    @Test fun `PC-71 future Time Limit row extension is unsupported rather than corrupt`() {
        try { StrictPrefsCodec.timeLimits("app~30~HARDCORE~UTC~future"); fail("Future row accepted") }
        catch (cause: StorageUnavailableException) { assertEquals(StorageFailure.UNSUPPORTED_VERSION, cause.reason) }
        assertEquals(StorageFailure.CORRUPT, editorPublicationFailure(androidx.datastore.core.CorruptionException("bad file")))
    }
}
