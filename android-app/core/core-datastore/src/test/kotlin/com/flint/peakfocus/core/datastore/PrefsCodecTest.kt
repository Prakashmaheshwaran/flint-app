package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.BreakRequestState
import com.flint.peakfocus.core.model.BreakSessionState
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.core.model.TimeLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsCodecTest {

    // MARK: escaping

    @Test
    fun escapeRoundTripsEveryDelimiterAndHostileInput() {
        val nasty = listOf(
            "%", "|", ",", "~", "\n",
            "%25", "%7C", "%2C", "%7E", "%0A", "%252C",
            "a|b,c~d%e\nf", "plain", "", "100% legit, no~kidding|honest\nreally",
        )
        for (raw in nasty) {
            assertEquals(raw, PrefsCodec.unescape(PrefsCodec.escape(raw)))
        }
    }

    @Test
    fun escapedAtomsContainNoLiveDelimiters() {
        val escaped = PrefsCodec.escape("a|b,c~d\ne")
        assertTrue("|" !in escaped)
        assertTrue("," !in escaped)
        assertTrue("~" !in escaped)
        assertTrue("\n" !in escaped)
    }

    // MARK: rules

    private val fullRule = BlockRule(
        id = "r-1",
        name = "Work hours | strict, very~much 100%\nno excuses",
        targets = BlockTargets(
            apps = setOf(
                AppRef("com.instagram.android", label = "Insta|gram, the~app"),
                AppRef("com.tiktok.android", label = null),
            ),
            domains = setOf(DomainRef("reddit.com"), DomainRef("news.ycombinator.com")),
            allowListMode = true,
        ),
        schedule = Schedule(daysOfWeek = setOf(1, 2, 3, 4, 5), startMinuteOfDay = 540, endMinuteOfDay = 1020),
        breakLevel = BreakLevel.HARDCORE,
        enabled = false,
    )

    @Test
    fun rulesRoundTripIncludingHostileNamesAndAllFields() {
        val simple = BlockRule(
            id = "r-2",
            name = "Blocklist",
            targets = BlockTargets(apps = setOf(AppRef("com.x"))),
        )
        val decoded = PrefsCodec.decodeRules(PrefsCodec.encodeRules(listOf(fullRule, simple)))
        assertEquals(listOf(fullRule, simple), decoded)
    }

    @Test
    fun ruleWithoutScheduleRoundTripsToNullSchedule() {
        val rule = fullRule.copy(schedule = null)
        val decoded = PrefsCodec.decodeRules(PrefsCodec.encodeRules(listOf(rule)))
        assertNull(decoded.single().schedule)
    }

    @Test
    fun scheduleWithEmptyDaysRoundTrips() {
        val rule = fullRule.copy(schedule = Schedule(startMinuteOfDay = 0, endMinuteOfDay = 60))
        val decoded = PrefsCodec.decodeRules(PrefsCodec.encodeRules(listOf(rule)))
        assertEquals(rule.schedule, decoded.single().schedule)
    }

    @Test
    fun blankAppLabelDecodesAsNull() {
        val rule = fullRule.copy(
            schedule = null,
            targets = BlockTargets(apps = setOf(AppRef("com.x", label = null))),
        )
        val decoded = PrefsCodec.decodeRules(PrefsCodec.encodeRules(listOf(rule)))
        assertNull(decoded.single().targets.apps.single().label)
    }

    @Test
    fun emptyRuleListRoundTrips() {
        assertEquals("", PrefsCodec.encodeRules(emptyList()))
        assertEquals(emptyList<BlockRule>(), PrefsCodec.decodeRules(""))
    }

    @Test
    fun malformedRuleLinesAreDroppedNotThrown() {
        val good = PrefsCodec.encodeRules(listOf(fullRule))
        val decoded = PrefsCodec.decodeRules("garbage|only|three\n$good\n\nid-but-nothing-else")
        assertEquals(listOf(fullRule), decoded)
    }

    @Test
    fun unknownBreakLevelDecodesToEasy() {
        val line = "r1|Focus|true|SUPERHARD|false||||-1|-1"
        val decoded = PrefsCodec.decodeRules(line)
        assertEquals(BreakLevel.EASY, decoded.single().breakLevel)
    }

    // MARK: limits

    @Test
    fun timeLimitsRoundTripAndDropMalformedLines() {
        val limits = listOf(TimeLimit("com.instagram.android", 45), TimeLimit("we|ird~pkg,name", 1))
        assertEquals(limits, PrefsCodec.decodeTimeLimits(PrefsCodec.encodeTimeLimits(limits)))
        assertEquals(emptyList<TimeLimit>(), PrefsCodec.decodeTimeLimits("nonsense\npkg~notanumber\n~5"))
    }

    @Test
    fun openLimitsRoundTripWithBreakLevels() {
        val limits = listOf(
            OpenLimit("com.instagram.android", dailyOpens = 5, breakLevel = BreakLevel.HARDCORE),
            OpenLimit("com.tiktok.android", dailyOpens = 3),
        )
        assertEquals(limits, PrefsCodec.decodeOpenLimits(PrefsCodec.encodeOpenLimits(limits)))
    }

    @Test
    fun openLimitUnknownLevelFallsBackToEasyAndBadLinesDrop() {
        assertEquals(
            listOf(OpenLimit("com.x", 2, BreakLevel.EASY)),
            PrefsCodec.decodeOpenLimits("com.x~2~NOPE\ncom.y~NaN~EASY\nshort"),
        )
    }

    // MARK: open counts

    @Test
    fun openCountsRoundTrip() {
        val counts = mapOf("com.instagram.android" to 4, "we|ird~pkg" to 1)
        assertEquals(counts, PrefsCodec.decodeOpenCounts(PrefsCodec.encodeOpenCounts(counts)))
        assertEquals(emptyMap<String, Int>(), PrefsCodec.decodeOpenCounts(""))
    }

    // MARK: break session

    @Test
    fun breakSessionRoundTripsWithAndWithoutPending() {
        val idle = BreakSessionState(breaksTaken = 2)
        assertEquals(idle, PrefsCodec.decodeBreakSession(PrefsCodec.encodeBreakSession(idle)))

        val pending = BreakSessionState(
            breaksTaken = 1,
            pending = BreakRequestState(requestedAtEpochMs = 1_000L, effectiveAtEpochMs = 61_000L),
        )
        assertEquals(pending, PrefsCodec.decodeBreakSession(PrefsCodec.encodeBreakSession(pending)))
    }

    @Test
    fun garbageBreakSessionDecodesToDefault() {
        assertEquals(BreakSessionState(), PrefsCodec.decodeBreakSession("not-a-number~x~y"))
        assertEquals(BreakSessionState(breaksTaken = 3), PrefsCodec.decodeBreakSession("3~oops~1"))
        assertEquals(BreakSessionState(), PrefsCodec.decodeBreakSession(""))
    }

    // MARK: enum fallback

    @Test
    fun breakLevelDecoderHandlesAllValuesAndGarbage() {
        for (level in BreakLevel.entries) {
            assertEquals(level, PrefsCodec.decodeBreakLevel(level.name))
        }
        assertEquals(BreakLevel.EASY, PrefsCodec.decodeBreakLevel(null))
        assertEquals(BreakLevel.EASY, PrefsCodec.decodeBreakLevel("hardcore")) // case-sensitive by design
    }
}
