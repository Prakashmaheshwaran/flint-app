package com.flint.peakfocus.feature.blocklist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for website-field normalization. The stored shape must match what
 * `BlockDecisionEngine.extractHost` produces from live browser URLs (lowercase, no scheme,
 * no www, no path/port) or the rule would never fire.
 */
class DomainInputTest {

    @Test
    fun `lowercases and strips scheme www path query fragment`() {
        assertEquals("youtube.com", normalizeDomain("HTTPS://WWW.YouTube.com/watch?v=abc#t=1"))
        assertEquals("youtube.com", normalizeDomain("http://youtube.com/"))
        assertEquals("reddit.com", normalizeDomain("reddit.com/r/all"))
        assertEquals("example.com", normalizeDomain("example.com?q=1"))
    }

    @Test
    fun `strips ports with and without scheme`() {
        assertEquals("youtube.com", normalizeDomain("youtube.com:443"))
        assertEquals("youtube.com", normalizeDomain("https://youtube.com:443/x"))
    }

    @Test
    fun `keeps subdomains other than www`() {
        assertEquals("m.twitch.tv", normalizeDomain("m.twitch.tv"))
        assertEquals(
            "news.ycombinator.com",
            normalizeDomain("https://news.ycombinator.com/item?id=1"),
        )
    }

    @Test
    fun `trims whitespace and a trailing dot`() {
        assertEquals("reddit.com", normalizeDomain("  reddit.com.  "))
    }

    @Test
    fun `accepts digits and inner hyphens`() {
        assertEquals("9gag.com", normalizeDomain("9gag.com"))
        assertEquals("my-site.co.uk", normalizeDomain("my-site.co.uk"))
    }

    @Test
    fun `accepts punycode - the ascii form browsers expose`() {
        assertEquals("xn--mnchen-3ya.de", normalizeDomain("xn--mnchen-3ya.de"))
    }

    @Test
    fun `rejects text that is not a domain`() {
        assertNull(normalizeDomain(""))
        assertNull(normalizeDomain("   "))
        assertNull(normalizeDomain("localhost"))
        assertNull(normalizeDomain("not a domain"))
        assertNull(normalizeDomain("https://"))
        assertNull(normalizeDomain("www."))
    }

    @Test
    fun `rejects raw unicode - it would never match a punycoded host`() {
        assertNull(normalizeDomain("héllo.com"))
    }

    @Test
    fun `rejects wildcard and malformed label shapes`() {
        assertNull(normalizeDomain("*.youtube.com"))
        assertNull(normalizeDomain(".youtube.com"))
        assertNull(normalizeDomain("you..tube.com"))
        assertNull(normalizeDomain("-bad.com"))
        assertNull(normalizeDomain("bad-.com"))
    }
}
