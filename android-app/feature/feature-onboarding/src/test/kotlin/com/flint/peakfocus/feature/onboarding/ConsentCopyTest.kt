package com.flint.peakfocus.feature.onboarding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class ConsentCopyTest {
    @Test
    fun `page-change disclosure names global signals and supported active-rule processing`() {
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("In the browsers Flint supports, while a website rule is active"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("Android sends page-change signals from other apps too"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("without reading their content"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("when a page changes without opening a new tab"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains(ConsentCopy.URL_HANDLING))
        assertFalse(ConsentCopy.WHAT_IT_READS.contains("window-content"))
        assertFalse(ConsentCopy.WHAT_IT_READS.contains("same-tab"))
    }

    @Test fun `service description and globally requested event set agree with consent`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val resources = File(root, "blocking/blocking-accessibility/src/main/res")
        val description = factory.newDocumentBuilder().parse(File(resources, "values/strings.xml"))
            .getElementsByTagName("string").let { nodes ->
                (0 until nodes.length).map { nodes.item(it) }
                    .first { it.attributes.getNamedItem("name").nodeValue == "flint_a11y_description" }.textContent
            }
        assertTrue(description.contains(ConsentCopy.PAGE_CHANGES))
        val config = factory.newDocumentBuilder().parse(File(resources, "xml/accessibility_service_config.xml")).documentElement
        val ns = "http://schemas.android.com/apk/res/android"
        assertTrue(config.getAttributeNS(ns, "packageNames").isEmpty())
        org.junit.Assert.assertEquals(setOf("typeWindowStateChanged", "typeWindowContentChanged"),
            config.getAttributeNS(ns, "accessibilityEventTypes").split('|').toSet())
        for (relative in listOf("docs/privacy-policy.md", "docs/store/android/accessibility.md", "docs/store/android/data-safety.md")) {
            val text = File(root.parentFile, relative).readText().replace(Regex("\\s+"), " ")
            assertTrue("$relative must agree on URL scope, retention and revocation", text.contains(ConsentCopy.URL_FACTS))
            if (!relative.endsWith("data-safety.md")) {
                assertTrue("$relative must point to the browser list after setup", text.contains(ConsentCopy.URL_HANDLING))
            }
        }
    }

    @Test
    fun `disclosure names the mechanism and data read`() {
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("Accessibility service", ignoreCase = true))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("which app is in the foreground"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("web address"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("known browser address bars"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("Not all browsers or pages"))
        assertTrue(ConsentCopy.WHAT_IT_READS.contains("screen class names"))
        assertFalse(ConsentCopy.WHAT_IT_READS.contains("Settings text"))
        assertFalse(ConsentCopy.WHAT_IT_READS.contains("content descriptions"))
    }

    @Test
    fun `disclosure limits use and sharing`() {
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("only to enforce the blocks you set up"))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("stays on your device", ignoreCase = true))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("never sent off your device"))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("shared, or sold"))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("no account", ignoreCase = true))
        assertTrue(ConsentCopy.WHERE_IT_GOES.contains("no analytics", ignoreCase = true))
    }

    @Test
    fun `consent is explicit optional and describes the actual revocation path`() {
        assertTrue(ConsentCopy.HEADLINE.contains("enabling Accessibility"))
        assertFalse(ConsentCopy.HEADLINE.contains("Before Flint can block apps"))
        assertTrue(ConsentCopy.ACCEPT_LABEL.contains("Accept"))
        assertTrue(ConsentCopy.DECLINE_LABEL.isNotBlank())
        assertFalse(ConsentCopy.DECLINE_LABEL.contains("accept", ignoreCase = true))
        assertTrue(ConsentCopy.NEXT_STEP.contains("turn off the service in Android Accessibility"))
        assertFalse(ConsentCopy.NEXT_STEP.contains("first end or disable"))
        assertTrue(ConsentCopy.NEXT_STEP.contains("backup path"))
        assertFalse(ConsentCopy.NEXT_STEP.contains("turn it off any time", ignoreCase = true))
    }

}
