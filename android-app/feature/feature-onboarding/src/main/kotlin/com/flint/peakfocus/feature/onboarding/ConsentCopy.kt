package com.flint.peakfocus.feature.onboarding

/**
 * Every word of the prominent-disclosure consent screen (Play AccessibilityService policy,
 * §4.3 / ADR-007). Pulled out of the composable so JVM tests can hold the copy to the policy:
 * what the service reads, what it's used for, where the data goes, and an explicit accept —
 * a compile-green refactor can no longer quietly weaken the compliance gate.
 *
 * Do not weaken this copy. ConsentCopyTest pins the required elements; if a test fails after
 * an edit, the edit — not the test — is almost certainly what's wrong.
 */
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
