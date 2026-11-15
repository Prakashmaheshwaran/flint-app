package com.flint.peakfocus

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import com.flint.peakfocus.core.model.FlintIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ADR-008 (2026-11-11): the installed package is `app.flint.focus`; the namespace and Kotlin
 * packages stay `com.flint.peakfocus`. Pins the build to the shared constant used by
 * context-free policy code and proves components resolve by fully qualified class name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FlintIdentityBuildTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun `applicationId is the shared identity and differs from the kept namespace`() {
        assertEquals("app.flint.focus", BuildConfig.APPLICATION_ID)
        assertEquals(FlintIdentity.APPLICATION_ID, BuildConfig.APPLICATION_ID)
        assertEquals("com.flint.peakfocus", BuildConfig::class.java.`package`!!.name)
        assertEquals(FlintIdentity.APPLICATION_ID, context.packageName)
    }

    @Test fun `MainActivity and the Accessibility service resolve under the installed package`() {
        assertEquals(FlintIdentity.MAIN_ACTIVITY_CLASS, MainActivity::class.java.name)
        assertNotEquals(FlintIdentity.MAIN_ACTIVITY_CLASS, "${context.packageName}.MainActivity")
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        @Suppress("DEPRECATION")
        val resolved = context.packageManager.queryIntentActivities(launcher, 0).map { it.activityInfo.name }
        assertEquals(listOf(FlintIdentity.MAIN_ACTIVITY_CLASS), resolved)
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(ComponentName(context.packageName, A11Y_SERVICE_CLASS), 0)
        assertEquals(FlintIdentity.APPLICATION_ID, service.packageName)
        assertEquals(A11Y_SERVICE_CLASS, service.name)
    }
}
