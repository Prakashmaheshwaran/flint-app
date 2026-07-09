package com.flint.peakfocus.feature.onboarding

import com.flint.peakfocus.blocking.resilience.HealthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupGuidanceTest {

    private fun status(
        accessibility: Boolean = true,
        usage: Boolean = true,
        overlay: Boolean = true,
        battery: Boolean = true,
    ) = HealthStatus(
        accessibilityEnabled = accessibility,
        usageAccessGranted = usage,
        overlayGranted = overlay,
        batteryExemptionGranted = battery,
    )

    // --- invariants across every grant combination ---

    @Test
    fun theCardClaimsBlockingIsOnExactlyWhenFlintCanEnforce() {
        allStatuses().forEach { s ->
            val plan = SetupGuidance.plan(s)
            assertEquals("enforcingNow for $s", s.canEnforce, plan.enforcingNow)
            if (s.canEnforce) {
                assertTrue(
                    "headline for $s must claim blocking is on, was: ${plan.headline}",
                    plan.headline.startsWith("Blocking is on"),
                )
            } else {
                assertEquals("Blocking is off", plan.headline)
            }
        }
    }

    @Test
    fun remainingStepsAreExactlyTheMissingGrants() {
        allStatuses().forEach { s ->
            val expected = buildSet {
                if (!s.accessibilityEnabled) add(SetupStep.ENABLE_ACCESSIBILITY)
                if (!s.usageAccessGranted) add(SetupStep.GRANT_USAGE_ACCESS)
                if (!s.overlayGranted) add(SetupStep.GRANT_OVERLAY)
                if (!s.batteryExemptionGranted) add(SetupStep.REQUEST_BATTERY_EXEMPTION)
            }
            val steps = SetupGuidance.plan(s).remainingSteps
            assertEquals("remaining steps for $s", expected, steps.toSet())
            assertEquals("no step may repeat for $s", steps.size, steps.toSet().size)
        }
    }

    @Test
    fun theNextStepIsAlwaysTheHeadOfTheRemainingSteps() {
        allStatuses().forEach { s ->
            val plan = SetupGuidance.plan(s)
            assertEquals("next step for $s", plan.remainingSteps.firstOrNull(), plan.nextStep)
        }
    }

    @Test
    fun everyPlanExplainsItself() {
        allStatuses().forEach { s ->
            assertTrue("body for $s must not be blank", SetupGuidance.plan(s).body.isNotBlank())
        }
    }

    @Test
    fun theBatteryCaveatAppearsWheneverTheExemptionIsMissingAndNeverOtherwise() {
        allStatuses().forEach { s ->
            val warns = SetupGuidance.plan(s).body.contains("not exempt from battery optimization")
            assertEquals("battery caveat for $s", !s.batteryExemptionGranted, warns)
        }
    }

    // --- the four states the card can be in ---

    @Test
    fun nothingIsLeftToDoWhenEveryGrantIsInPlace() {
        val plan = SetupGuidance.plan(status())
        assertTrue(plan.enforcingNow)
        assertEquals("Blocking is on", plan.headline)
        assertEquals(emptyList<SetupStep>(), plan.remainingSteps)
        assertNull("a healthy plan must not nag", plan.nextStep)
    }

    @Test
    fun withNothingGrantedTheAccessibilityServiceLeadsAsTheOneGrantThatRestoresBlocking() {
        val plan = SetupGuidance.plan(status(accessibility = false, usage = false, overlay = false))
        assertFalse(plan.enforcingNow)
        assertEquals(SetupStep.ENABLE_ACCESSIBILITY, plan.nextStep)
        assertTrue(plan.body.contains("accessibility service alone is enough"))
    }

    @Test
    fun theOverlayLeadsWhenItAloneWouldRestoreBlocking() {
        // Usage access is already granted, so the overlay grant on its own brings Path B up —
        // one tap, and cheaper than the consent-gated accessibility flow.
        val plan = SetupGuidance.plan(status(accessibility = false, usage = true, overlay = false))
        assertFalse(plan.enforcingNow)
        assertEquals(SetupStep.GRANT_OVERLAY, plan.nextStep)
        assertEquals(
            listOf(SetupStep.GRANT_OVERLAY, SetupStep.ENABLE_ACCESSIBILITY),
            plan.remainingSteps,
        )
        assertTrue(plan.body.contains("no way to show the block screen"))
    }

    @Test
    fun theAccessibilityServiceStillLeadsWhenUsageAccessIsMissingToo() {
        // Overlay alone changes nothing here: Path B needs usage access as well, so the
        // single-grant route back to blocking is Path A.
        val plan = SetupGuidance.plan(status(accessibility = false, usage = false, overlay = true))
        assertFalse(plan.enforcingNow)
        assertEquals(SetupStep.ENABLE_ACCESSIBILITY, plan.nextStep)
    }

    @Test
    fun blockingThroughTheFallbackIsReportedAsOnAndAdmitsWhatItCannotDo() {
        val plan = SetupGuidance.plan(status(accessibility = false))
        assertTrue("the fallback path really is enforcing", plan.enforcingNow)
        assertTrue(plan.headline.contains("backup path"))
        assertTrue(plan.body.contains("cannot block websites"))
        assertEquals(SetupStep.ENABLE_ACCESSIBILITY, plan.nextStep)
    }

    @Test
    fun anIncompleteBackupPathWarnsThatRevocationWouldStopBlockingOutright() {
        val plan = SetupGuidance.plan(status(usage = false, overlay = false))
        assertTrue(plan.enforcingNow)
        assertTrue(plan.headline.contains("isn't protected"))
        assertTrue(plan.body.contains("blocking would stop entirely"))
        assertEquals(SetupStep.GRANT_USAGE_ACCESS, plan.nextStep)
    }

    @Test
    fun theBatteryExemptionIsTheLastStepAndIsNeverCalledMandatory() {
        val plan = SetupGuidance.plan(status(battery = false))
        assertTrue(plan.enforcingNow)
        assertEquals(SetupStep.REQUEST_BATTERY_EXEMPTION, plan.nextStep)
        assertTrue(plan.body.contains("recommended, not required"))
        assertFalse(plan.body.contains("must", ignoreCase = true))
    }

    // --- copy discipline ---

    @Test
    fun everyStepHasAnHonestCallToAction() {
        SetupStep.entries.forEach { step ->
            assertTrue("cta label for $step", step.ctaLabel.isNotBlank())
            // Hand-offs are user-initiated; Flint never grants anything on the user's behalf.
            assertFalse(
                "cta label for $step must not promise automation",
                step.ctaLabel.contains("auto", ignoreCase = true),
            )
        }
    }

    /** All 16 grant combinations. */
    private fun allStatuses(): List<HealthStatus> = (0 until 16).map { bits ->
        status(
            accessibility = bits and 1 != 0,
            usage = bits and 2 != 0,
            overlay = bits and 4 != 0,
            battery = bits and 8 != 0,
        )
    }
}
