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
import androidx.glance.appwidget.lazy.LazyColumn
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
            val open = actionStartActivity(Intent(context, MainActivity::class.java))
            val primary = TextStyle(color = ColorProvider(Color(0xFFF1EFE8)), fontSize = 14.sp)
            val secondary = TextStyle(color = ColorProvider(Color(0xFFFAC775)), fontSize = 12.sp)
            Column(
                GlanceModifier.fillMaxSize().background(Color(0xFF2C2C2A)).padding(16.dp)
                    .clickable(open),
            ) {
                Text("Flint", style = primary.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
                Spacer(GlanceModifier.height(8.dp))
                // Scroll long names/large text while keeping the observation footer visible.
                LazyColumn(GlanceModifier.defaultWeight()) {
                    item { Text(display.status, GlanceModifier.clickable(open),
                        style = primary.copy(fontWeight = FontWeight.Bold)) }
                    item { Text(display.detail, GlanceModifier.clickable(open), style = primary) }
                    item { Spacer(GlanceModifier.height(8.dp)) }
                    item { Text(display.next, GlanceModifier.clickable(open), style = primary) }
                    item { Text("${display.focusedTodayLabel}: ${display.focusedToday}",
                        GlanceModifier.clickable(open), style = primary) }
                    display.historyNote?.let { note ->
                        item { Text(note, GlanceModifier.clickable(open), style = secondary) }
                    }
                }
                Spacer(GlanceModifier.height(8.dp))
                Text(display.checked, style = secondary)
            }
        }
    }
}
