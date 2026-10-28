package com.flint.peakfocus.blocking.overlay

import com.flint.peakfocus.core.model.AccessClockStamp
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProtectionStatusLeaseTest {
    private val policy = ProtectionStatus.UNKNOWN.copy(
        readiness = ProtectionStatus.Readiness.READY,
        observedAt = AccessClockStamp(10_000, 0, "boot"), validUntilElapsedRealtimeMs = 1_000,
        detectors = ProtectionStatus.DetectorHealth(true, ProtectionStatus.PathBState.UNKNOWN, setOf("policy-reason")),
    )

    @Test fun PC_55_expiredLeaseReadsUnknownWithoutChangingThePolicyObservation() = runTest {
        var now = 0L
        val publisher = ProtectionStatusPublisher({ now }, backgroundScope)
        publisher.publish(policy)
        publisher.publishPathBHealth(ProtectionStatus.PathBState.ENFORCING, leaseUntilElapsedRealtimeMs = 100)
        assertEquals(100L, publisher.status.value.validUntilElapsedRealtimeMs)
        now = 100 // Deliberately do not run the expiry job: .value must expire a dead publisher.
        val expired = publisher.status.value
        assertEquals(ProtectionStatus.PathBState.UNKNOWN, expired.detectors.pathB)
        assertTrue("path-b-health-expired" in expired.detectors.degradedReasons)
        assertTrue("policy-reason" in expired.detectors.degradedReasons)
        assertTrue(expired.detectors.accessibilityConnected)
        assertEquals(policy.observedAt, expired.observedAt)
        assertEquals(policy.lastCheckedEpochMs, expired.lastCheckedEpochMs)
        assertEquals(policy.readiness, expired.readiness)
    }

    @Test fun PC_55_expiryJobNotifiesCollectorsWithoutAReadOrServiceCallback() = runTest {
        val publisher = ProtectionStatusPublisher({ testScheduler.currentTime }, backgroundScope)
        val seen = mutableListOf<ProtectionStatus>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { publisher.status.collect { seen += it } }
        publisher.publish(policy)
        publisher.publishPathBHealth(ProtectionStatus.PathBState.STANDBY, leaseUntilElapsedRealtimeMs = 10)
        runCurrent()
        assertEquals(ProtectionStatus.PathBState.STANDBY, seen.last().detectors.pathB)
        advanceTimeBy(10)
        runCurrent()
        assertEquals(ProtectionStatus.PathBState.UNKNOWN, seen.last().detectors.pathB)
        assertEquals(policy.observedAt, seen.last().observedAt)
    }

    @Test fun PC_55_aConfirmedRenewalExtendsOnlyItsOwnLeaseAndCancelsTheOldExpiry() = runTest {
        val publisher = ProtectionStatusPublisher({ testScheduler.currentTime }, backgroundScope)
        publisher.publish(policy)
        publisher.publishPathBHealth(ProtectionStatus.PathBState.ENFORCING, leaseUntilElapsedRealtimeMs = 100)
        runCurrent()
        advanceTimeBy(50)
        publisher.publishPathBHealth(ProtectionStatus.PathBState.ENFORCING, leaseUntilElapsedRealtimeMs = 200)
        runCurrent()
        advanceTimeBy(50)
        runCurrent()
        assertEquals(ProtectionStatus.PathBState.ENFORCING, publisher.status.value.detectors.pathB)
        assertEquals(200L, publisher.status.value.validUntilElapsedRealtimeMs)
        assertEquals(policy.lastCheckedEpochMs, publisher.status.value.lastCheckedEpochMs)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(ProtectionStatus.PathBState.UNKNOWN, publisher.status.value.detectors.pathB)
    }

    @Test fun PC_55_nullLeaseKeepsCompatibilityAndCancelsAnEarlierLease() = runTest {
        val publisher = ProtectionStatusPublisher({ testScheduler.currentTime }, backgroundScope)
        publisher.publish(policy)
        publisher.publishPathBHealth(ProtectionStatus.PathBState.ENFORCING, leaseUntilElapsedRealtimeMs = 10)
        runCurrent()
        publisher.publishPathBHealth(ProtectionStatus.PathBState.OFF)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(ProtectionStatus.PathBState.OFF, publisher.status.value.detectors.pathB)
        assertEquals(policy.validUntilElapsedRealtimeMs, publisher.status.value.validUntilElapsedRealtimeMs)
        assertFalse("path-b-health-expired" in publisher.status.value.detectors.degradedReasons)
    }
}
