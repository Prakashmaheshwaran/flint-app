package com.flint.peakfocus.glance

import android.Manifest
import android.app.Activity
import android.app.Application
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

/** DEBUG ONLY: real AppWidgetHost/Glance jobs and notification pixels with labeled fake evidence.
 * Binding and notification consent use ordinary OS dialogs; no privileged grants or rule writes.
 */
class GlanceFixtureActivity : Activity() {
    private lateinit var host: AppWidgetHost
    private lateinit var layout: LinearLayout
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var fixtureScope: CoroutineScope? = null
    private var sessionEnd = 0L
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            if (activity !is GlanceFixtureActivity) restoreProduction()
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
        override fun onActivityStarted(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivityStopped(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        application.registerActivityLifecycleCallbacks(lifecycle)
        fixtureOwner = this
        host = AppWidgetHost(this, 12028)
        host.startListening()
        layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 24, 16, 16) }
        layout.addView(TextView(this).apply { text = "FIXTURE EVIDENCE · No real protection"; textSize = 18f })
        setContentView(layout)
        configure(intent)
        widgetId = host.allocateAppWidgetId()
        val provider = ComponentName(this, FlintGlanceWidgetReceiver::class.java)
        if (AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(widgetId, provider)) showWidget()
        else @Suppress("DEPRECATION") startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider), 1)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configure(intent)
    }

    private fun configure(intent: Intent) {
        fixtureOwner = this
        fixtureScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        fixtureScope = scope
        sessionEnd = SystemClock.elapsedRealtime() + 300_000
        val state = intent.getStringExtra("state") ?: "unavailable"
        val reader = object : GlanceProtectionReader {
            override val changes = flow { while (true) { emit(Unit); delay(1_000) } }
            override suspend fun read(): ProtectionReading {
                if (state == "unavailable") return ProtectionReading()
                val now = SystemClock.elapsedRealtime()
                return ProtectionReading(
                    status = if (state == "protected") ProtectionStatus.ACTIVE else ProtectionStatus.INACTIVE,
                    checkedElapsedMs = now, checkedEpochMs = System.currentTimeMillis(),
                    validUntilElapsedMs = now + 2_000, sameBootVerified = true,
                    detector = GlanceDetector.PATH_A_CONFIRMED,
                    cause = if (state == "protected") ActiveGlanceCause("fixture-occurrence", "Fixture focus",
                        GlanceTier.HARDER, sessionEnd, GlanceCauseKind.FOCUS_SESSION) else null,
                )
            }
        }
        val history = object : GlanceFocusReader {
            override val changes = flowOf(Unit)
            override suspend fun read() = FocusTodayReading("5m", "Fixture local history", LocalDate.now())
        }
        GlanceSurfaceBridge.start(this, scope, reader, history)
    }

    @Deprecated("Test fixture uses the platform result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) showWidget()
    }

    private fun showWidget() {
        val manager = AppWidgetManager.getInstance(this)
        val view = host.createView(this, widgetId, manager.getAppWidgetInfo(widgetId))
        view.updateAppWidgetSize(Bundle(), 320, 320, 320, 320)
        layout.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            (380 * resources.displayMetrics.density).toInt()))
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
    }

    override fun onDestroy() {
        application.unregisterActivityLifecycleCallbacks(lifecycle)
        fixtureScope?.cancel()
        restoreProduction()
        host.stopListening()
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(widgetId)
        super.onDestroy()
    }

    private fun restoreProduction() {
        if (fixtureOwner !== this) return // An old Activity cannot retire a newer fixture.
        fixtureOwner = null
        fixtureScope?.cancel()
        GlanceSurfaceBridge.start(applicationContext, productionScope, CoordinatorGlanceReader(),
            FocusHistoryGlanceReader(applicationContext, productionScope))
    }

    private companion object {
        var fixtureOwner: GlanceFixtureActivity? = null
        val productionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
