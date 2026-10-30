package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.*
import org.junit.Assert.*
import org.junit.Test

class EditorTemplateCatalogTest {
    private val hash = "0".repeat(64)
    private val literal = "TC|1\nN|3\nT|rule:legacy|1|1|UTC|$hash|true|false\nT|time:app|2|2|Asia/Tokyo|$hash|false|true"
    private fun input(key: String = "rule:work", record: String = "unchanged", zone: String = "UTC") = EditorTemplateInput(key, record, zone)
    private fun failure(raw: String, reason: StorageFailure = StorageFailure.CORRUPT) {
        try { EditorTemplateCatalogCodec.decode(raw); fail("Invalid metadata accepted") }
        catch (failure: StorageUnavailableException) { assertEquals(reason, failure.reason) }
    }

    @Test fun `PC-70 literal template revisions contract versions saved zones and review flags decode unchanged`() {
        val catalog = EditorTemplateCatalogCodec.decode(literal)
        assertEquals(3L, catalog.nextRevision)
        assertEquals(1, catalog.templates.getValue("rule:legacy").contractVersion)
        assertTrue(catalog.templates.getValue("rule:legacy").requiresFiniteEnd)
        assertEquals("Asia/Tokyo", catalog.templates.getValue("time:app").zoneId)
        assertTrue(catalog.templates.getValue("time:app").tierNeedsReview)
        assertEquals(literal, EditorTemplateCatalogCodec.encode(catalog))
    }

    @Test fun `PC-56 metadata publication is unchanged for reordered inputs and a device zone edit`() {
        val entries = listOf(input(), input("group:one", "saved group"))
        val first = reconcileEditorTemplateCatalog(EditorTemplateCatalog(), entries)
        assertSame(first, reconcileEditorTemplateCatalog(first, entries.reversed().map { it.copy(zoneId = "Asia/Tokyo") }))
        assertEquals(setOf("UTC"), first.templates.values.map { it.zoneId }.toSet())
    }

    @Test fun `PC-70 a real edit changes only its template revision and recreation never reuses one`() {
        val entries = listOf(input(), input("open:app", "two credits"))
        val first = reconcileEditorTemplateCatalog(EditorTemplateCatalog(), entries)
        val changed = reconcileEditorTemplateCatalog(first, entries.map { if (it.key == "rule:work") it.copy(canonicalRecord = "new tier") else it })
        assertEquals(first.templates.getValue("open:app"), changed.templates.getValue("open:app"))
        assertTrue(changed.templates.getValue("rule:work").revision > first.templates.getValue("rule:work").revision)
        val removed = reconcileEditorTemplateCatalog(changed, entries.filterNot { it.key == "rule:work" })
        val recreated = reconcileEditorTemplateCatalog(removed, entries)
        assertTrue(recreated.templates.getValue("rule:work").revision > changed.templates.getValue("rule:work").revision)
    }

    @Test fun `PC-54 finite wall estimate changes do not revise a template but explicit deadline acceptance does`() {
        val rule = BlockRule("work", "Work", BlockTargets(apps = setOf(AppRef("app"))), expiresAtEpochMs = 100_000)
        val first = reconcileEditorTemplateCatalog(EditorTemplateCatalog(), listOf(ruleTemplateInput(rule, "UTC")))
        val shifted = ruleTemplateInput(rule.copy(expiresAtEpochMs = 86_500_000), "UTC")
        assertSame(first, reconcileEditorTemplateCatalog(first, listOf(shifted)))
        val accepted = reconcileEditorTemplateCatalog(first, listOf(shifted), acceptedChanges = setOf("rule:work"))
        assertTrue(accepted.templates.getValue("rule:work").revision > first.templates.getValue("rule:work").revision)
    }

    @Test fun `PC-68 legacy open ended metadata cannot acquire new rights without explicit finite acceptance`() {
        val legacy = input().copy(legacyOpenEnded = true)
        val first = reconcileEditorTemplateCatalog(EditorTemplateCatalog(), listOf(legacy))
        val finite = legacy.copy(canonicalRecord = "finite selection", legacyOpenEnded = false, hasFiniteBoundary = true)
        val unconfirmed = reconcileEditorTemplateCatalog(first, listOf(finite))
        assertTrue(unconfirmed.templates.getValue("rule:work").requiresFiniteEnd)
        val accepted = reconcileEditorTemplateCatalog(first, listOf(finite), acceptedChanges = setOf("rule:work"), acceptedFiniteEnds = setOf("rule:work"))
        assertFalse(accepted.templates.getValue("rule:work").requiresFiniteEnd)
        assertEquals(2, accepted.templates.getValue("rule:work").contractVersion)
    }

    @Test fun `PC-68 an unbounded candidate cannot masquerade as explicit finite acceptance`() {
        try {
            reconcileEditorTemplateCatalog(EditorTemplateCatalog(), listOf(input()), setOf("rule:work"), setOf("rule:work"))
            fail("Unbounded acceptance allowed")
        } catch (failure: StorageUnavailableException) { assertEquals(StorageFailure.CORRUPT, failure.reason) }
    }

    @Test fun `PC-70 legacy default tier remains marked for review until an explicit template edit`() {
        val legacy = input("time:app").copy(tierNeedsReview = true)
        val first = reconcileEditorTemplateCatalog(EditorTemplateCatalog(), listOf(legacy))
        val current = legacy.copy(tierNeedsReview = false)
        assertSame(first, reconcileEditorTemplateCatalog(first, listOf(current)))
        val accepted = reconcileEditorTemplateCatalog(first, listOf(current), setOf("time:app"))
        assertFalse(accepted.templates.getValue("time:app").tierNeedsReview)
    }

    @Test fun `PC-71 missing duplicate nonpositive and reused revisions are unavailable`() {
        failure(literal.lines().filterNot { it.startsWith("N|") }.joinToString("\n"))
        failure("$literal\nN|3")
        failure(literal.replace("|1|1|UTC", "|0|1|UTC"))
        failure(literal.replace("|2|2|Asia/Tokyo", "|1|2|Asia/Tokyo"))
        failure(literal.replace("N|3", "N|2"))
        failure(literal.replace("N|3", "N|03"))
    }

    @Test fun `PC-71 future metadata schema contract and source are never downgraded`() {
        failure(literal.replace("TC|1", "TC|2"), StorageFailure.UNSUPPORTED_VERSION)
        failure(literal.replace("|1|1|UTC", "|1|3|UTC"), StorageFailure.UNSUPPORTED_VERSION)
        failure(literal.replace("|1|1|UTC", "|1|4294967297|UTC"), StorageFailure.UNSUPPORTED_VERSION)
        failure(literal.replace("rule:legacy", "future:legacy"), StorageFailure.UNSUPPORTED_VERSION)
    }

    @Test fun `PC-71 unsupported zones and inconsistent legacy flags cannot arm a new policy`() {
        failure(literal.replace("|UTC|", "|Unknown/Zone|"))
        failure(literal.replace("|true|false", "|false|false"))
        failure(literal.replace("time:app", "open:app"))
        failure(literal.replace(hash, "short"))
    }
}
