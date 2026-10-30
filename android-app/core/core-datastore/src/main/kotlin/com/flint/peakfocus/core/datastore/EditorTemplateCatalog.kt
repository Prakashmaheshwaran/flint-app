package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.BlockRule
import java.security.MessageDigest
import java.util.TimeZone

/** Template metadata is separate from the currently running occurrence's promised rights. */
internal data class EditorTemplateMetadata(
    val revision: Long,
    val contractVersion: Int,
    val zoneId: String,
    val fingerprint: String,
    val requiresFiniteEnd: Boolean = false,
    val tierNeedsReview: Boolean = false,
)

internal data class EditorTemplateCatalog(
    val nextRevision: Long = 1,
    val templates: Map<String, EditorTemplateMetadata> = emptyMap(),
)

internal data class EditorTemplateInput(
    val key: String,
    val canonicalRecord: String,
    val zoneId: String,
    val legacyOpenEnded: Boolean = false,
    val tierNeedsReview: Boolean = false,
    val hasFiniteBoundary: Boolean = false,
)

/** A wall-clock display rewrite is not an accepted change to a finite commitment. */
internal fun ruleTemplateInput(rule: BlockRule, zoneId: String, legacyOpenEnded: Boolean = false) = EditorTemplateInput(
    ruleCauseKey(rule.id), PrefsCodec.encodeRules(listOf(rule.copy(expiresAtEpochMs = rule.expiresAtEpochMs?.let { 0L }))),
    zoneId, legacyOpenEnded = legacyOpenEnded, hasFiniteBoundary = rule.schedule != null || rule.expiresAtEpochMs != null,
)

/** No clocks or storage writes: only actual configuration changes allocate a new revision. */
internal fun reconcileEditorTemplateCatalog(
    previous: EditorTemplateCatalog,
    inputs: List<EditorTemplateInput>,
    acceptedChanges: Set<String> = emptySet(),
    acceptedFiniteEnds: Set<String> = emptySet(),
): EditorTemplateCatalog {
    wireRequire(inputs.map { it.key }.distinct().size == inputs.size)
    val byKey = inputs.associateBy { it.key }
    wireRequire(acceptedChanges.all { it in byKey } && acceptedFiniteEnds.all { it in acceptedChanges })
    wireRequire(acceptedFiniteEnds.all { key -> key.startsWith("rule:") && byKey.getValue(key).hasFiniteBoundary })
    var next = previous.nextRevision
    val templates = inputs.sortedBy { it.key }.associate { input ->
        val old = previous.templates[input.key]
        wireRequire(!input.legacyOpenEnded || !input.hasFiniteBoundary)
        val finiteRequired = input.legacyOpenEnded || (old?.requiresFiniteEnd == true && input.key !in acceptedFiniteEnds)
        val review = input.tierNeedsReview || (old?.tierNeedsReview == true && input.key !in acceptedChanges)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(input.canonicalRecord.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val candidate = EditorTemplateMetadata(old?.revision ?: next, if (finiteRequired) 1 else 2,
            old?.zoneId ?: input.zoneId, fingerprint, finiteRequired, review)
        val changed = old == null || candidate != old || input.key in acceptedChanges
        val metadata = if (!changed) old else {
            wireRequire(next in 1 until Long.MAX_VALUE)
            candidate.copy(revision = next++)
        }
        input.key to checkNotNull(metadata)
    }
    val result = EditorTemplateCatalog(next, templates)
    EditorTemplateCatalogCodec.encode(result)
    return if (result == previous) previous else result
}

/** A bounded sidecar payload; the production editor migration owns its eventual marker. */
internal object EditorTemplateCatalogCodec {
    fun encode(value: EditorTemplateCatalog): String = buildList {
        add("TC|1")
        add("N|${value.nextRevision}")
        value.templates.toSortedMap().forEach { (key, metadata) ->
            add("T|${wireAtom(key)}|${metadata.revision}|${metadata.contractVersion}|${wireAtom(metadata.zoneId)}|${metadata.fingerprint}|${metadata.requiresFiniteEnd}|${metadata.tierNeedsReview}")
        }
    }.joinToString("\n").also { decode(it) }

    fun decode(raw: String): EditorTemplateCatalog {
        var next: Long? = null
        val templates = linkedMapOf<String, EditorTemplateMetadata>()
        wireRows(raw, "TC").forEach { fields -> when (fields[0]) {
            "N" -> { wireRequire(fields.size == 2 && next == null); next = number(fields[1]); wireRequire(next!! > 0) }
            "T" -> {
                wireRequire(fields.size == 8)
                val key = readWireAtom(fields[1])
                val kind = key.substringBefore(':')
                if (kind !in setOf("rule", "time", "open", "group")) wireFuture()
                wireRequire(key.substringAfter(':', "").isNotBlank())
                val contract = number(fields[3])
                if (contract !in 1L..2L) wireFuture()
                val zone = readWireAtom(fields[4])
                wireRequire(TimeZone.getTimeZone(zone).id == zone)
                wireRequire(fields[5].matches(Regex("[0-9a-f]{64}")))
                val finite = fields[6].toBooleanStrictOrNull() ?: wireCorrupt()
                val review = fields[7].toBooleanStrictOrNull() ?: wireCorrupt()
                wireRequire((contract == 1L) == finite && (!finite || kind == "rule") && (!review || kind == "time"))
                wireRequire(templates.put(key, EditorTemplateMetadata(number(fields[2]), contract.toInt(), zone, fields[5], finite, review)) == null)
            }
            else -> wireFuture()
        } }
        wireRequire(next != null && templates.values.all { it.revision in 1 until next!! })
        wireRequire(templates.values.map { it.revision }.distinct().size == templates.size)
        return EditorTemplateCatalog(next!!, templates.toMap())
    }

    private fun number(raw: String): Long = wireLong(raw).also { wireRequire(it.toString() == raw) }
}
