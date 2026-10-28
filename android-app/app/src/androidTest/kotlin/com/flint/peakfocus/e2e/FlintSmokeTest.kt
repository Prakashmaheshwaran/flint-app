package com.flint.peakfocus.e2e

import android.app.Activity
import android.app.UiAutomation
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
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
class FlintSmokeTest {
    @get:Rule val testName = TestName()
    private lateinit var device: UiDevice
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app = "com.flint.peakfocus"
    private val target = "$app.test"
    private val service = "$app/com.flint.peakfocus.blocking.accessibility.FlintAccessibilityService"
    private val timeout = 15_000L

    @Before fun resetDisposableApp() {
        // UIAutomator must not suppress the very AccessibilityService under test.
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        device = UiDevice.getInstance(instrumentation)
        assertEquals("Tests must run in their own package", target, instrumentation.targetContext.packageName)
        assertEquals("Only disposable emulators are supported", "1", shell("getprop ro.kernel.qemu").trim())
        assertEquals("Explicit disposable consent required", "true",
            InstrumentationRegistry.getArguments().getString("flintDisposable"))
        disableService()
        assertTrue(shell("pm clear $app").contains("Success"))
        launchFlint()
        click("Begin")
        visible(By.text("Today"))
    }

    @After fun captureAndReset() {
        if (!::device.isInitialized) return
        screenshot("final")
        disableService()
        shell("am force-stop $app")
    }

    @Test fun onboardingConsentRendersAndDeclines() {
        scrollTo("Enable accessibility service").click()
        visible(By.text("Before Flint can block apps"))
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
        click("Use an Emergency Pass")
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
        visible(By.text("Time limit"))
        screenshot("time-limit")
    }

    private fun createRule(name: String, hardcore: Boolean = false) {
        clickTab("Rules")
        visible(By.desc("New block rule")).click()
        visible(By.text("Rule name"))
        visible(By.clazz("android.widget.EditText")).text = name
        device.pressBack() // dismiss keyboard
        click("Choose apps")
        visible(By.clazz("android.widget.EditText")).text = "Flint E2E Target"
        visible(By.text("Flint E2E Target")).click()
        device.pressBack() // dismiss search keyboard
        click("Add · 1")
        if (hardcore) scrollTo("Hardcore").click()
        scrollTo("Save rule").click()
        visible(By.desc("New block rule"))
        visible(By.text(name))
        screenshot("saved-rule")
    }

    private fun clickTab(name: String) {
        // Tab semantics have a stable content description; text is merged into the tab.
        val tab = device.wait(Until.findObject(By.desc(name)), timeout)
            ?: visible(By.text(name))
        tab.click()
    }

    private fun enableService() {
        shell("settings put secure enabled_accessibility_services $service")
        shell("settings put secure accessibility_enabled 1")
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (shell("dumpsys activity services $service").contains("app=ProcessRecord")) return
            SystemClock.sleep(200)
        }
        fail("Flint AccessibilityService did not bind: " + shell("dumpsys accessibility"))
    }

    private fun disableService() {
        shell("settings put secure enabled_accessibility_services null")
        shell("settings put secure accessibility_enabled 0")
    }

    private fun launchFlint() {
        shell("am start -W -n $app/.MainActivity")
    }

    private fun launchTarget() {
        shell("am start -W -n $target/com.flint.peakfocus.e2e.BlockedTestActivity")
    }

    private fun shield() {
        visible(By.text("Go back"))
        visible(By.text("Flint E2E Target is resting."))
    }

    private fun visible(selector: BySelector): UiObject2 =
        device.wait(Until.findObject(selector), timeout)
            ?: throw AssertionError("UI did not show $selector")

    private fun click(text: String) = visible(By.text(text)).click()

    private fun scrollTo(text: String): UiObject2 {
        repeat(8) {
            device.findObject(By.text(text))?.let { return it }
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4,
                device.displayWidth / 2, device.displayHeight / 3, 35)
            SystemClock.sleep(250)
        }
        return visible(By.text(text))
    }

    private fun shell(command: String): String = device.executeShellCommand(command)

    private fun screenshot(stage: String) {
        val directory = File(instrumentation.context.getExternalFilesDir(null), "smoke").apply { mkdirs() }
        val name = "${testName.methodName}-$stage"
        device.takeScreenshot(File(directory, "$name.png"))
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
}
