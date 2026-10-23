package com.flint.peakfocus.feature.connected

import com.flint.peakfocus.core.model.*
import com.google.gson.*
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZoneId

/** Strict v1 decoder: no coercion, unknown keys, duplicate keys, partial records or defaults. */
internal object ManagedWireCodec {
    const val SNAPSHOT_LIMIT = 1_048_576
    private val uuid = Regex("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
    private val time = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]")
    private val timestamp = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{3})?Z")
    private const val MAX_REVISION = 9_007_199_254_740_991L

    fun snapshot(payload: String, identity: ManagedIdentity): ManagedSnapshot = checked {
        val root = parse(payload).obj("schemaVersion", "complete", "userId", "revision", "serverTime", "memberships", "rules", "tombstones")
        require(root.number("schemaVersion") == 1L && root.flag("complete"))
        require(root.string("userId", 128) == identity.accountUid)
        val revision = root.number("revision")
        val memberships = root.array("memberships", 20).map { value ->
            val row = value.obj("groupId", "membershipId", "role")
            ManagedMembership(row.identifier("groupId"), row.identifier("membershipId"), when (row.string("role", 10)) {
                "owner" -> ManagedRole.OWNER
                "member" -> ManagedRole.MEMBER
                else -> error("Unknown role")
            })
        }
        require(memberships.map { it.groupId }.distinct().size == memberships.size)
        require(memberships.map { it.membershipId }.distinct().size == memberships.size)
        val rules = root.array("rules", 200).map { rule(it) }
        val tombstones = root.array("tombstones", 1_280).map { tombstone(it) }
        validateRows(rules, tombstones, revision)
        rules.forEach { rule ->
            require(memberships.any { it.groupId == rule.targetGroupId && it.role == ManagedRole.MEMBER })
            (rule.scope as? ManagedRuleScope.Member)?.let { require(it.userId == identity.accountUid) }
        }
        tombstones.forEach { deleted -> require(memberships.any { it.groupId == deleted.targetGroupId && it.role == ManagedRole.MEMBER }) }
        ManagedSnapshot(identity, 1, revision, root.instant("serverTime"), memberships.sortedBy { it.groupId }, rules.sortedBy { it.id }, tombstones.sortedBy { it.id })
    }

    fun page(payload: String, identity: ManagedIdentity, groupId: String): ManagedRulePage = checked {
        val root = parse(payload).obj("revision", "rules", "tombstones")
        val revision = root.number("revision")
        val rules = root.array("rules", 10).map { rule(it) }
        val deleted = root.array("tombstones", 64).map { tombstone(it) }
        validateRows(rules, deleted, revision)
        require(rules.all { it.targetGroupId == groupId } && deleted.all { it.targetGroupId == groupId })
        ManagedRulePage(identity, revision, rules, deleted)
    }

    fun result(payload: String, identity: ManagedIdentity, groupId: String): ManagedRuleResult = checked {
        val root = parse(payload).obj("revision", "rule")
        val rule = rule(root["rule"])
        val revision = root.number("revision")
        require(rule.targetGroupId == groupId && rule.revision <= revision)
        ManagedRuleResult(identity, revision, rule)
    }

    fun deletion(payload: String, identity: ManagedIdentity, groupId: String): ManagedDeleteResult = checked {
        val root = parse(payload).obj("revision", "tombstone")
        val row = tombstone(root["tombstone"])
        val revision = root.number("revision")
        require(row.targetGroupId == groupId && row.revision <= revision)
        ManagedDeleteResult(identity, revision, row)
    }

    fun input(value: ManagedRuleInput): JsonObject = checkedInput {
        val row = inputJson(value)
        // Reuse the full decoder's structural checks before sending authoring data.
        val probe = row.deepCopy().apply {
            addProperty("id", "00000000-0000-0000-0000-000000000001")
            addProperty("targetGroupId", "00000000-0000-0000-0000-000000000002")
            addProperty("revision", 1)
            addProperty("createdAt", "2026-01-01T00:00:00.000Z")
            addProperty("updatedAt", "2026-01-01T00:00:00.000Z")
        }
        rule(probe)
        require(row.toString().toByteArray(Charsets.UTF_8).size <= 2_048)
        row
    }

    fun fingerprint(snapshot: ManagedSnapshot): String {
        val value = JsonObject().apply {
            addProperty("userId", snapshot.identity.accountUid)
            addProperty("revision", snapshot.revision)
            add("memberships", JsonArray().apply { snapshot.memberships.sortedBy { it.groupId }.forEach { member ->
                add(JsonObject().apply { addProperty("groupId", member.groupId); addProperty("membershipId", member.membershipId); addProperty("role", member.role.name) })
            } })
            add("rules", JsonArray().apply { snapshot.rules.sortedBy { it.id }.forEach { rule ->
                add(inputJson(ManagedRuleInput(rule.name, rule.zoneId, rule.enabled, rule.scope, rule.schedule, rule.targets, rule.unblocksAllowed)).apply {
                    addProperty("id", rule.id); addProperty("targetGroupId", rule.targetGroupId); addProperty("revision", rule.revision)
                    addProperty("createdAt", rule.createdAtEpochMs); addProperty("updatedAt", rule.updatedAtEpochMs)
                })
            } })
            add("tombstones", JsonArray().apply { snapshot.tombstones.sortedBy { it.id }.forEach { row ->
                add(JsonObject().apply { addProperty("id", row.id); addProperty("targetGroupId", row.targetGroupId); addProperty("revision", row.revision); addProperty("deletedAt", row.deletedAtEpochMs) })
            } })
        }
        return MessageDigest.getInstance("SHA-256").digest(value.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun inputJson(value: ManagedRuleInput) = JsonObject().apply {
        addProperty("name", value.name.trim()); addProperty("enabled", value.enabled); addProperty("zoneId", value.zoneId)
        add("scope", JsonObject().apply { when (val scope = value.scope) {
            ManagedRuleScope.Group -> addProperty("kind", "group")
            is ManagedRuleScope.Member -> { addProperty("kind", "member"); addProperty("userId", scope.userId) }
        } })
        addProperty("ruleType", if (value.schedule is ManagedSchedule.Session) "session" else "recurring_schedule")
        add("config", JsonObject().apply {
            add("blockScope", JsonObject().apply { when (val targets = value.targets) {
                ManagedTargets.AllApps -> addProperty("type", "all_apps")
                is ManagedTargets.Selection -> {
                    addProperty("type", "distracting_apps")
                    add("apps", JsonArray().apply { targets.packages.sorted().forEach { add(it) } })
                    add("domains", JsonArray().apply { targets.domains.sorted().forEach { add(it) } })
                }
            } })
            add("unblocks", JsonObject().apply { addProperty("allowed", value.unblocksAllowed) })
            when (val schedule = value.schedule) {
                is ManagedSchedule.Session -> {
                    addProperty("startTime", Instant.ofEpochMilli(schedule.startsAtEpochMs).toString())
                    addProperty("endTime", Instant.ofEpochMilli(schedule.endsAtEpochMs).toString())
                }
                is ManagedSchedule.Recurring -> {
                    addProperty("startTime", minuteText(schedule.startMinute)); addProperty("endTime", minuteText(schedule.endMinute))
                    add("weekdays", JsonArray().apply { schedule.isoWeekdays.sorted().forEach { add(it) } })
                }
            }
        })
    }

    private fun rule(value: JsonElement): ManagedRule {
        val row = value.obj("id", "targetGroupId", "revision", "createdAt", "updatedAt", "name", "ruleType", "scope", "zoneId", "enabled", "config")
        val name = row.string("name", 80).also { require(it == it.trim()) }
        val zone = row.string("zoneId", 64).also { require(it == "UTC" || it.contains('/')); ZoneId.of(it) }
        val scopeRow = row["scope"].asObject()
        val scope = when (scopeRow.string("kind", 10)) {
            "group" -> { scopeRow.obj("kind"); ManagedRuleScope.Group }
            "member" -> { scopeRow.obj("kind", "userId"); ManagedRuleScope.Member(scopeRow.string("userId", 128)) }
            else -> error("Unknown scope")
        }
        val kind = row.string("ruleType", 20)
        val config = when (kind) {
            "session" -> row["config"].obj("blockScope", "unblocks", "startTime", "endTime")
            "recurring_schedule" -> row["config"].obj("blockScope", "unblocks", "startTime", "endTime", "weekdays")
            else -> error("Unknown rule type")
        }
        val unblocks = config["unblocks"].obj("allowed").flag("allowed")
        val selection = config["blockScope"].asObject()
        val targets = when (selection.string("type", 20)) {
            "all_apps" -> { selection.obj("type"); ManagedTargets.AllApps }
            "distracting_apps" -> {
                selection.obj("type", "apps", "domains")
                val apps = selection.array("apps", 20).map { it.text(255) }
                val domains = selection.array("domains", 20).map { it.text(253) }
                require(apps.distinct().size == apps.size && domains.distinct().size == domains.size && apps.size + domains.size > 0)
                require(apps.all { it.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")) })
                require(domains.all { host -> host == host.lowercase() && host.contains('.') && !host.matches(Regex("[0-9.]+")) &&
                    host.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) } })
                ManagedTargets.Selection(apps.toSortedSet(), domains.toSortedSet())
            }
            else -> error("Unknown targets")
        }
        val schedule = if (kind == "session") {
            val start = config.instant("startTime"); val end = config.instant("endTime")
            require(end > start && end - start <= 7 * 86_400_000L)
            ManagedSchedule.Session(start, end)
        } else {
            val start = minute(config.string("startTime", 5)); val end = minute(config.string("endTime", 5))
            val days = config.array("weekdays", 7).map { it.longNumber().also { day -> require(day in 1L..7L) }.toInt() }
            require(days.isNotEmpty() && days.distinct().size == days.size && start != end)
            ManagedSchedule.Recurring(start, end, days.toSortedSet())
        }
        return ManagedRule(row.identifier("id"), row.identifier("targetGroupId"), row.number("revision").also { require(it > 0) },
            name, zone, row.flag("enabled"), scope, schedule, targets, unblocks, row.instant("createdAt"), row.instant("updatedAt"))
    }
    private fun tombstone(value: JsonElement): ManagedTombstone {
        val row = value.obj("id", "targetGroupId", "revision", "deletedAt")
        return ManagedTombstone(row.identifier("id"), row.identifier("targetGroupId"), row.number("revision").also { require(it > 0) }, row.instant("deletedAt"))
    }
    private fun validateRows(rules: List<ManagedRule>, tombstones: List<ManagedTombstone>, revision: Long) {
        require(rules.map { it.id }.distinct().size == rules.size && tombstones.map { it.id }.distinct().size == tombstones.size)
        require(rules.map { it.id }.toSet().intersect(tombstones.map { it.id }.toSet()).isEmpty())
        require(rules.all { it.revision <= revision } && tombstones.all { it.revision <= revision })
        require(rules.groupingBy { it.targetGroupId }.eachCount().values.all { it <= 10 })
        require(tombstones.groupingBy { it.targetGroupId }.eachCount().values.all { it <= 64 })
    }
    private fun minute(value: String): Int { require(time.matches(value)); return value.substring(0, 2).toInt() * 60 + value.substring(3).toInt() }
    private fun minuteText(value: Int): String { require(value in 0..1439); return "%02d:%02d".format(java.util.Locale.ROOT, value / 60, value % 60) }
    private fun JsonElement.asObject(): JsonObject { require(isJsonObject); return asJsonObject }
    private fun JsonElement.obj(vararg fields: String): JsonObject = asObject().also { require(it.keySet() == fields.toSet()) }
    private fun JsonObject.string(key: String, max: Int) = this[key].text(max)
    private fun JsonElement.text(max: Int): String { require(isJsonPrimitive && asJsonPrimitive.isString); return asString.also { require(it.isNotBlank() && it.length <= max && it.none(Char::isISOControl)) } }
    private fun JsonObject.identifier(key: String) = string(key, 36).also { require(uuid.matches(it)) }
    private fun JsonObject.flag(key: String): Boolean { val value = this[key]; require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean); return value.asBoolean }
    private fun JsonElement.longNumber(): Long { require(isJsonPrimitive && asJsonPrimitive.isNumber); val raw = asString; require(raw.matches(Regex("0|[1-9][0-9]*"))); return raw.toLong().also { require(it <= MAX_REVISION) } }
    private fun JsonObject.number(key: String) = this[key].longNumber()
    private fun JsonObject.array(key: String, maximum: Int): List<JsonElement> { val value = this[key]; require(value.isJsonArray); return value.asJsonArray.toList().also { require(it.size <= maximum) } }
    private fun JsonObject.instant(key: String): Long {
        val value = string(key, 30)
        require(timestamp.matches(value))
        // LocalDateTime rejects leap seconds, 24:00, and invalid calendar dates
        // which Instant.parse can normalize. Only server-contract millisecond UTC is accepted.
        return LocalDateTime.parse(value.removeSuffix("Z")).toInstant(ZoneOffset.UTC).toEpochMilli()
            .also { require(it in 0..253_402_300_799_999L) }
    }

    private fun parse(payload: String): JsonElement {
        require(payload.toByteArray(Charsets.UTF_8).size <= SNAPSHOT_LIMIT)
        var tokens = 0
        fun read(reader: JsonReader, depth: Int): JsonElement {
            require(depth <= 12 && ++tokens <= 100_000)
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> JsonObject().apply {
                    reader.beginObject()
                    while (reader.hasNext()) { val key = reader.nextName(); require(key.length <= 80 && !has(key)); add(key, read(reader, depth + 1)) }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> JsonArray().apply { reader.beginArray(); while (reader.hasNext()) add(read(reader, depth + 1)); reader.endArray() }
                JsonToken.STRING -> JsonPrimitive(reader.nextString().also { require(it.length <= 2_048) })
                JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString().also { require(it.length <= 32 && it.matches(Regex("0|[1-9][0-9]*"))) }))
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                else -> error("Unsupported JSON value")
            }
        }
        return JsonReader(StringReader(payload)).use { reader ->
            reader.strictness = Strictness.STRICT
            read(reader, 0).also { require(reader.peek() == JsonToken.END_DOCUMENT) }
        }
    }
    private inline fun <T> checked(block: () -> T): T = try { block() } catch (error: Exception) {
        if (error is ManagedRequestFailure) throw error
        throw ManagedRequestFailure(ManagedFailureKind.PROTOCOL, "The managed policy response is invalid or unsupported.")
    }
    private inline fun <T> checkedInput(block: () -> T): T = try { block() } catch (_: Exception) {
        throw ManagedRequestFailure(ManagedFailureKind.VALIDATION, "Check the managed policy fields before saving.")
    }
}
