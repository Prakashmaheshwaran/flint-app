package com.flint.peakfocus.feature.blocklist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPickerSelectionTest {
    private val chrome = InstalledApp("com.android.chrome", "Chrome", null)
    private val camera = InstalledApp("com.android.camera", "Camera", null)
    private val clock = InstalledApp("com.android.clock", "Clock", null)
    private val apps = listOf(camera, chrome, clock)

    @Test fun `initial choices count only apps the picker can confirm`() {
        val initial = setOf(chrome.packageName, "uninstalled.app")
        val selection = AppPickerSelection(initial)

        assertEquals(listOf(chrome), selection.selectedApps(apps))
        assertFalse(selection.hasChanges(initial, apps))
        assertEquals(2, initial.size) // Unavailable entries remain the caller's responsibility.
    }

    @Test fun `pending count follows zero one two one zero without changing the draft`() {
        val initial = emptySet<String>()
        var selection = AppPickerSelection(initial)
        val counts = mutableListOf(selection.selectedApps(apps).size)
        for ((app, checked) in listOf(chrome to true, camera to true, chrome to false, camera to false)) {
            selection = selection.withSelection(listOf(app.packageName), checked)
            counts += selection.selectedApps(apps).size
            assertTrue(initial.isEmpty())
        }

        assertEquals(listOf(0, 1, 2, 1, 0), counts)
        assertFalse(selection.hasChanges(initial, apps))
    }

    @Test fun `filtered bulk selection keeps choices outside the search results`() {
        val initial = setOf(chrome.packageName)
        var selection = AppPickerSelection(initial)
        val filteredPackages = listOf(camera.packageName, clock.packageName)
        selection = selection.withSelection(filteredPackages, true)
        selection = selection.withSelection(filteredPackages, true)

        assertEquals(3, selection.selectedApps(apps).size)
        assertTrue(selection.hasChanges(initial, apps))

        selection = selection.withSelection(filteredPackages, false)
        assertEquals(listOf(chrome), selection.selectedApps(apps))
        assertFalse(selection.hasChanges(initial, apps))
        assertEquals(selection, selection.withSelection(emptyList(), true))
        assertEquals(selection, selection.withSelection(emptyList(), false))
    }

    @Test fun `current app list controls count and confirmation while retaining pending choices`() {
        val selection = AppPickerSelection(setOf(chrome.packageName, camera.packageName))
        val renamedChrome = chrome.copy(label = "Chrome updated")

        assertEquals(listOf(renamedChrome), selection.selectedApps(listOf(renamedChrome, clock)))
        assertEquals(listOf(camera, renamedChrome), selection.selectedApps(listOf(camera, renamedChrome)))
        assertEquals(emptyList<InstalledApp>(), selection.selectedApps(emptyList()))
        assertEquals(listOf(camera, chrome), selection.selectedApps(apps))
    }

    @Test fun `only available pending edits enable confirmation after an app list update`() {
        val initial = setOf(chrome.packageName)
        val selection = AppPickerSelection(initial).withSelection(listOf(camera.packageName), true)

        assertTrue(selection.hasChanges(initial, apps))
        assertFalse(selection.hasChanges(initial, listOf(chrome, clock)))
        assertFalse(selection.hasChanges(initial, emptyList()))
        assertTrue(selection.hasChanges(initial, apps))
    }

    @Test fun `removing the last initial selection is a confirmable empty choice`() {
        val initial = setOf(chrome.packageName)
        val selection = AppPickerSelection(initial).withSelection(listOf(chrome.packageName), false)

        assertTrue(selection.hasChanges(initial, apps))
        assertTrue(selection.selectedApps(apps).isEmpty())
        assertEquals(listOf(chrome), AppPickerSelection(initial).selectedApps(apps))
    }
}
