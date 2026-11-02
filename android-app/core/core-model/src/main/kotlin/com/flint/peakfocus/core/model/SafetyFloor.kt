package com.flint.peakfocus.core.model

/**
 * The enforcement safety floor (audit R2): foreground surfaces that no app rule, allow-list
 * window, schedule, Sleep rule, daily Time Limit or Open Limit may ever shield, on either
 * detector. Blocking any of these can strand the user — the dialer and emergency surfaces are
 * how emergency calls and alerts are reached, the home screen is the way out of every app
 * (shielding it loops Home into the block screen), Settings and the permission controller are
 * where Flint's own grants are recovered, and Flint itself hosts the exit paths.
 *
 * [STATIC_PACKAGES] is the build-time, best-effort OEM list. Detectors add the device's
 * *runtime* surfaces — the resolved default/system dialer, default home app and uninstaller — because a
 * custom default dialer or launcher can be any package. Both sets go through [isProtected], so
 * the engine, the shared block coordinator and the app pickers all ask the same question.
 *
 * Plain Kotlin on purpose: the pure engine, the pickers and the JVM tests share it.
 *
 * There is no uninstall/disable interception on Android (ADR-012 §10): the uninstaller, Settings
 * and permission surfaces are on the floor, so no cause of any kind can shield them.
 */
object SafetyFloor {

    /** Core system chrome: shade/recents, the framework's own resolver and permission dialogs. */
    private val SYSTEM_UI = setOf(
        "android",
        "com.android.systemui",
    )

    /** Settings and the places Flint's grants (Accessibility, Usage Access, overlay,
     *  notifications, battery exemptions) are recovered from, including common OEM managers,
     *  plus the system uninstaller (protection contract §7/§10: uninstall UI stays reachable). */
    private val PERMISSION_RECOVERY = setOf(
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.huawei.systemmanager",
        "com.samsung.android.lool",
        "com.samsung.android.sm",
    )

    /** Telephony and emergency calling / alerts. Never place a real emergency call to test these. */
    private val TELEPHONY_AND_EMERGENCY = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.app.telephonyui",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.samsung.android.incallui",
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.android.cellbroadcastreceiver",
        "com.android.cellbroadcastreceiver.module",
        "com.google.android.cellbroadcastreceiver",
    )

    /** Every statically known floor package. Runtime surfaces are added by the detectors. */
    val STATIC_PACKAGES: Set<String> = SYSTEM_UI + PERMISSION_RECOVERY + TELEPHONY_AND_EMERGENCY

    /**
     * True when [packageName] must never be shielded by an ordinary block cause. A blank
     * package (an empty window event) is never something to block either.
     *
     * @param selfPackage Flint's own package.
     * @param runtimeSurfaces the device's resolved default/system dialer and home apps.
     */
    fun isProtected(
        packageName: String,
        selfPackage: String,
        runtimeSurfaces: Set<String> = emptySet(),
    ): Boolean =
        packageName.isBlank() ||
            packageName == selfPackage ||
            packageName in STATIC_PACKAGES ||
            packageName in runtimeSurfaces
}
