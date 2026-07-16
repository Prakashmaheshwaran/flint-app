package com.flint.peakfocus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSearchTest {
    private val apps = listOf(
        AppEntry("com.instagram.android", "Instagram"),
        AppEntry("com.zhiliaoapp.musically", "TikTok"),
        AppEntry("com.google.android.youtube", "YouTube"),
        AppEntry("com.android.chrome", "Chrome"),
    )

    @Test
    fun `blank query returns the original list`() {
        assertSame(apps, filterApps(apps, ""))
        assertSame(apps, filterApps(apps, "   "))
    }

    @Test
    fun `matches labels case-insensitively`() {
        assertEquals(listOf("TikTok"), filterApps(apps, "TIKT").map { it.label })
    }

    @Test
    fun `matches package names`() {
        assertEquals(listOf("TikTok"), filterApps(apps, "musically").map { it.label })
    }

    @Test
    fun `trims input and preserves incoming order`() {
        assertEquals(listOf("Instagram"), filterApps(apps, " instagram ").map { it.label })
        assertEquals(listOf("Instagram", "YouTube", "Chrome"), filterApps(apps, "android").map { it.label })
    }

    @Test
    fun `no match is empty`() {
        assertTrue(filterApps(apps, "flappy bird").isEmpty())
    }
}
