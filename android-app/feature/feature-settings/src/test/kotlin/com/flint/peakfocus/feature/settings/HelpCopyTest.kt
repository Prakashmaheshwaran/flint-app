package com.flint.peakfocus.feature.settings

import com.flint.peakfocus.blocking.engine.BrowserAddressBars
import com.flint.peakfocus.blocking.engine.WebCoverage
import com.flint.peakfocus.blocking.resilience.HealthStatus
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

    private val banned = listOf(
        "non-bypassable", "only way out", "only exit", "sole exit", "per week", "this week",
        "monday", "can't be removed", "can't be uninstalled", "prevents uninstall",
        "can't be turned off", "anti-uninstall", "guaranteed", "every browser", "all websites",
    )

    private val tierAndPassTerms = listOf("Hardcore", "Emergency Pass", "Harder")

    /**
     * Every branch of the website-coverage projection Help renders beside its own copy. Unsupported
     * handler labels come from the device, so they use a neutral fixture name here.
     */
    private val websiteCoverageCopy: List<String> by lazy {
        val browsers = BrowserAddressBars.supportedBrowsers
        val states = listOf(WebCoverage.NotRequired, WebCoverage.AwaitingBrowser, WebCoverage.Paused) +
            browsers.flatMap { listOf(WebCoverage.Available(it), WebCoverage.Degraded(it, 1L), WebCoverage.Unconfirmed(it)) } +
            listOf(true, false).map { WebCoverage.Unsupported("fixture.handler", "Fixture handler", isBrowser = it) }
        states.flatMap { coverage ->
            listOf(true, false).map { current ->
                BlockingHealthUi.websiteCoverage(
                    HealthStatus(true, true, true, true, websiteCoverage = coverage, websiteCoverageIsCurrent = current),
                )
            }
        }
    }

    @Test
    fun `every help string referenced by the screen exists`() {
        val referenced = HelpContent.sections.size
        assertEquals(5, referenced)
        listOf(
            "help_section_strictness", "help_strict_easy_title", "help_strict_easy_text",
            "help_strict_harder_title", "help_strict_harder_text", "help_strict_hardcore_title",
            "help_strict_hardcore_text", "help_strict_timers_title", "help_strict_timers_text",
            "help_section_pass", "help_pass_title", "help_pass_text", "help_pass_recovery_title",
            "help_pass_recovery_text", "help_pass_where_title", "help_pass_where_text",
            "help_section_limits", "help_access_title", "help_access_text", "help_safety_title",
            "help_safety_text", "help_uninstall_title", "help_uninstall_text", "help_clock_title", "help_clock_text", "help_section_websites",
            "help_websites_title", "help_websites_text", "help_section_running", "help_battery_title",
            "help_battery_text", "help_privacy", "help_title", "help_intro", "help_back",
        ).forEach { assertTrue("missing $it", strings.containsKey(it)) }
        assertEquals("Website coverage", strings.getValue("help_websites_title"))
        val websites = HelpContent.sections.flatMap { it.facts }.single { it.showsWebsiteCoverage }
        assertEquals(R.string.help_websites_text, websites.text)
        assertEquals(
            "Website rules need Accessibility and a browser whose address bar Flint can read.",
            strings.getValue("help_websites_text"),
        )
    }

    @Test
    fun `no overclaims from ADR-012 copy list`() {
        (helpCopy + websiteCoverageCopy.mapIndexed { i, text -> "websiteCoverage#$i" to text }).forEach { (key, text) ->
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
    fun `clock copy keeps started blocks anchored but future boundaries on the phone clock`() {
        val clock = strings.getValue("help_clock_text")
        assertTrue(clock.contains("a clock change doesn't shorten a block that has already started"))
        assertTrue(clock.contains("Future schedule start times and daily time-limit resets still follow the phone's clock."))
        assertTrue(clock.contains("can't always tell how much time really passed"))
    }

    @Test
    fun `pass copy uses lane C's rolling and recovery wording`() {
        val pass = strings.getValue("help_pass_text")
        assertTrue(pass.contains("One Emergency Pass becomes available seven days after you use it. " +
            "It starts 60 minutes of recovery across all personal protection."))
        val recovery = strings.getValue("help_pass_recovery_text")
        assertTrue(recovery.contains("New schedule occurrences are also suspended during recovery. " +
            "Counters are kept; the pass is not refunded."))
        assertTrue(recovery.contains("only commitments still naturally active will resume"))
        assertTrue(strings.getValue("help_pass_where_text").contains("Review Emergency Pass"))
    }

    @Test
    fun `strictness copy states the enforced waits and Hardcore exits`() {
        assertTrue(strings.getValue("help_strict_harder_text").contains("1 minute the first time in a block, then 5, then 15"))
        assertTrue(strings.getValue("help_strict_easy_text").contains("five-minute break"))
        val hardcore = strings.getValue("help_strict_hardcore_text")
        assertTrue(hardcore.contains("No breaks and no ending early"))
        assertTrue(hardcore.contains("saved for after that block ends"))
        assertTrue(strings.getValue("help_strict_timers_text").contains("2-second hold"))
    }

    @Test
    fun `timed Open credits are not described until they are available on Android`() {
        helpCopy.forEach { (key, text) -> assertFalse("$key mentions credits", text.lowercase().contains("credit")) }
    }

    @Test
    fun `tier and pass terms stay out of the website projection`() {
        websiteCoverageCopy.forEach { text ->
            tierAndPassTerms.forEach { term -> assertFalse("website copy names $term", text.contains(term)) }
        }
    }

    @Test
    fun `website coverage branches all reach Help with the shared qualifications`() {
        // 3 fixed + 3 per known browser + 2 unsupported, each current and historical.
        assertEquals((3 + 3 * BrowserAddressBars.supportedBrowsers.size + 2) * 2, websiteCoverageCopy.size)
        websiteCoverageCopy.forEach { text ->
            assertTrue(text.contains("Known browsers (not verified on this device)"))
            assertTrue(text.contains("Other browsers and the usage-access backup path do not filter websites."))
        }
    }
}
