package com.flint.peakfocus

import com.flint.peakfocus.core.datastore.EditorTerminalDeliveryHealth
import com.flint.peakfocus.core.model.StorageFailure
import com.flint.peakfocus.core.model.StorageState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class EditorHistoryHealthTest {
    @Test fun retainedPendingOrOverflowDoesNotClaimMissingHistory() = runTest {
        var reports = 0
        reportEditorHistoryLoss(flowOf<StorageState<EditorTerminalDeliveryHealth>>(
            StorageState.Loading,
            StorageState.Ready(EditorTerminalDeliveryHealth(0, 0, null)),
            StorageState.Ready(EditorTerminalDeliveryHealth(5, 3, null)),
            StorageState.Unavailable(StorageFailure.IO),
        )) { reports++ }
        assertEquals(0, reports)
    }

    @Test fun publicOverflowLossAndCaptureFailureBothReportMissingHistory() = runTest {
        var reports = 0
        reportEditorHistoryLoss(flowOf<StorageState<EditorTerminalDeliveryHealth>>(
            StorageState.Ready(EditorTerminalDeliveryHealth(0, 0, null, overflowDropped = 1)),
            StorageState.Ready(EditorTerminalDeliveryHealth(0, 0, null, captureFailures = 1)),
            StorageState.Ready(EditorTerminalDeliveryHealth(0, 1, null)),
        )) { reports++ }
        assertEquals(2, reports)
    }
}
