package com.flint.peakfocus.blocking.overlay

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.datastore.TimeLimitForegroundObservation
import com.flint.peakfocus.core.model.AccessClockStamp
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class TimeLimitForegroundSessionTest {
    @Test fun PC_48_departureCapturesANewVisitEvenIfItsDurableWriteFails() = runTest {
        val observed = mutableListOf<TimeLimitForegroundObservation>()
        val session = TimeLimitForegroundSession(write = { observation, current ->
            assertTrue(observation.closeOnly || current())
            observed += observation
            if (observation.packageName == null && !observation.closeOnly) throw IOException("floor checkpoint failed")
        }, eligible = { true }, dispatcher = StandardTestDispatcher(testScheduler),
            stampFor = { wall, elapsed -> AccessClockStamp(wall, elapsed, "boot") })
        session.checkpoint("app").also { runCurrent(); it.await() }
        val floor = session.checkpoint(null)
        val returned = session.checkpoint("app")
        runCurrent()
        try { floor.await(); fail() } catch (_: IOException) { }
        returned.await()
        assertNull(observed[1].packageName)
        assertEquals(observed[0].visitId, observed[1].previousVisitId)
        assertNull(observed[2].previousVisitId)
        assertNotEquals(observed[0].visitId, observed[2].visitId)
        session.close().also { runCurrent(); it.await() }
    }

    @Test fun PC_60_retirementFencesQueuedWorkAndClosesOutsideTheCancelledEvaluation() = runTest {
        val written = mutableListOf<TimeLimitForegroundObservation>()
        val session = TimeLimitForegroundSession(write = { observation, current ->
            if (observation.closeOnly || current()) written += observation
        }, eligible = { true }, dispatcher = StandardTestDispatcher(testScheduler),
            stampFor = { wall, elapsed -> AccessClockStamp(wall, elapsed, "boot") })
        session.checkpoint("app").also { runCurrent(); it.await() }
        val evaluation = launch { session.checkpoint("app").await() }
        evaluation.cancel()
        val closed = session.close()
        runCurrent(); closed.await()
        assertEquals(2, written.size)
        assertTrue(written.last().closeOnly)
        assertEquals(written.first().ownerId, written.last().ownerId)
        session.checkpoint("app").await()
        assertEquals(2, written.size)
    }
}
