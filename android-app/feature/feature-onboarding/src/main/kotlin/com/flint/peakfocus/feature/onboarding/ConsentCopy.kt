package com.flint.peakfocus.feature.onboarding

/** Policy-sensitive AccessibilityService disclosure copy, isolated for JVM regression tests. */
internal object ConsentCopy {
    const val HEADLINE = "Before enabling Accessibility"
    const val WHAT_IT_READS =
        "Flint uses Android's Accessibility service to " +
            "detect which app is in the foreground using app and screen class names, and " +
            "read the web address from known " +
            "browser address bars for website blocking. Not all browsers or pages expose an address. " +
            "While an enabled Hardcore rule is active, Flint also reads visible Settings text " +
            "and content descriptions to identify protected screens."
    const val WHERE_IT_GOES =
        "Flint uses this only to enforce the blocks you set up. This data stays on " +
            "your device. It is never sent off your device, shared, or sold. Flint's local " +
            "blocking needs no account and no analytics."
    const val NEXT_STEP =
        "Accept to open Android Settings, where you can choose whether to enable “Flint app " +
            "blocking”. To turn it off later, first end or disable active Hardcore rules inside " +
            "Flint, then turn off the service in Android Accessibility settings. A native Timer " +
            "can be ended with its hold-to-end control. Choosing Not now does not enable the service; " +
            "app blocking may still work through the usage-access and overlay backup path."
    const val GUARD_BEHAVIOR =
        "While any enabled Hardcore rule is active, Flint covers known system uninstallers, " +
            "even when removing another app. It also covers Settings screens about Flint that " +
            "offer uninstall, force stop, clear data, or Accessibility disable controls. If screen " +
            "text is unavailable, a recognized App-info or Accessibility screen may be covered " +
            "even for another app. This guard has no break or Emergency Pass action. It works " +
            "only while Accessibility is running and may miss screens on some devices or languages."
    const val ACCEPT_LABEL = "Accept & continue"
    const val DECLINE_LABEL = "Not now"
}
