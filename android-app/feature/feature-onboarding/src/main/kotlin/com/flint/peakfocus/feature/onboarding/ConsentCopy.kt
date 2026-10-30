package com.flint.peakfocus.feature.onboarding

/** Policy-sensitive AccessibilityService disclosure copy, isolated for JVM regression tests. */
internal object ConsentCopy {
    const val HEADLINE = "Before enabling Accessibility"
    const val URL_FACTS =
        "Address-bar " +
            "URLs stay on this device, in memory for blocking; they are not saved as browsing " +
            "history. Websites you add to a rule remain in your rules. Address-bar reading stops when Accessibility is off."
    const val URL_HANDLING =
        "After setup, the list of known browsers is on Flint's You health screen. " + URL_FACTS
    const val PAGE_CHANGES =
        "In the browsers Flint supports, while a website rule is active, Flint also re-reads " +
            "the address bar when a page changes without opening a new tab. Android sends " +
            "page-change signals from other apps too; Flint ignores those without reading their content. " + URL_HANDLING
    const val WHAT_IT_READS =
        "Flint uses Android's Accessibility service to " +
            "detect which app is in the foreground using app and screen class names, and " +
            "read the web address from known " +
            "browser address bars for website blocking. Not all browsers or pages expose an address. " +
            PAGE_CHANGES + " " +
            "Flint uses these reads to show its block screen when your app or website rules apply."
    const val WHERE_IT_GOES =
        "Flint uses this only to enforce the blocks you set up. This data stays on " +
            "your device. It is never sent off your device, shared, or sold. Flint's local " +
            "blocking needs no account and no analytics."
    const val NEXT_STEP =
        "Accept to open Android Settings, where you can choose whether to enable “Flint app " +
            "blocking”. To turn it off later, turn off the service in Android Accessibility " +
            "settings. Choosing Not now does not enable the service; " +
            "app blocking may still work through the usage-access and overlay backup path."
    const val ACCEPT_LABEL = "Accept & continue"
    const val DECLINE_LABEL = "Not now"
}
