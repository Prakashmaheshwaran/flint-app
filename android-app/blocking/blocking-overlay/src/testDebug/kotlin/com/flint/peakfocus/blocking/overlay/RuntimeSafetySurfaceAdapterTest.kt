package com.flint.peakfocus.blocking.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telecom.TelecomManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flint.peakfocus.core.common.RuntimeSafetySurfaces
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Real resolveBySource + framework intent filtering, with deterministic Robolectric roles. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RuntimeSafetySurfaceAdapterTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val pm get() = app.packageManager

    @Before fun setup() {
        shadowOf(pm).setSystemFeature(PackageManager.FEATURE_TELEPHONY, true)
        val telecom = app.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        shadowOf(telecom).setDefaultDialerPackage(null)
        shadowOf(telecom).setSystemDialerPackage(null)
    }

    private fun handler(pkg: String, action: String, default: Boolean, scheme: String? = null) {
        val component = ComponentName(pkg, "$pkg.Handler")
        shadowOf(pm).addActivityIfNotPresent(component).apply {
            exported = true
            enabled = true
            applicationInfo.enabled = true
        }
        shadowOf(pm).addIntentFilterForActivity(component, IntentFilter(action).apply {
            if (default) addCategory(Intent.CATEGORY_DEFAULT)
            if (scheme != null) addDataScheme(scheme)
        })
    }

    @Test fun PC_49_nonDefaultExportedDialFilterCannotEscapeAnExplicitBlock() {
        handler("com.unused.dial.filter", Intent.ACTION_DIAL, default = false, scheme = "tel")
        handler("com.real.call.handler", Intent.ACTION_CALL, default = true, scheme = "tel")
        val snapshot = RuntimeSafetySurfaces.resolveBySource(app)
        assertNull(snapshot[RuntimeSafetySurfaces.SOURCE_DEFAULT_DIALER])
        assertEquals(setOf("com.real.call.handler"), snapshot[RuntimeSafetySurfaces.SOURCE_CANDIDATE_CALLING])
        val gate = SafetyFloorGate(app.packageName, { snapshot }, { 0 },
            requiredRuntimeSources = setOf(RuntimeSafetySurfaces.SOURCE_DEFAULT_DIALER))
        assertTrue(gate.protects("com.real.call.handler"))
        assertFalse(gate.protects("com.unused.dial.filter"))
        assertFalse(gate.protects("ordinary.control"))
        assertTrue(gate.runtimeResolution.value.degraded)
    }

    @Test fun PC_49_telephonyDeviceWithUnsetRoleUsesTheEffectiveDefaultDialer() {
        handler("com.effective.dialer", Intent.ACTION_DIAL, default = true, scheme = "tel")
        val snapshot = RuntimeSafetySurfaces.resolveBySource(app)
        assertEquals(setOf("com.effective.dialer"), snapshot[RuntimeSafetySurfaces.SOURCE_DEFAULT_DIALER])
        assertEquals(setOf("com.effective.dialer"), snapshot[RuntimeSafetySurfaces.SOURCE_SYSTEM_DIALER])
    }

    @Test fun PC_49_tabletWithoutTelephonyHasResolvedAbsentCallingRoles() {
        shadowOf(pm).setSystemFeature(PackageManager.FEATURE_TELEPHONY, false)
        val snapshot = RuntimeSafetySurfaces.resolveBySource(app)
        assertEquals(emptySet<String>(), snapshot[RuntimeSafetySurfaces.SOURCE_DEFAULT_DIALER])
        assertEquals(emptySet<String>(), snapshot[RuntimeSafetySurfaces.SOURCE_SYSTEM_DIALER])
    }

    @Test fun PC_49_dataOnlyTelephonyDeviceWithNoCallingHandlerResolvesAbsentRatherThanFailed() {
        val snapshot = RuntimeSafetySurfaces.resolveBySource(app)
        assertEquals(emptySet<String>(), snapshot[RuntimeSafetySurfaces.SOURCE_DEFAULT_DIALER])
        assertEquals(emptySet<String>(), snapshot[RuntimeSafetySurfaces.SOURCE_SYSTEM_DIALER])
    }
}
