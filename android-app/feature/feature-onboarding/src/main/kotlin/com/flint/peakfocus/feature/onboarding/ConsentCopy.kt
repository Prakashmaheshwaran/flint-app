package com.flint.peakfocus.feature.onboarding

/** Policy-sensitive AccessibilityService disclosure copy, isolated for JVM regression tests. */
internal object ConsentCopy {
    const val HEADLINE = "Before Flint can block apps"
    const val WHAT_IT_READS =
        "To block apps in real time, Flint uses Android's Accessibility service to " +
            "detect which app is in the foreground and — for website blocking — to read " +
            "the web address shown in your browser."
    const val WHERE_IT_GOES =
        "Flint uses this only to enforce the blocks you set up. This data stays on " +
            "your device. It is never sent off your device, shared, or sold. Flint has no " +
            "account and no analytics."
    const val NEXT_STEP =
        "On the next screen, enable “Flint app blocking” under Accessibility. You can " +
            "turn it off any time in Settings."
    const val ACCEPT_LABEL = "Accept & continue"
    const val DECLINE_LABEL = "Not now"
}
