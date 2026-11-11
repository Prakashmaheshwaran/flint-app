package com.flint.peakfocus.quickstart

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.service.quicksettings.Tile
import androidx.core.content.pm.ShortcutManagerCompat
import com.flint.peakfocus.MainActivity
import com.flint.peakfocus.core.model.AppGroup
import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.StorageFailure
import com.flint.peakfocus.core.model.StorageState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class QuickStartSurfacesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun group(id: String, name: String, vararg packages: String) =
        AppGroup(id, name, packages.map { AppRef(it, it) }.toSet())

    @Before fun clear() {
        QuickStartInbox.resetForTest()
        ShortcutManagerCompat.removeAllDynamicShortcuts(context)
    }

    @After fun reset() = QuickStartInbox.resetForTest()

    // --- Exposure: no new exported route, no new permission ---------------------------------

    @Test fun entryIsNotExportedAndTileIsBindableOnlyBySystemUi() {
        val pm = context.packageManager
        val entry = pm.getActivityInfo(ComponentName(context, QuickStartActivity::class.java), 0)
        assertFalse("Other apps must not reach the quick-start entry", entry.exported)
        val tile = pm.getServiceInfo(ComponentName(context, FlintQuickTileService::class.java), 0)
        assertEquals(Manifest.permission.BIND_QUICK_SETTINGS_TILE, tile.permission)
        // No intent filter: an implicit request can never resolve to the entry. (Release builds'
        // no-network posture is gated by scripts/verify_local_first_apk.py on the release APK.)
        val implicit = Intent(QuickStartIntents.ACTION_START_FOCUS).setPackage(context.packageName)
        assertTrue(pm.queryIntentActivities(implicit, 0).isEmpty())
        assertNull(entry.permission)
    }

    // --- Entry activity ----------------------------------------------------------------------

    @Test fun entryPostsOneOpenRequestAndReordersMainActivityWithoutClearingTheTask() {
        val controller = Robolectric.buildActivity(QuickStartActivity::class.java,
            QuickStartIntents.startFocus(context, groupId = "g-1", shortcutId = "focus-group-g-1")).create()
        val pending = checkNotNull(QuickStartInbox.pending.value)
        assertEquals("g-1", pending.request.groupId)
        val started = shadowOf(context).nextStartedActivity
        assertEquals(MainActivity::class.java.name, started.component?.className)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP == 0)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK == 0)
        assertTrue(started.extras == null || started.extras!!.isEmpty)
        assertTrue(controller.get().isFinishing)
    }

    @Test fun unknownActionOrMalformedIdsOnlyOpenFlint() {
        Robolectric.buildActivity(QuickStartActivity::class.java,
            Intent(context, QuickStartActivity::class.java).setAction(Intent.ACTION_VIEW)
                .putExtra(QuickStartIntents.EXTRA_GROUP_ID, "g-1")).create()
        assertNull(QuickStartInbox.pending.value)
        assertEquals(MainActivity::class.java.name, shadowOf(context).nextStartedActivity.component?.className)

        for (bad in listOf("", "has space", "line\nbreak", "x".repeat(129))) {
            val parsed = QuickStartIntents.parse(QuickStartIntents.startFocus(context, groupId = bad))
            assertNull(parsed?.groupId)
        }
        val wrongType = Intent(QuickStartIntents.ACTION_START_FOCUS).putExtra(QuickStartIntents.EXTRA_GROUP_ID, 42)
        assertEquals(QuickStartIntents.Parsed(null, null), QuickStartIntents.parse(wrongType))
    }

    @Test fun inboxExpiresAndConsumesOnlyTheRequestItNames() {
        val first = QuickStartInbox.post(null, 1_000)
        val pending = QuickStartInbox.pending.value!!
        assertTrue(QuickStartInbox.isFresh(pending, 1_000 + QuickStartInbox.MAX_AGE_MS - 1))
        assertFalse(QuickStartInbox.isFresh(pending, 1_000 + QuickStartInbox.MAX_AGE_MS))
        assertFalse("A clock before the post is not fresh", QuickStartInbox.isFresh(pending, 999))
        val second = QuickStartInbox.post("g", 2_000)
        assertTrue(second.id > first.id)
        QuickStartInbox.consume(first.id)
        assertEquals(second, QuickStartInbox.pending.value?.request)
        QuickStartInbox.consume(second.id)
        assertNull(QuickStartInbox.pending.value)
    }

    // --- Tile --------------------------------------------------------------------------------

    @Test fun tileTapOpensTheEntryAndNeverToggles() {
        val service = Robolectric.buildService(FlintQuickTileService::class.java).create().get()
        service.onClick()
        val opened = shadowOf(context).nextStartedActivity
        assertEquals(QuickStartActivity::class.java.name, opened.component?.className)
        assertEquals(QuickStartIntents.ACTION_START_FOCUS, opened.action)
        assertNull(opened.getStringExtra(QuickStartIntents.EXTRA_GROUP_ID))
        assertTrue(opened.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull("The tile itself posts nothing; only the entry does", QuickStartInbox.pending.value)
    }

    @Test fun lockedTileUnlocksBeforeOpening() {
        val service = Robolectric.buildService(FlintQuickTileService::class.java).create().get()
        shadowOf(service).setLocked(true)
        service.onClick()
        assertFalse(service.isLocked)
        assertEquals(QuickStartActivity::class.java.name, shadowOf(context).nextStartedActivity.component?.className)
    }

    @Test fun listeningTileShowsUnconfirmedWithoutALiveCoordinatorCheck() {
        val controller = Robolectric.buildService(FlintQuickTileService::class.java).create()
        val service = controller.get()
        service.onStartListening()
        shadowOf(Looper.getMainLooper()).idle()
        val tile = service.qsTile
        assertEquals(Tile.STATE_INACTIVE, tile.state)
        assertEquals("Start focus", tile.label)
        assertEquals("Status unconfirmed", tile.subtitle)
        assertEquals("Start focus. Protection not confirmed. Opens Flint.", tile.contentDescription)
        assertEquals("Protection not confirmed", tile.stateDescription)
        service.onStopListening() // destroy() is not used: Robolectric's TileService shadow can't run it.
    }

    // --- Shortcuts ---------------------------------------------------------------------------

    @Test fun specsKeepStartFirstAndOnlyNamedGroupsWithApps() {
        val specs = QuickStartShortcuts.specs(listOf(
            group("z", "zeta", "a.app"),
            group("e", "Empty"),
            group("b", "beta", "b.app"),
            group("a", "Alpha\nWork", "c.app"),
            group("d", "delta", "d.app"),
        ))
        assertEquals(listOf("focus-start", "focus-group-a", "focus-group-b", "focus-group-d"), specs.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), specs.map { it.rank })
        assertEquals("Alpha Work", specs[1].shortLabel)
        assertEquals("Focus: Alpha Work", specs[1].longLabel)
        assertNull(specs[0].groupId)
        val long = QuickStartShortcuts.specs(listOf(group("l", "An extremely long group name", "a"))).last()
        assertTrue(long.shortLabel.length <= 12 && long.shortLabel.endsWith("…"))
        assertTrue(long.longLabel.length <= 25 && long.longLabel.endsWith("…"))
    }

    @Test fun publishedShortcutsTargetOnlyTheNonExportedEntryAndRefreshWithGroups() {
        assertTrue(QuickStartShortcuts.publish(context, QuickStartShortcuts.specs(listOf(group("w", "Work", "a.app")))))
        val published = ShortcutManagerCompat.getDynamicShortcuts(context).associateBy { it.id }
        assertEquals(setOf("focus-start", "focus-group-w"), published.keys)
        for (info in published.values) {
            assertEquals(QuickStartActivity::class.java.name, info.intent.component?.className)
            assertEquals(QuickStartIntents.ACTION_START_FOCUS, info.intent.action)
        }
        assertEquals("w", published.getValue("focus-group-w").intent.getStringExtra(QuickStartIntents.EXTRA_GROUP_ID))
        assertNull(published.getValue("focus-start").intent.getStringExtra(QuickStartIntents.EXTRA_GROUP_ID))

        // Unchanged content is not republished; a deleted group drops out on the next change.
        assertTrue(QuickStartShortcuts.publish(context, QuickStartShortcuts.specs(listOf(group("w", "Work", "a.app")))))
        assertTrue(QuickStartShortcuts.publish(context, QuickStartShortcuts.specs(emptyList())))
        assertEquals(listOf("focus-start"), ShortcutManagerCompat.getDynamicShortcuts(context).map { it.id })
    }

    @Test fun unavailableGroupStoragePublishesNothing() = runBlocking {
        val emitted = QuickStartShortcuts.readySpecs(flowOf(
            StorageState.Loading,
            StorageState.Unavailable(StorageFailure.IO),
            StorageState.Ready(listOf(group("w", "Work", "a.app"))),
            StorageState.Ready(listOf(group("w", "Work", "a.app"))),
        )).toList()
        assertEquals(1, emitted.size)
        assertEquals(listOf("focus-start", "focus-group-w"), emitted.single().map { it.id })
    }
}
