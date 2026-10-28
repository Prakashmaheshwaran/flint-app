package com.flint.peakfocus.core.history

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Inexact local reminders. No exact-alarm permission and no permission prompt. */
internal class FocusCompletionNotifier(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val state = notificationState(context)
    private var firstSuccessfulSnapshot = true

    suspend fun reconcile(snapshot: HistorySnapshot, now: Long) {
        createChannel()
        val delivered = state.delivered()
        val targets = FocusCompletionPolicy.alarms(snapshot, delivered, now).associate { it.sessionId to it.at }
        val previous = state.scheduled()
        previous.keys.filter { it !in targets }.forEach { alarms.cancel(pending(context, it)) }
        // Persist stale outcomes without posting. A late resume cannot produce surprise reminders.
        snapshot.events.map { it.session }.distinct().filter { it !in delivered }.forEach { id ->
            if (FocusCompletionPolicy.decision(snapshot, id, now) == FocusCompletionDelivery.STALE)
                state.markDelivered(id, now)
        }
        targets.forEach { (id, end) ->
            if (firstSuccessfulSnapshot || previous[id] != end) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(end, now), pending(context, id))
            }
        }
        state.setScheduled(targets)
        firstSuccessfulSnapshot = false
    }

    fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Focus sessions", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Timer completion reminders may arrive a few minutes late."
            },
        )
    }

    fun notificationsEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            return false
        if (Build.VERSION.SDK_INT >= 24 && !notifications.areNotificationsEnabled()) return false
        if (Build.VERSION.SDK_INT >= 26 && notifications.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE)
            return false
        return true
    }

    fun post(sessionId: String) {
        createChannel()
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL) else Notification.Builder(context)
        builder.setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Focus session complete")
            .setContentText("Your focus timer reached its planned end.")
            .setAutoCancel(true)
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let { intent ->
            builder.setContentIntent(PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        notifications.notify(sessionId, 1, builder.build())
    }

    companion object {
        @Volatile internal var diagnostic: String? = null
        private const val CHANNEL = "focus_sessions"
        internal const val SESSION_EXTRA = "focus_session_id"
        fun notificationState(context: Context) = FocusNotificationState(File(context.filesDir, "focus-history/notification-state.v1.json"))

        private fun pending(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(context, 0,
            Intent(context, FocusCompletionReceiver::class.java)
                .setData(Uri.Builder().scheme("flint-focus").authority("complete").appendPath(id).build())
                .putExtra(SESSION_EXTRA, id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun start(context: Context, scope: CoroutineScope, store: FocusHistoryStore, ready: StateFlow<Boolean>) {
            val notifier = FocusCompletionNotifier(context.applicationContext)
            scope.launch(Dispatchers.IO) {
                combine(store.snapshots, ready) { snapshot, loaded -> snapshot to loaded }.collect { (snapshot, loaded) ->
                    if (loaded) try { notifier.reconcile(snapshot, System.currentTimeMillis()); diagnostic = null }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { diagnostic = e.javaClass.simpleName }
                }
            }
        }
    }
}

class FocusCompletionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(FocusCompletionNotifier.SESSION_EXTRA)?.takeIf { it.startsWith("session-") } ?: return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val notifier = FocusCompletionNotifier(app)
                notifier.createChannel()
                FocusCompletionDeliveryService(FocusCompletionNotifier.notificationState(app),
                    { FocusHistoryBridge.store(app).load() }, notifier::notificationsEnabled, notifier::post)
                    .deliver(id, System.currentTimeMillis())
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Fail closed if history, delivery state, or notifications are unavailable. */ }
            finally { pending.finish() }
        }
    }
}
