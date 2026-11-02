package com.flint.peakfocus.e2e

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/** Isolated test-APK host: exercises the installed app's actual minified widget worker. */
class GlanceWidgetHostActivity : Activity() {
    private lateinit var host: AppWidgetHost
    private lateinit var layout: LinearLayout
    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = AppWidgetHost(this, 12029)
        host.startListening()
        layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 24, 16, 16) }
        layout.addView(TextView(this).apply { text = "TEST HOST · Installed Flint widget"; textSize = 18f })
        setContentView(layout)
        widgetId = host.allocateAppWidgetId()
        val provider = ComponentName("com.flint.peakfocus", "com.flint.peakfocus.glance.FlintGlanceWidgetReceiver")
        if (AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(widgetId, provider)) showWidget()
        else @Suppress("DEPRECATION") startActivityForResult(Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider), 1)
    }

    @Deprecated("Test host uses platform callback")
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
    }

    override fun onDestroy() {
        host.stopListening()
        if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) host.deleteAppWidgetId(widgetId)
        super.onDestroy()
    }
}
