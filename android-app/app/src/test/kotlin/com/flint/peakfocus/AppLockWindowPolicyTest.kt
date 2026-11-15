package com.flint.peakfocus

import com.flint.peakfocus.feature.settings.applock.PinStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The API-level split that keeps a PIN-protected app out of the recents thumbnail. */
class AppLockWindowPolicyTest {

    @Test fun `api 32 secures the window only while a pin exists`() {
        assertFalse(AppLockWindowPolicy.decide(sdkInt = 32, pinStatus = PinStatus.Off).secureFlag)
        assertTrue(AppLockWindowPolicy.decide(sdkInt = 32, pinStatus = PinStatus.On).secureFlag)
        assertTrue(
            AppLockWindowPolicy.decide(sdkInt = 32, pinStatus = PinStatus.Unreadable).secureFlag,
        )
    }

    @Test fun `api 32 never touches the recents-screenshot flag`() {
        for (status in PinStatus.entries) {
            assertTrue(AppLockWindowPolicy.decide(sdkInt = 32, pinStatus = status).recentsScreenshotEnabled)
        }
    }

    @Test fun `api 33 and newer hide only the recents thumbnail`() {
        for (sdk in listOf(33, 34, 35, 36)) {
            assertFalse(
                AppLockWindowPolicy.decide(sdkInt = sdk, pinStatus = PinStatus.On)
                    .recentsScreenshotEnabled,
            )
            assertFalse(
                AppLockWindowPolicy.decide(sdkInt = sdk, pinStatus = PinStatus.Unreadable)
                    .recentsScreenshotEnabled,
            )
            assertTrue(
                AppLockWindowPolicy.decide(sdkInt = sdk, pinStatus = PinStatus.Off)
                    .recentsScreenshotEnabled,
            )
            // FLAG_SECURE would also break screen recording and screenshots; only 33+ has the
            // narrower control, so it must never be set there.
            for (status in PinStatus.entries) {
                assertFalse(AppLockWindowPolicy.decide(sdkInt = sdk, pinStatus = status).secureFlag)
            }
        }
    }
}
