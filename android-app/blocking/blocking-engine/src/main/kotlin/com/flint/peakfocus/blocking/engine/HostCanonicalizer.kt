package com.flint.peakfocus.blocking.engine

/**
 * Reduces browser address-bar text — and stored rule domains — to one comparable host shape:
 * lowercase, no scheme, no userinfo, no port, no path/query/fragment, no `www.`, no root dot.
 *
 * Both sides of a website match run through here, which is the point: a rule stored as
 * `reddit.com` and an omnibox reading `https://WWW.Reddit.com./r/all` have to land on the same
 * string or the rule silently stops firing. Every step below exists because a browser accepts
 * some spelling of a host that naive splitting reads as a *different* host:
 *
 *  - **Scheme only when it precedes the authority.** Chrome elides `https://` in the omnibox, so
 *    the displayed text of a link-sharing page is `reddit.com/submit?url=https://example.com`.
 *    Splitting on the first `://` found anywhere reads the host as `example.com` — the blocked
 *    site walks straight through, and symmetrically `google.com/search?q=https://reddit.com`
 *    gets shielded as if it were Reddit. The scheme is matched anchored, per RFC 3986.
 *  - **Userinfo.** `https://evil.com@reddit.com/` loads Reddit; the host is what follows the
 *    last `@`, not the first thing that looks like a domain.
 *  - **Root dot.** `reddit.com.` is the fully-qualified spelling of `reddit.com` and resolves
 *    identically. Rule domains are already stored without it (`normalizeDomain`).
 *  - **Backslashes.** The URL spec maps `\` onto `/`, so `reddit.com\r\all` is a Reddit path.
 *  - **Tab/newline/CR.** Stripped from URLs before parsing, so they can't be used to split a host.
 *
 * Hosts never contain whitespace, so text that still does after canonicalization is an omnibox
 * search query rather than an address ("quit .reddit.com" must not trip a `reddit.com` rule).
 *
 * IDNA is deliberately out of scope: unicode spellings of a host (fullwidth dots, confusables)
 * are the browser's to resolve, and what it then *displays* is the punycoded ASCII host we read.
 * Rule domains are ASCII-only for the same reason — see `normalizeDomain` in feature-blocklist.
 *
 * No Android dependencies and no `java.net.URI` — this is a *display-string* parser, not an RFC
 * parser, and it must stay total (never throw) on whatever a browser puts in the bar.
 */
internal object HostCanonicalizer {

    /** RFC 3986 scheme, anchored: it counts only when the very next thing is the authority. */
    private val SCHEME = Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://")

    /** Characters the URL spec removes before parsing. */
    private const val STRIPPED = "\t\n\r"

    fun canonicalize(raw: String): String? {
        var s = raw.filterNot { it in STRIPPED }.trim()
        SCHEME.find(s)?.let { s = s.substring(it.value.length) }

        // Authority runs to the first delimiter; `\` is a path separator too.
        s = s.replace('\\', '/').substringBefore('/').substringBefore('?').substringBefore('#')

        // Credentials: the host is whatever follows the last '@'.
        val userinfoEnd = s.lastIndexOf('@')
        if (userinfoEnd >= 0) s = s.substring(userinfoEnd + 1)

        // Port — but the colons inside a bracketed IPv6 literal are part of the host.
        s = if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close < 0) return null else s.substring(0, close + 1)
        } else {
            s.substringBefore(':')
        }

        // Dots at either end are never part of a label: a trailing one is the DNS root, and a
        // leading one only ever shows up in a hand-written rule ("`.reddit.com`"), which would
        // otherwise be unmatchable — `endsWith(".$d")` would be asking for `..reddit.com`.
        s = s.lowercase().removePrefix("www.").trim('.')
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        return s
    }
}
