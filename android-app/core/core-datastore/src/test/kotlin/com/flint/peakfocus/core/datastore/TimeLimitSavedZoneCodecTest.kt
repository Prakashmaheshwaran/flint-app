package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class TimeLimitSavedZoneCodecTest {
    @Test fun PC_56_D5_savedZoneRoundTripsWithOwnedTier() {
        for (zone in listOf("UTC", "America/New_York", "Pacific/Auckland")) {
            val limit = TimeLimit("com.target", 30, BreakLevel.HARDCORE, zone)
            assertEquals(listOf(limit), StrictPrefsCodec.timeLimits(PrefsCodec.encodeTimeLimits(listOf(limit))))
        }
    }

    @Test fun PC_70_D5_twoAndThreeFieldLegacyRowsStayReadableWithoutInventingAZone() {
        assertEquals(listOf(TimeLimit("legacy", 30, BreakLevel.HARDCORE)),
            StrictPrefsCodec.timeLimits("legacy~30", BreakLevel.HARDCORE))
        assertEquals(listOf(TimeLimit("legacy", 30, BreakLevel.HARDER)),
            StrictPrefsCodec.timeLimits("legacy~30~HARDER", BreakLevel.HARDCORE))
    }

    @Test fun PC_71_D5_unknownEmptyAndMalformedSavedZonesAreUnavailable() {
        for (raw in listOf("app~30~HARDCORE~", "app~30~HARDCORE~not-a-zone", "app~30~HARDCORE~UTC%7Cbad")) {
            try { StrictPrefsCodec.timeLimits(raw); fail("malformed zone accepted") }
            catch (error: StorageUnavailableException) { assertEquals(StorageFailure.CORRUPT, error.reason) }
        }
        try { StrictPrefsCodec.timeLimits("app~30~HARDCORE~UTC~future"); fail("future schema accepted") }
        catch (error: StorageUnavailableException) { assertEquals(StorageFailure.UNSUPPORTED_VERSION, error.reason) }
    }

    @Test fun PC_56_D5_committingADraftPreservesTheSavedZoneAcrossTravel() {
        val saved = TimeLimit("app", 30, BreakLevel.HARDCORE, "America/New_York")
        val draft = TimeLimit("app", 45, BreakLevel.HARDCORE)
        assertEquals("America/New_York", draft.withSavedTimeLimitZone(saved, "Pacific/Auckland").zoneId)
        assertEquals("Pacific/Auckland", draft.withSavedTimeLimitZone(null, "Pacific/Auckland").zoneId)
    }
}
