package com.flint.peakfocus.glance

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.flint.peakfocus.MainActivity

class FlintGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FlintGlanceWidget()
}

/** One action on the entire surface: open Flint. No start/stop/break/pass routes. */
class FlintGlanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        GlanceSurfaceBridge.readDisplay()
        provideContent {
            val display by GlanceSurfaceBridge.display.collectAsState()
            val primary = TextStyle(color = ColorProvider(Color(0xFFF1EFE8)), fontSize = 14.sp)
            val secondary = TextStyle(color = ColorProvider(Color(0xFFFAC775)), fontSize = 12.sp)
            Column(
                GlanceModifier.fillMaxSize().background(Color(0xFF2C2C2A)).padding(16.dp)
                    .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
            ) {
                Text("Flint", style = primary.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.height(8.dp))
                Text(display.status, style = primary.copy(fontWeight = FontWeight.Bold), maxLines = 2)
                Text(display.detail, style = primary, maxLines = 3)
                Spacer(GlanceModifier.height(8.dp))
                Text(display.next, style = primary, maxLines = 2)
                Text("${display.focusedTodayLabel}: ${display.focusedToday}", style = primary, maxLines = 2)
                display.historyNote?.let { Text(it, style = secondary, maxLines = 2) }
                Spacer(GlanceModifier.height(8.dp))
                Text(display.checked, style = secondary, maxLines = 2)
            }
        }
    }
}
