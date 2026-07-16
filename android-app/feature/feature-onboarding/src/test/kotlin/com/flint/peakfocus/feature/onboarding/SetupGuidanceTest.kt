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
        notificationsRequired: Boolean = false,
        notificationsGranted: Boolean = true,
    ) = HealthStatus(
        accessibilityEnabled = accessibility,
        usageAccessGranted = usage,
        overlayGranted = overlay,
        batteryExemptionGranted = battery,
        notificationsRequired = notificationsRequired,
        notificationsGranted = notificationsGranted,
    )

    // --- invariants across every blocking grant combination and notification state ---

    @Test
    fun theCardClaimsBlockingIsOnExactlyWhenFlintCanEnforce() {
        allStatuses().forEach { current ->
            val plan = SetupGuidance.plan(current)
            assertEquals("enforcingNow for $current", current.canEnforce, plan.enforcingNow)
            if (current.canEnforce) {
                assertTrue(
                    "headline for $current must claim blocking is on, was: ${plan.headline}",
                    plan.headline.startsWith("Blocking is on"),
                )
            } else {
                assertEquals("Blocking is off", plan.headline)
            }
        }
    }

    @Test
    fun remainingStepsAreExactlyTheMissingBlockingAndSurvivalGrants() {
        allStatuses().forEach { current ->
            val expected = buildSet {
                if (!current.accessibilityEnabled) add(SetupStep.ENABLE_ACCESSIBILITY)
                if (!current.usageAccessGranted) add(SetupStep.GRANT_USAGE_ACCESS)
                if (!current.overlayGranted) add(SetupStep.GRANT_OVERLAY)
                if (!current.batteryExemptionGranted) add(SetupStep.REQUEST_BATTERY_EXEMPTION)
            }
            val steps = SetupGuidance.plan(current).remainingSteps
            assertEquals("remaining steps for $current", expected, steps.toSet())
            assertEquals("no step may repeat for $current", steps.size, steps.toSet().size)
        }
    }

    @Test
    fun theNextStepIsAlwaysTheHeadOfTheRemainingSteps() {
        allStatuses().forEach { current ->
            val plan = SetupGuidance.plan(current)
            assertEquals("next step for $current", plan.remainingSteps.firstOrNull(), plan.nextStep)
        }
    }

    @Test
    fun everyPlanExplainsItself() {
        allStatuses().forEach { current ->
            assertTrue(
                "body for $current must not be blank",
                SetupGuidance.plan(current).body.isNotBlank(),
            )
        }
    }

    @Test
    fun theBatteryCaveatAppearsWheneverTheExemptionIsMissingAndNeverOtherwise() {
        allStatuses().forEach { current ->
            val warns = SetupGuidance.plan(current).body
                .contains("not exempt from battery optimization")
            assertEquals("battery caveat for $current", !current.batteryExemptionGranted, warns)
        }
    }

    @Test
    fun notificationVisibilityNeverChangesEnforcementOrStepOrdering() {
        coreStatuses().forEach { base ->
            val granted = SetupGuidance.plan(
                base.copy(notificationsRequired = true, notificationsGranted = true),
            )
            val denied = SetupGuidance.plan(
                base.copy(notificationsRequired = true, notificationsGranted = false),
            )
            assertEquals("enforcement for $base", granted.enforcingNow, denied.enforcingNow)
            assertEquals("headline for $base", granted.headline, denied.headline)
            assertEquals("steps for $base", granted.remainingSteps, denied.remainingSteps)
            assertEquals("next step for $base", granted.nextStep, denied.nextStep)
        }
    }

    @Test
    fun hiddenNotificationIsNamedOnlyWhileTheBackupPathIsActuallyEnforcing() {
        allStatuses().forEach { current ->
            val expected = !current.accessibilityEnabled && current.fallbackPathAvailable &&
                !current.serviceNotificationVisible
            val named = SetupGuidance.plan(current).body.contains("status notification")
            assertEquals("notification visibility copy for $current", expected, named)
        }
    }

    // --- representative card states ---

    @Test
    fun nothingIsLeftToDoWhenEveryBlockingAndSurvivalGrantIsInPlace() {
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
        val plan = SetupGuidance.plan(status(accessibility = false, usage = false, overlay = true))
        assertFalse(plan.enforcingNow)
        assertEquals(SetupStep.ENABLE_ACCESSIBILITY, plan.nextStep)
    }

    @Test
    fun blockingThroughTheFallbackIsReportedAsOnAndAdmitsWhatItCannotDo() {
        val plan = SetupGuidance.plan(status(accessibility = false))
        assertTrue(plan.enforcingNow)
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

    @Test
    fun deniedNotificationsExplainVisibilityWithoutClaimingEnforcementIsBroken() {
        val plan = SetupGuidance.plan(
            status(
                accessibility = false,
                notificationsRequired = true,
                notificationsGranted = false,
            ),
        )
        assertTrue(plan.enforcingNow)
        assertTrue(plan.body.contains("Blocking itself is unaffected"))
        assertTrue(plan.body.contains("Flint Settings"))
    }

    // --- copy discipline ---

    @Test
    fun everyStepHasAnHonestCallToAction() {
        SetupStep.entries.forEach { step ->
            assertTrue("cta label for $step", step.ctaLabel.isNotBlank())
            assertFalse(
                "cta label for $step must not promise automation",
                step.ctaLabel.contains("auto", ignoreCase = true),
            )
        }
    }

    /** All 16 blocking/survival grant combinations. */
    private fun coreStatuses(): List<HealthStatus> = (0 until 16).map { bits ->
        status(
            accessibility = bits and 1 != 0,
            usage = bits and 2 != 0,
            overlay = bits and 4 != 0,
            battery = bits and 8 != 0,
        )
    }

    /** Every core combination across pre-13, 13+ granted, and 13+ denied notifications. */
    private fun allStatuses(): List<HealthStatus> = coreStatuses().flatMap { base ->
        listOf(
            base.copy(notificationsRequired = false, notificationsGranted = false),
            base.copy(notificationsRequired = true, notificationsGranted = true),
            base.copy(notificationsRequired = true, notificationsGranted = false),
        )
    }
}
