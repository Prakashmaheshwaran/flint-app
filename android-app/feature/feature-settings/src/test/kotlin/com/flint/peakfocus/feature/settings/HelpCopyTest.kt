package com.flint.peakfocus.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the "How Flint works" copy against ADR-012 §13 overclaims. Reads the module's own
 * strings.xml (Gradle runs unit tests from the module directory).
 */
class HelpCopyTest {
    private val strings: Map<String, String> by lazy {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/res/values/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent.replace("\\'", "'")
        }
    }

    private val helpCopy get() = strings.filterKeys { it.startsWith("help_") }

    @Test
    fun `every help string referenced by the screen exists`() {
        val referenced = HelpContent.sections.size
        assertEquals(3, referenced)
        listOf(
            "help_section_limits", "help_access_title", "help_access_text", "help_safety_title",
            "help_safety_text", "help_uninstall_title", "help_uninstall_text", "help_clock_title", "help_clock_text", "help_section_websites",
            "help_websites_title", "help_websites_text", "help_section_running", "help_battery_title",
            "help_battery_text", "help_privacy", "help_title", "help_intro", "help_back",
        ).forEach { assertTrue("missing $it", strings.containsKey(it)) }
    }

    @Test
    fun `no overclaims from ADR-012 copy list`() {
        val banned = listOf(
            "non-bypassable", "only way out", "only exit", "sole exit", "per week", "this week",
            "monday", "can't be removed", "can't be uninstalled", "prevents uninstall",
            "can't be turned off", "anti-uninstall", "guaranteed", "every browser", "all websites",
        )
        helpCopy.forEach { (key, text) ->
            banned.forEach { phrase ->
                assertFalse("$key contains '$phrase'", text.lowercase().contains(phrase))
            }
        }
    }

    @Test
    fun `access copy keeps the user's control and its consequence`() {
        val access = strings.getValue("help_access_text")
        assertTrue(access.contains("doesn't block those settings"))
        assertTrue(access.contains("can stop or weaken blocking"))
    }

    @Test
    fun `clock copy does not claim schedules or limits are protected from clock changes`() {
        val clock = strings.getValue("help_clock_text")
        assertTrue(clock.contains("schedules and daily time limits follow the phone's clock"))
    }

    @Test
    fun `no tier or pass promises until lane C's ADR-012 behavior lands`() {
        helpCopy.values.forEach { text ->
            listOf("Hardcore", "Emergency Pass", "Harder").forEach { term ->
                assertFalse("help copy names $term before its behavior is settled", text.contains(term))
            }
        }
    }
}
