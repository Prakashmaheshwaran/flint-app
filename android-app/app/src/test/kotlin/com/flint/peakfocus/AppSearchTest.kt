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
    fun `blank query keeps the full list untouched`() {
        assertSame(apps, filterApps(apps, ""))
        assertSame(apps, filterApps(apps, "   "))
    }

    @Test
    fun `matches labels case-insensitively`() {
        assertEquals(listOf("TikTok"), filterApps(apps, "tikt").map { it.label })
        assertEquals(listOf("YouTube"), filterApps(apps, "YOUTU").map { it.label })
    }

    @Test
    fun `matches package names too - the row shows both`() {
        // The label says "TikTok" but the package is the searchable truth for power users.
        assertEquals(listOf("TikTok"), filterApps(apps, "musically").map { it.label })
        assertEquals(listOf("Chrome"), filterApps(apps, "com.android.chrome").map { it.label })
    }

    @Test
    fun `trims the query so a stray space never empties the screen`() {
        assertEquals(listOf("Instagram"), filterApps(apps, " instagram ").map { it.label })
    }

    @Test
    fun `preserves the incoming order of matches`() {
        // "an" hits Instagram (label+pkg), TikTok? no; youtube pkg has "android"; chrome pkg too.
        val hits = filterApps(apps, "android").map { it.label }
        assertEquals(listOf("Instagram", "YouTube", "Chrome"), hits)
    }

    @Test
    fun `no match returns empty`() {
        assertTrue(filterApps(apps, "flappy bird").isEmpty())
    }
}
