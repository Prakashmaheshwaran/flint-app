package com.flint.peakfocus.blocking.engine

import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bypass regression suite for website rules.
 *
 * Each `bypass…` case is a spelling of a blocked host that a browser resolves to that host but
 * that naive splitting read as a *different* host — i.e. the site loaded and the shield never
 * came up. Each `falsePositive…` case is the mirror image: an innocent page that got shielded.
 * They are written against [BlockDecisionEngine.decide], the entry point both detection paths
 * call, so a regression anywhere under it fails here.
 *
 * The address-bar strings are what Chrome actually *displays*: it elides `https://` and `www.`,
 * which is what makes the embedded-URL cases reachable by simply visiting a page.
 */
class WebsiteMatchingTest {

    private val engine = BlockDecisionEngine()
    private val self = "com.flint.peakfocus"
    private val chrome = "com.android.chrome"

    private fun rules(vararg domains: String) = listOf(
        BlockRule(
            id = "r1",
            name = "sites",
            targets = BlockTargets(domains = domains.map { DomainRef(it) }.toSet()),
        ),
    )

    private fun blocks(url: String, domain: String = "reddit.com"): Boolean =
        engine.decide(chrome, url, rules(domain), self) is Verdict.Block

    // MARK: bypasses — the blocked site must not load

    @Test
    fun `bypass - embedded url in the query does not move the host`() {
        // Chrome's omnibox shows this with the scheme elided, so the first "://" in the string
        // belongs to the *query*. Reading the host from it hands the user reddit.com unblocked.
        assertTrue(blocks("reddit.com/submit?url=https://example.com"))
        assertTrue(blocks("reddit.com/r/all#go=https://example.com"))
        assertTrue(blocks("old.reddit.com/x?next=http://example.com/y"))
    }

    @Test
    fun `bypass - trailing root dot is the same host`() {
        assertTrue(blocks("https://reddit.com./r/all"))
        assertTrue(blocks("reddit.com."))
        assertTrue(blocks("https://old.reddit.com./"))
    }

    @Test
    fun `bypass - userinfo before the host`() {
        assertTrue(blocks("https://example.com@reddit.com/"))
        assertTrue(blocks("https://user:pw@reddit.com/r/all"))
        // The host follows the *last* '@', not the first.
        assertTrue(blocks("https://a@b@reddit.com/"))
    }

    @Test
    fun `bypass - backslashes are path separators`() {
        assertTrue(blocks("https://reddit.com\\r\\all"))
        assertTrue(blocks("reddit.com\\"))
    }

    @Test
    fun `bypass - tab and newline are stripped before parsing`() {
        assertTrue(blocks("https://redd\tit.com/r/all"))
        assertTrue(blocks("https://reddit.com\n/r/all"))
    }

    @Test
    fun `bypass - case and www spelling`() {
        assertTrue(blocks("HTTPS://WWW.REDDIT.COM/R/ALL"))
        assertTrue(blocks("https://WWW.Reddit.Com./"))
        assertTrue(blocks("https://M.Reddit.com/"))
    }

    @Test
    fun `bypass - port does not hide the host`() {
        assertTrue(blocks("https://reddit.com:443/x"))
        assertTrue(blocks("reddit.com:8080"))
    }

    @Test
    fun `bypass - a rule saved without normalization still matches`() {
        // Rules can arrive from DataStore or an older build without passing normalizeDomain.
        assertTrue(blocks("https://www.reddit.com/", domain = "WWW.Reddit.com"))
        assertTrue(blocks("https://reddit.com/", domain = "  reddit.com.  "))
        assertTrue(blocks("https://reddit.com/", domain = "https://reddit.com/r/all"))
        // A leading dot is unmatchable under `endsWith(".$d")` unless it is trimmed off.
        assertTrue(blocks("https://reddit.com/", domain = ".reddit.com"))
        assertTrue(blocks("https://old.reddit.com/", domain = ".reddit.com"))
    }

    // MARK: false positives — innocent pages must load

    @Test
    fun `falsePositive - searching for a blocked url does not shield the search engine`() {
        assertFalse(blocks("google.com/search?q=https://reddit.com"))
        assertFalse(blocks("news.ycombinator.com/from?site=https://reddit.com"))
        assertFalse(blocks("duckduckgo.com/?q=https://old.reddit.com/r/all"))
    }

    @Test
    fun `falsePositive - a lookalike host is not a subdomain`() {
        assertFalse(blocks("https://notreddit.com/"))
        assertFalse(blocks("https://reddit.com.evil.example/"))
        assertFalse(blocks("https://example.com/reddit.com"))
    }

