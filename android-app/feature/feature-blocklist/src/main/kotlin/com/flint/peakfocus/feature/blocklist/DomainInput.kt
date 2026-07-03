package com.flint.peakfocus.feature.blocklist

/**
 * Normalizes what a user typed into the website field down to the bare domain the engine
 * matches on, or returns null when it can't be a domain.
 *
 * `BlockDecisionEngine.domainMatches` compares the URL's host (lowercased, `www.`-stripped)
 * against the stored domain with equality-or-subdomain semantics, so we store exactly that
 * shape: lowercase, no scheme, no `www.`, no path/query/fragment/port, no trailing dot.
 * Subdomains ("m.youtube.com") are kept as typed — narrower blocks are legitimate.
 *
 * ASCII-only by design: the engine matches hosts as plain strings and browsers expose
 * internationalized hosts in punycode ("xn--…"), which is ASCII and passes through fine;
 * accepting raw unicode here would create rules that never match. Pure Kotlin, JVM-tested.
 */
internal fun normalizeDomain(raw: String): String? {
    var s = raw.trim().lowercase()
    val schemeIndex = s.indexOf("://")
    if (schemeIndex >= 0) s = s.substring(schemeIndex + 3)
    s = s.substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringBefore(':')
    s = s.removePrefix("www.")
    s = s.trimEnd('.')
    if (s.isEmpty()) return null
    if ('.' !in s) return null
    if (!s.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return null
    val labels = s.split('.')
    if (labels.any { it.isEmpty() }) return null
    if (labels.any { it.startsWith("-") || it.endsWith("-") }) return null
    return s
}
