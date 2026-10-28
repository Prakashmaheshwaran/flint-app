package com.flint.peakfocus.e2e

import android.app.Activity
import android.app.UiAutomation
import android.os.Build
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
    private var ownsSetup = false
    private var ownsGrant = false
    private var previousGlobal = "0"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app = "com.flint.peakfocus"
    private val target = "$app.test"
    private val service = "$app/com.flint.peakfocus.blocking.accessibility.FlintAccessibilityService"
    private val timeout = 15_000L
    private val runToken get() = InstrumentationRegistry.getArguments().getString("flintRunToken")!!
    private val bootId get() = InstrumentationRegistry.getArguments().getString("flintBootId")!!
    private val grantMarker get() = File(instrumentation.context.getExternalFilesDir(null), "owned-service.txt")

    @Before fun resetDisposableApp() {
        // UIAutomator must not suppress the very AccessibilityService under test.
        Configurator.getInstance().uiAutomationFlags = UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
        device = UiDevice.getInstance(instrumentation)
        assertEquals("Tests must run in their own package", target, instrumentation.targetContext.packageName)
        assertEquals("Only disposable emulators are supported", "1", shell("getprop ro.kernel.qemu").trim())
        assertEquals("Explicit disposable consent required", "true",
            InstrumentationRegistry.getArguments().getString("flintDisposable"))
        assertEquals("Exact boot incarnation required", bootId, shell("cat /proc/sys/kernel/random/boot_id").trim())
        assertTrue("Run token required", runToken.matches(Regex("[0-9a-f-]{36}")))
        val enabled = shell("settings get secure enabled_accessibility_services").trim()
        assertTrue("Refusing to replace existing accessibility services", enabled.isEmpty() || enabled == "null")
        ownsSetup = true
        assertTrue(shell("pm clear $app").contains("Success"))
        launchFlint()
        click("Begin")
        visible(By.text("Today"))
    }

    @After fun captureAndReset() {
        if (!ownsSetup) return
        try {
            screenshot("final")
        } finally {
            disableService()
            shell("am force-stop $app")
        }
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
        visible(By.text("TIME LIMIT"))
        screenshot("time-limit")
    }

    private fun createRule(name: String, hardcore: Boolean = false) {
        clickTab("Rules")
        visible(By.desc("New block rule")).click()
        visible(By.text("Rule name"))
        visible(By.clazz("android.widget.EditText")).click()
        visible(By.clazz("android.widget.EditText").focused(true)).text = name
        device.pressBack() // dismiss keyboard
        click("Choose apps")
        // Wait for the destination before resolving its rows; the outgoing editor
        // also has an EditText. Select the unique package row, not search text.
        visible(By.text("Choose apps to block"))
        scrollTo(target).click()
        click("Add · 1")
        if (hardcore) scrollTo("Hardcore").click()
        scrollTo("Save rule").click()
        visible(By.desc("New block rule"))
        visible(By.text(name))
        screenshot("saved-rule")
    }

    private fun clickTab(name: String) {
        // The header and tab may share text; the bottom-most match is the navigation tab.
        val matches = device.wait(Until.findObjects(By.text(name)), timeout)
        check(!matches.isNullOrEmpty()) { "Missing $name tab" }
        matches.maxBy { it.visibleBounds.bottom }.click()
    }

    private fun enableService() {
        assertEquals(bootId, shell("cat /proc/sys/kernel/random/boot_id").trim())
        val current = shell("settings get secure enabled_accessibility_services").trim()
        assertTrue("No existing service may be overwritten", current.isEmpty() || current == "null")
        previousGlobal = shell("settings get secure accessibility_enabled").trim()
        grantMarker.writeText(runToken)
        ownsGrant = true
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
        if (!ownsGrant || shell("cat /proc/sys/kernel/random/boot_id").trim() != bootId) return
        val remaining = shell("settings get secure enabled_accessibility_services").trim()
            .split(":").filter { it.isNotBlank() && it != "null" && it != service }
        shell("settings put secure enabled_accessibility_services ${remaining.joinToString(":").ifEmpty { "null" }}")
        if (remaining.isEmpty()) {
            if (previousGlobal == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $previousGlobal")
        }
        grantMarker.delete()
        ownsGrant = false
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

    private fun visible(selector: BySelector): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            refreshAccessibilityCache()
            device.findObject(selector)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("UI did not show $selector")
    }

    private fun refreshAccessibilityCache() {
        // API 36 retained Add · 0/disabled AX nodes after pixels showed Add · 1
        // enabled. Read fresh nodes during readiness polling, without replaying input
        // or changing UiAutomation's accessibility-service preservation flag.
        if (Build.VERSION.SDK_INT >= 34) {
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).clearCache()
        }
    }

    private fun click(text: String) = visible(By.text(text)).click()

    private fun scrollTo(text: String): UiObject2 {
        repeat(8) {
            refreshAccessibilityCache()
            device.findObject(By.text(text))?.let { return it }
            // Editors have a fixed footer; a screen-relative swipe can start
            // outside the LazyColumn and never move it. Use its actual viewport.
            val bounds = visible(By.scrollable(true)).visibleBounds
            device.swipe(bounds.centerX(), bounds.bottom - bounds.height() / 4,
                bounds.centerX(), bounds.top + bounds.height() / 4, 35)
            SystemClock.sleep(250)
        }
        return visible(By.text(text))
    }

    private fun shell(command: String): String = device.executeShellCommand(command)

    private fun screenshot(stage: String) {
        val directory = File(instrumentation.context.getExternalFilesDir(null), "smoke").apply { mkdirs() }
        val name = "${testName.methodName}-$stage"
        device.takeScreenshot(File(directory, "$name.png"))
        refreshAccessibilityCache()
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
}
