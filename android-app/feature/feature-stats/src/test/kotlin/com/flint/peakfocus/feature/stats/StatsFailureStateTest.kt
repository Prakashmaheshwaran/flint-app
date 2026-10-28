package com.flint.peakfocus.feature.stats

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StatsFailureStateTest {
    @Test fun failedUsageQueryShowsErrorAndRetryCanRecover() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            var failing = true
            val report = UsageReport(emptyList(), 0, 0, emptyList())
            val viewModel = StatsViewModel({ true }, { if (failing) throw IOException("query unavailable") else report }, dispatcher)
            viewModel.refresh(); advanceUntilIdle()
            assertEquals(StatsUiState.Error, viewModel.uiState.value)
            failing = false
            viewModel.refresh(); advanceUntilIdle()
            assertEquals(StatsUiState.Ready(report), viewModel.uiState.value)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun permissionLossDuringQueryMapsToPermissionRequired() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val viewModel = StatsViewModel({ true }, { throw SecurityException("revoked") }, dispatcher)
            viewModel.refresh(); advanceUntilIdle()
            assertEquals(StatsUiState.PermissionRequired, viewModel.uiState.value)
            assertEquals(StatsUiState.Error, statsFailureState(IllegalStateException("service unavailable")))
        } finally { Dispatchers.resetMain() }
    }
}
