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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/** Shared guarded fixture; contains no test methods and no production shortcuts. */
abstract class FlintE2EFixture {
    @get:Rule val testName = TestName()
    protected lateinit var device: UiDevice
    private var ownsSetup = false
    private var ownsGrant = false
    private var previousGlobal = "0"
    protected val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    protected val app = "com.flint.peakfocus"
    protected val target = "$app.test"
    protected val service = "$app/com.flint.peakfocus.blocking.accessibility.FlintAccessibilityService"
    protected val timeout = 15_000L
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
        settleSystemBeforeReset()
        assertTrue(shell("pm clear $app").contains("Success"))
        launchFlint()
        click("Begin")
        visible(By.text("Today"))
    }

    private fun setupEvent(event: String, detail: String) {
        val directory = File(instrumentation.context.getExternalFilesDir(null), "smoke").apply { mkdirs() }
        File(directory, "setup-events.jsonl").appendText(JSONObject()
            .put("test", testName.methodName).put("runToken", runToken).put("uptimeMs", SystemClock.uptimeMillis())
            .put("event", event).put("detail", detail).toString() + "\n")
    }

    /** Bounded setup only. No recovery is called by visible(), clicks or test bodies. */
    private fun settleSystemBeforeReset() {
        val configurator = Configurator.getInstance()
        val previousIdleTimeout = configurator.waitForIdleTimeout
        configurator.waitForIdleTimeout = 500
        setupEvent("setup-start", "Checking launcher and System UI before clearing Flint")
        try {
            val component = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
                .lineSequence().lastOrNull { it.contains('/') }?.trim()
            val launcher = requireNotNull(component?.substringBefore('/')) { "Cannot resolve the launcher: $component" }
            check(launcher.matches(Regex("[a-zA-Z0-9_.]+"))) { "Invalid launcher package: $launcher" }
            val launcherLabel = runCatching {
                val pm = instrumentation.context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(launcher, 0)).toString()
            }.getOrDefault(launcher)
            val deadline = SystemClock.uptimeMillis() + 30_000
            var dismissals = 0
            var homeRequested = false
            var stableSamples = 0
            var previousSystemUi = ""
            do {
                assertEquals("Boot changed during setup", bootId, shell("cat /proc/sys/kernel/random/boot_id").trim())
                refreshAccessibilityCache()
                val title = device.findObject(By.text(java.util.regex.Pattern.compile("(?is).*isn['’]t responding.*")))
                if (title != null) {
                    val text = title.text
                    val wait = device.findObject(By.res("android", "aerr_wait").enabled(true))
                    val close = device.findObject(By.res("android", "aerr_close").enabled(true))
                    val action = SetupAnrPolicy.action(text, launcherLabel, wait != null, close != null)
                    setupEvent("anr-detected", "$text; action=$action")
                    screenshot("setup-anr-${dismissals + 1}")
                    check(action != SetupAnrPolicy.Action.FAIL && dismissals < 2) {
                        "Setup refuses unknown/Flint ANR or more than two dismissals: $text"
                    }
                    val button = checkNotNull(if (action == SetupAnrPolicy.Action.WAIT) wait else close)
                    check(!button.visibleBounds.isEmpty) { "ANR control has no visible bounds" }
                    button.click() // one input for this observed dialog, never replay a failed click
                    dismissals++
                    setupEvent("anr-dismiss-requested", "$text; action=$action; count=$dismissals")
                    check(device.wait(Until.gone(By.text(text)), 5_000)) { "ANR dialog did not dismiss: $text" }
                    setupEvent("anr-dismissed", "$text; action=$action; count=$dismissals")
                    homeRequested = false
                    stableSamples = 0
                } else {
                    if (!homeRequested) {
                        check(device.pressHome()) { "Home input failed during setup" }
                        homeRequested = true
                    }
                    val systemUi = shell("pidof com.android.systemui").trim()
                    val home = device.findObject(By.pkg(launcher).enabled(true))
                    val responsive = device.currentPackageName == launcher && home != null &&
                        !home.visibleBounds.isEmpty && systemUi.isNotEmpty()
                    stableSamples = if (responsive && systemUi == previousSystemUi) stableSamples + 1 else 0
                    previousSystemUi = systemUi
                    if (stableSamples >= 2) {
                        setupEvent("launcher-ready", "package=$launcher; systemUiPid=$systemUi; dismissals=$dismissals")
                        return
                    }
                }
                SystemClock.sleep(1_000)
            } while (SystemClock.uptimeMillis() < deadline)
            error("Launcher/System UI did not settle within 30 seconds")
        } catch (failure: Throwable) {
            setupEvent("setup-failed", failure.toString())
            throw failure
        } finally {
            configurator.waitForIdleTimeout = previousIdleTimeout
        }
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

    protected fun clickTab(name: String) {
        // The header and tab may share text; the bottom-most match is the navigation tab.
        val matches = device.wait(Until.findObjects(By.text(name)), timeout)
        check(!matches.isNullOrEmpty()) { "Missing $name tab" }
        matches.maxBy { it.visibleBounds.bottom }.click()
    }

    protected fun enableService() {
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

    protected fun disableService() {
        if (!ownsGrant || shell("cat /proc/sys/kernel/random/boot_id").trim() != bootId) return
        val remaining = shell("settings get secure enabled_accessibility_services").trim()
            .split(":").filter { it.isNotBlank() && it != "null" && it != service }
        shell("settings put secure enabled_accessibility_services ${remaining.joinToString(":").ifEmpty { "null" }}")
        if (remaining.isEmpty()) {
            if (previousGlobal == "null") shell("settings delete secure accessibility_enabled")
            else shell("settings put secure accessibility_enabled $previousGlobal")
        }
        // Settings writes are asynchronous. Force-stopping while the manager still
        // considers Flint enabled can mark its service crashed for the next test.
        // Observe removal once; never repeat the grant mutation or force-stop early.
        val deadline = SystemClock.uptimeMillis() + timeout
        var lastDump = "not sampled"
        do {
            assertEquals("Boot changed during service cleanup", bootId,
                shell("cat /proc/sys/kernel/random/boot_id").trim())
            lastDump = shell("dumpsys accessibility")
            if (listOf("Enabled", "Bound", "Binding", "Crashed").all { state ->
                    val section = managerServiceState(lastDump, state)
                    !section.contains("$app/") && !section.contains("FlintAccessibilityService")
                }) {
                grantMarker.delete()
                ownsGrant = false
                return
            }
            SystemClock.sleep(200)
        } while (SystemClock.uptimeMillis() < deadline)
        fail("Accessibility manager did not remove Flint before force-stop: $lastDump")
    }

    private fun managerServiceState(dump: String, state: String): String {
        val headings = Regex("(?m)^\\s*${state} services:\\s*\\{").findAll(dump).toList()
        check(headings.size == 1) { "Expected one $state services state: $dump" }
        val start = headings.single().range.last
        var depth = 0
        for (index in start until dump.length) {
            when (dump[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return dump.substring(start, index + 1)
            }
        }
        error("Unterminated $state services state: $dump")
    }

    protected fun launchFlint() {
        shell("am start -W -n $app/.MainActivity")
    }

    protected fun launchTarget() {
        shell("am start -W -n $target/com.flint.peakfocus.e2e.BlockedTestActivity")
    }

    protected fun shield() {
        visible(By.text("Go back"))
        visible(By.text("Flint E2E Target is resting."))
        // Semantics precede entrance paint (six 75 ms staggers + 300 ms fade).
        // One presentation settle makes screenshots reviewable without replaying input.
        SystemClock.sleep(1_000)
    }

    protected fun visible(selector: BySelector): UiObject2 {
        val deadline = SystemClock.uptimeMillis() + timeout
        do {
            refreshAccessibilityCache()
            device.findObject(selector)?.let { return it }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("UI did not show $selector")
    }

    protected fun refreshAccessibilityCache() {
        // API 36 retained Add · 0/disabled AX nodes after pixels showed Add · 1
        // enabled. Read fresh nodes during readiness polling, without replaying input
        // or changing UiAutomation's accessibility-service preservation flag.
        if (Build.VERSION.SDK_INT >= 34) {
            instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).clearCache()
        }
    }

    protected fun click(text: String) = visible(By.text(text)).click()

    protected fun scrollTo(text: String): UiObject2 {
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

    protected fun shell(command: String): String = device.executeShellCommand(command)

    protected fun screenshot(stage: String) {
        val directory = File(instrumentation.context.getExternalFilesDir(null), "smoke").apply { mkdirs() }
        val name = "${testName.methodName}-$stage"
        device.takeScreenshot(File(directory, "$name.png"))
        refreshAccessibilityCache()
        device.dumpWindowHierarchy(File(directory, "$name.xml"))
    }
}
