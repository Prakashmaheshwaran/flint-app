package com.flint.peakfocus.feature.blocklist

import com.flint.peakfocus.core.model.AppRef
import com.flint.peakfocus.core.model.BlockRule
import com.flint.peakfocus.core.model.BlockTargets
import com.flint.peakfocus.core.model.DomainRef
import com.flint.peakfocus.core.model.StorageFailure
import com.flint.peakfocus.core.model.TimeLimit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PickedTargetsTest {
    private fun rule(id: String, apps: Set<String> = emptySet(), domains: Set<String> = emptySet(), allowList: Boolean = false) =
        BlockRule(id, id, BlockTargets(apps.map { AppRef(it) }.toSet(), domains.map { DomainRef(it) }.toSet(), allowList))

    private fun ready(vararg rules: BlockRule, limits: List<AppLimitRow> = emptyList()) =
        pickedTargets(RulesUiState.Ready(rules.toList()), LimitsUiState.Ready(limits)) { pkg ->
            mapOf("com.b" to "Bravo", "com.a" to "alpha")[pkg]
        } as PickedTargetsState.Ready

    @Test fun `lists each picked app and site once, apps by label then sites`() {
        val state = ready(
            rule("r1", setOf("com.b", "com.a"), setOf("News.example ", "news.example")),
            rule("session-1", setOf("com.a"), setOf("video.example")),
            limits = listOf(AppLimitRow("com.c", "Charlie", TimeLimit("com.c", 30), null)),
        )
        assertEquals(listOf("alpha", "Bravo", "Charlie", "news.example", "video.example"), state.targets.map { it.label })
        assertEquals(AppDetailTarget.Site("news.example"), state.targets[3].target)
        assertFalse(state.hasAllowListRules)
    }

    @Test fun `allow-list and Sleep exemptions are not listed as picks`() {
        val state = ready(rule("sleep-window", setOf("com.allowed"), allowList = true), rule("r", setOf("com.b")))
        assertEquals(listOf(AppDetailTarget.App("com.b")), state.targets.map { it.target })
        assertTrue(state.hasAllowListRules)
    }

    @Test fun `unknown labels fall back to the stored label then the package`() {
        val state = ready(rule("r", setOf("com.z")), limits = listOf(AppLimitRow("com.y", "Yankee", null, null)))
        assertEquals(listOf("com.z", "Yankee"), state.targets.map { it.label })
    }

    @Test fun `unreadable or loading stores never become an empty list`() {
        assertEquals(PickedTargetsState.Unavailable, pickedTargets(
            RulesUiState.Unavailable(StorageFailure.CORRUPT), LimitsUiState.Ready(emptyList())) { null })
        assertEquals(PickedTargetsState.Unavailable, pickedTargets(
            RulesUiState.Ready(emptyList()), LimitsUiState.Unavailable(StorageFailure.IO)) { null })
        assertEquals(PickedTargetsState.Loading, pickedTargets(RulesUiState.Loading, LimitsUiState.Ready(emptyList())) { null })
    }
}