    @Test
    fun `falsePositive - omnibox search text is not a host`() {
        // Free text still carries a dot; it must not end up matching by suffix.
        assertFalse(blocks("how do i quit .reddit.com"))
        assertFalse(blocks("reddit"))
    }

    @Test
    fun `falsePositive - userinfo that merely names the blocked host`() {
        assertFalse(blocks("https://reddit.com@example.com/"))
    }

    // MARK: subdomain semantics (equality or strict subdomain, both directions)

    @Test
    fun `matches the domain and any subdomain but never a parent`() {
        assertTrue(blocks("https://reddit.com/"))
        assertTrue(blocks("https://old.reddit.com/"))
        assertTrue(blocks("https://a.b.reddit.com/"))
        assertFalse(blocks("https://com/", domain = "reddit.com"))
        assertFalse(blocks("https://reddit.com/", domain = "old.reddit.com"))
    }

    @Test
    fun `a rule on a subdomain does not block the apex`() {
        assertTrue(blocks("https://m.youtube.com/watch?v=1", domain = "m.youtube.com"))
        assertFalse(blocks("https://youtube.com/watch?v=1", domain = "m.youtube.com"))
    }

    // MARK: canonicalizer units

    @Test
    fun `canonicalize strips scheme userinfo port path and root dot`() {
        val messy = "https://user@WWW.Reddit.com.:443/r/all?x=1#y"
        assertEquals("reddit.com", HostCanonicalizer.canonicalize(messy))
        assertEquals("sub.reddit.com", HostCanonicalizer.canonicalize("http://sub.reddit.com:8080/x?y=1"))
        assertEquals("reddit.com", HostCanonicalizer.canonicalize("reddit.com"))
    }

    @Test
    fun `canonicalize keeps a bracketed ipv6 literal intact`() {
        assertEquals("[::1]", HostCanonicalizer.canonicalize("http://[::1]:8080/x"))
        assertEquals("[2001:db8::1]", HostCanonicalizer.canonicalize("https://[2001:DB8::1]/"))
        assertNull(HostCanonicalizer.canonicalize("http://[::1"))
    }

    @Test
    fun `canonicalize rejects text that cannot be a host`() {
        assertNull(HostCanonicalizer.canonicalize(""))
        assertNull(HostCanonicalizer.canonicalize("   "))
        assertNull(HostCanonicalizer.canonicalize("https://"))
        assertNull(HostCanonicalizer.canonicalize("www."))
        assertNull(HostCanonicalizer.canonicalize("search terms here"))
        assertNull(HostCanonicalizer.canonicalize("."))
    }

    @Test
    fun `canonicalize does not mistake a non-scheme prefix for a scheme`() {
        // "reddit.com" is a legal scheme *spelling*, but no "://" follows it here.
        assertEquals("reddit.com", HostCanonicalizer.canonicalize("reddit.com/a://b"))
        assertEquals("example.com", HostCanonicalizer.canonicalize("ftp://example.com/f"))
    }

    @Test
    fun `canonicalize is idempotent`() {
        val once = HostCanonicalizer.canonicalize("https://WWW.Reddit.com./r/all")!!
        assertEquals(once, HostCanonicalizer.canonicalize(once))
    }

    // MARK: interaction with the rest of decide()

    @Test
    fun `a null url never matches a website rule`() {
        assertEquals(Verdict.Allow, engine.decide(chrome, null, rules("reddit.com"), self))
    }

    @Test
    fun `an empty domain in a rule matches nothing`() {
        assertFalse(blocks("https://reddit.com/", domain = ""))
        assertFalse(blocks("https://reddit.com/", domain = "   "))
    }

    @Test
    fun `a rule with many domains matches on any of them`() {
        // decide() canonicalizes the address bar once and reuses it across every domain.
        val many = rules("x.example", "reddit.com", "y.example")
        assertTrue(engine.decide(chrome, "old.reddit.com./r/all", many, self) is Verdict.Block)
        assertEquals(Verdict.Allow, engine.decide(chrome, "z.example/?u=https://reddit.com", many, self))
    }

    @Test
    fun `an unreadable address bar blocks nothing`() {
        // canonicalize() returns null for search text, so no domain can match it.
        val searchText = "why is reddit.com addictive"
        assertEquals(Verdict.Allow, engine.decide(chrome, searchText, rules("reddit.com"), self))
    }
}
