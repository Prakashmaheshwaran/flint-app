package com.flint.peakfocus.e2e

import android.app.Activity
import android.app.UiAutomation
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import android.view.accessibility.AccessibilityWindowInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** Only installed in the test APK. Its process also hosts the black-box test runner. */
class BlockedTestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "E2E target is open"
            textSize = 24f
        })
    }
}

/** Real UI + accessibility adapter smoke tests, exclusively on disposable emulators. */
@RunWith(AndroidJUnit4::class)
class FlintSmokeTest : FlintE2EFixture() {
    @Test fun onboardingConsentRendersAndDeclines() {
        scrollTo("Enable accessibility service").click()
        visible(By.text("Before enabling Accessibility"))
        scrollTo("Accept & continue")
        visible(By.text("Not now"))
        screenshot("consent")
        click("Not now")
        visible(By.text("Today"))
        assertFalse(shell("settings get secure enabled_accessibility_services").contains(service))
    }

    @Test fun authoredRuleSurvivesProcessRestartAndShieldBreakWorks() {
        createRule("E2E persistent rule")
        enableService()
        launchTarget()
        shield()
        screenshot("shield-before-restart")
        val before = shell("pidof $app").trim()
        assertTrue("Flint process was running", before.isNotEmpty())
        // Stop only Flint, while the separate test process stays alive.
        disableService()
        shell("am force-stop $app")
        assertTrue("Flint process stopped", shell("pidof $app").trim().isEmpty())
        launchFlint()
        clickTab("Rules")
        visible(By.text("E2E persistent rule"))
        assertNotEquals("Fresh process reads persisted rule", before, shell("pidof $app").trim())
        enableService()
        launchTarget()
        shield()
        click("Take a break")
        assertTrue("Break dismisses actual shield", device.wait(Until.gone(By.text("Go back")), timeout))
        visible(By.text("E2E target is open"))
        screenshot("break-open")
    }

    @Test fun hardcoreShieldEmergencyPassWorks() {
        createRule("E2E pass rule", hardcore = true)
        enableService()
        launchTarget()
        shield()
        assertFalse(device.hasObject(By.text("Take a break")))
        click("Review Emergency Pass")
        visible(By.text("Protection recovery"))
        click("Review Emergency Pass")
        visible(By.text("Confirm global recovery"))
        click("Confirm 60 minutes of recovery")
        visible(By.textStartsWith("Recovery ends in"))
        // Recovery is a Flint surface; re-enter the target to verify the committed release.
        launchTarget()
        assertTrue("Pass dismisses actual shield", device.wait(Until.gone(By.text("Go back")), timeout))
        visible(By.text("E2E target is open"))
        screenshot("pass-open")
    }

    @Test fun exhaustedDailyBudgetShowsTimeLimitShield() {
        // The existing DEBUG receiver seeds an already exhausted zero-minute legacy budget.
        // This proves adapter -> cause -> shield wiring, not elapsed UsageStats accounting.
        val result = shell("am broadcast -n $app/.SetBlocklistReceiver --es limitPkg $target --ei limitMin 0")
        assertTrue(result, result.contains("Broadcast completed"))
        enableService()
        launchTarget()
        shield()
        visible(By.text("TIME LIMIT"))
        screenshot("time-limit")
    }

    private fun createRule(name: String, hardcore: Boolean = false) {
        if (hardcore) {
            // ADR-012 requires a finite new Hardcore commitment. The pass smoke can
            // use the native Timer without depending on the runner's local clock/zone.
            clickTab("Today")
            scrollTo("Start focus").click()
            visible(By.desc("25 minutes")).click()
            scrollTo("Choose what rests").click()
            visible(By.text("What rests"))
            scrollTo(target).click()
            click("Add · 1")
            scrollTo("Hardcore").click()
            scrollTo("Begin 25m").click()
            visible(By.textStartsWith("ends at "))
            screenshot("saved-rule")
            return
        }
        clickTab("Rules")
        visible(By.desc("New block rule")).click()
        visible(By.text("Rule name"))
        visible(By.clazz("android.widget.EditText")).click()
        visible(By.clazz("android.widget.EditText").focused(true)).text = name
        waitForImeVisibility(true)
        device.pressBack() // exactly one Back, only after a visible keyboard is proven
        waitForImeVisibility(false)
        visible(By.text("Rule name"))
        visible(By.text("Choose apps"))
        click("Choose apps")
        // Wait for the destination before resolving its rows; the outgoing editor
        // also has an EditText. Select the unique package row, not search text.
        visible(By.text("Choose apps to block"))
        scrollTo(target).click()
        click("Add · 1")
        scrollTo("Save rule").click()
        click("Confirm")
        visible(By.desc("New block rule"))
        visible(By.text(name))
        screenshot("saved-rule")
    }

    private fun waitForImeVisibility(expected: Boolean) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val serviceInfo = checkNotNull(automation.serviceInfo) { "UiAutomation service info unavailable" }
        val retrieveWindows = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        if (serviceInfo.flags and retrieveWindows == 0) {
            serviceInfo.flags = serviceInfo.flags or retrieveWindows
            automation.serviceInfo = serviceInfo
        }
        val deadline = SystemClock.uptimeMillis() + timeout
        var lastWindows = "not sampled"
        var previousImeWindows: List<String>? = null
        var stableSamples = 0
        do {
            refreshAccessibilityCache()
            val windows = automation.windows
            val imeWindows = mutableListOf<String>()
            lastWindows = windows.joinToString("\n") { window ->
                val bounds = Rect().also(window::getBoundsInScreen)
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD && !bounds.isEmpty) {
                    imeWindows += "${window.id}:$bounds"
                }
                "id=${window.id} type=${window.type} active=${window.isActive} bounds=$bounds"
            }
            val currentImeWindows = imeWindows.sorted()
            if (currentImeWindows.isNotEmpty() == expected) {
                stableSamples = if (currentImeWindows == previousImeWindows) stableSamples + 1 else 1
                previousImeWindows = currentImeWindows
            } else {
                stableSamples = 0
                previousImeWindows = null
            }
            if (stableSamples >= 2) return
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        val directory = File(instrumentation.context.getExternalFilesDir(null), "smoke").apply { mkdirs() }
        File(directory, "${testName.methodName}-ime-timeout.txt").writeText(
            "Expected visible IME=$expected\nWindows:\n$lastWindows\nInput method:\n" + shell("dumpsys input_method"),
        )
        fail("IME visibility did not become $expected; windows=$lastWindows")
    }

}
