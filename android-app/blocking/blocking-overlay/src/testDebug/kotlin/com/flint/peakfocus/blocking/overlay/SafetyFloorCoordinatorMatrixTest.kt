package com.flint.peakfocus.blocking.overlay

import android.content.Context
import android.os.Looper
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.datastore.FlintPreferences
import com.flint.peakfocus.core.model.ActiveRulesHolder
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.BreakLevel
import com.flint.peakfocus.core.model.OpenCountState
import com.flint.peakfocus.core.model.OpenLimit
import com.flint.peakfocus.core.model.Schedule
import com.flint.peakfocus.feature.blockscreen.BlockScreenReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Audit R2 regression matrix through the real [BlockScreenCoordinator] — the decision point both
 * detectors share (Path A: FlintAccessibilityService.evaluateForeground; Path B:
 * UsageStatsForegroundService.tick → PathBBlockHandoff.onForegroundPolled). Every floor surface
 * (static OEM dialer/emergency/Settings/permission/Flint, plus *runtime* custom default dialer
 * and launcher, which the pure engine cannot know) is checked against every ordinary cause
 * (rule incl. allow-list/schedule/Sleep/Hardcore, daily Time Limit, Open Limit), each next to an
 * ordinary control app with the identical configuration that IS blocked — so a passing floor
 * case can never be a dead cause. The detectors' own early returns call [isSafetyFloor]; the
 * coordinator re-applies the floor regardless (defense in depth), which is what is exercised.
 *
 * What this does not prove: real Accessibility/UsageStats event delivery or an on-device shield.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SafetyFloorCoordinatorMatrixTest {

    private val app: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val attached = mutableListOf<View>()
    private lateinit var coordinator: BlockScreenCoordinator

    private val self get() = app.packageName
    private val customDialer = "com.truecaller"
    private val customHome = "com.teslacoilsw.launcher"
    private val control = "com.instagram.android"
    private val other = "com.example.elsewhere"

    private val floor by lazy {
        listOf(
            self,
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.emergency",
            "com.android.settings",
            "com.google.android.permissioncontroller",
            "com.google.android.packageinstaller", // PC-51: uninstall UI reachable, no guard
            "com.android.systemui",
            customDialer,
            customHome,
        )
    }

    @Before fun setUp() {
        BlockExemptions.clearAll()
        coordinator = BlockScreenCoordinator(
            context = app,
            scope = scope,
            surfaceHost = object : BlockScreenCoordinator.SurfaceHost {
                override fun attach(view: View): Boolean = attached.add(view)
                override fun detach(view: View) { attached.remove(view) }
                override fun goHome() = Unit
            },
            safetyFloorGate = SafetyFloorGate(
                selfPackage = app.packageName,
                resolveRuntime = { mapOf("default-dialer" to setOf(customDialer), "home" to setOf(customHome)) },
                nowElapsedMs = { 0L },
            ),
        )
    }

    @After fun tearDown() {
        ActiveRulesHolder.publish(TEST_SOURCE, emptyList())
        BlockExemptions.clearAll()
        // FlintPreferences is one process-wide DataStore: undo every write so no test depends
        // on another's limits or counts (JUnit method order is not a contract).
        runBlocking {
            val limits = FlintPreferences.limits(app)
            (floor + control + listOf(COUNTED, SENTINEL)).forEach { limits.clearOpenLimit(it) }
            FlintPreferences.focusState(app).setOpenCounts(OpenCountState())
        }
        scope.cancel()
    }

    @Test fun `detector short-circuit recognises every floor surface, static and runtime`() {
        for (pkg in floor) assertTrue(pkg, coordinator.isSafetyFloor(pkg))
        assertEquals(false, coordinator.isSafetyFloor(control))
    }

    @Test fun `rule causes never shield a floor surface`() {
        val now = System.currentTimeMillis()
        val rules = listOf(
            rule("block", BreakLevel.EASY, allowList = false),
            rule("allow", BreakLevel.HARDER, allowList = true),
            rule("sleep", BreakLevel.HARDER, allowList = true, schedule = Schedule(startMinuteOfDay = 0, endMinuteOfDay = 1439)),
            rule("hardcore", BreakLevel.HARDCORE, allowList = false),
        )
        for (r in rules) {
            val cause = ruleBlockCause(r, now, 0L)
            assertEquals("${r.id} live on control", cause, coordinator.evaluateBlockCause(other, control, cause, timeLimitSpent = false))
            for (pkg in floor) {
                assertNull("${r.id} on $pkg", coordinator.evaluateBlockCause(other, pkg, cause, timeLimitSpent = false))
            }
        }
    }

    @Test fun `daily time limit never shields a floor surface`() {
        assertEquals(
            BlockScreenReason.TIME_LIMIT,
            coordinator.evaluateBlockCause(other, control, ruleCause = null, timeLimitSpent = true)?.reason,
        )
        for (pkg in floor) {
            assertNull(pkg, coordinator.evaluateBlockCause(other, pkg, ruleCause = null, timeLimitSpent = true))
        }
    }

    @Test fun `open limit never shields a floor surface, including the Path B Allow route`() {
        // Legacy/odd stored data: zero-open limits on the control and on every floor surface.
        val limits = (floor + control + SENTINEL).map { OpenLimit(it, dailyOpens = 0, breakLevel = BreakLevel.HARDCORE) }
        runBlocking { limits.forEach { FlintPreferences.limits(app).setOpenLimit(it) } }
        // Poll a sentinel, not the control, so waiting never records opens for the control.
        awaitTrue("open limits snapshot loaded") {
            coordinator.evaluateBlockCause(other, SENTINEL, null, false)?.reason == BlockScreenReason.OPEN_LIMIT
        }
        assertEquals(BlockScreenReason.OPEN_LIMIT, coordinator.evaluateBlockCause(other, control, null, false)?.reason)
        for (pkg in floor) {
            // Path B routes floor surfaces as Verdict.Allow (null rule cause, no budget).
            assertNull(pkg, coordinator.evaluateBlockCause(other, pkg, null, timeLimitSpent = false))
            assertNull("re-entry $pkg", coordinator.evaluateBlockCause(pkg, pkg, null, timeLimitSpent = false))
        }
    }

    @Test fun `a non-zero open limit on a custom dialer never trips after repeated opens`() {
        runBlocking { FlintPreferences.limits(app).setOpenLimit(OpenLimit(customDialer, dailyOpens = 1)) }
        runBlocking { FlintPreferences.limits(app).setOpenLimit(OpenLimit(COUNTED, dailyOpens = 1)) }
        // The ordinary app with the same limit is blocked on its second open (proves it is live).
        awaitTrue("limit loaded") {
            coordinator.evaluateBlockCause(other, COUNTED, null, false)
            coordinator.evaluateBlockCause(other, COUNTED, null, false)?.reason == BlockScreenReason.OPEN_LIMIT
        }
        repeat(5) {
            assertNull(coordinator.evaluateBlockCause(other, customDialer, null, false))
            assertNull(coordinator.evaluateBlockCause(customDialer, other, null, false))
        }
    }

    @Test fun `revalidation drops ordinary displayed causes but keeps the uninstall guard`() {
        val now = System.currentTimeMillis()
        ActiveRulesHolder.publish(TEST_SOURCE, listOf(rule("hardcore", BreakLevel.HARDCORE, allowList = true)))
        val displayed = ruleBlockCause(ActiveRulesHolder.rules.first { it.id == "hardcore" }, now, 0L)
        // No displayed cause passed: the rule itself must re-derive the block for the control.
        assertEquals(BlockScreenReason.DEEP_FOCUS, coordinator.currentBlockCause(control, null)?.reason)
        for (pkg in floor) assertNull(pkg, coordinator.currentBlockCause(pkg, displayed))
        val guard = BlockCause(BlockScreenReason.UNINSTALL_GUARD, BreakLevel.HARDCORE, endsAtEpochMs = null)
        assertEquals(guard, coordinator.currentBlockCause("com.android.settings", guard))
    }

    @Test fun `showBlocked never mounts an ordinary shield on a floor surface`() {
        val cause = BlockCause(BlockScreenReason.MANUAL_SESSION, BreakLevel.HARDCORE, endsAtEpochMs = null)
        for (pkg in floor) {
            coordinator.showBlocked(pkg, null, cause)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(pkg, attached.isEmpty())
        }
        coordinator.showBlocked(control, null, cause)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, attached.size)
        coordinator.hideIfShown()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `a floor show never reveals another app's shield`() {
        val cause = BlockCause(BlockScreenReason.MANUAL_SESSION, BreakLevel.HARDCORE, endsAtEpochMs = null)
        coordinator.showBlocked(control, null, cause)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, attached.size)
        coordinator.showBlocked(customDialer, null, cause)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("control's shield stays mounted", 1, attached.size)
        coordinator.hideIfShown()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun `the uninstall guard still mounts on Settings`() {
        val guard = BlockCause(BlockScreenReason.UNINSTALL_GUARD, BreakLevel.HARDCORE, endsAtEpochMs = null)
        coordinator.showBlocked("com.android.settings", null, guard)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, attached.size)
        coordinator.hideIfShown()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun rule(id: String, level: BreakLevel, allowList: Boolean, schedule: Schedule? = null) = BlockRule(
        id = id,
        name = id,
        targets = BlockTargets(
            apps = if (allowList) setOf(AppRef("com.spotify.music")) else (floor + control).map { AppRef(it) }.toSet(),
            allowListMode = allowList,
        ),
        schedule = schedule,
        breakLevel = level,
    )

    private fun awaitTrue(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(20L)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private companion object {
        const val TEST_SOURCE = "safety-floor-test"
        const val COUNTED = "com.example.counted"
        const val SENTINEL = "com.example.sentinel"
    }
}
