package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.blocking.engine.AnchoredInterval
import com.flint.peakfocus.blocking.engine.CommitmentPolicy
import com.flint.peakfocus.blocking.engine.HarderWaitPolicy
import com.flint.peakfocus.core.model.AccessClockStamp

/** Sidecar v1. Original v1 rule/limit bytes are archived by the one-time transaction. */
internal object EditorCommitmentCodec {
    fun encode(state: EditorCommitmentState): String = buildList {
        add("E|1")
        add("N|${state.nextId}|${state.migrationNotice}")
        state.timeline?.let { add("K|${clock(it)}") }
        state.causes.toSortedMap().forEach { (key, cause) ->
            add("C|${wireAtom(key)}|${wireAtom(cause.occurrenceId)}|${wireAtom(cause.zoneId)}|${cause.legacyRights}|${interval(cause.interval)}")
        }
        state.pending?.let { p ->
            val wait = p.wait?.let { HarderWaitCodec.encode(HarderWaitPolicy.Ledger(pending =
                HarderWaitPolicy.PendingWait(it, AccessClockStamp(0, 0, "operation"), 60_000))) }.orEmpty()
            add("P|${p.id}|${p.outcome.name}|${wireAtom(change(p.change))}|${wireAtom(expected(p.expected))}|${PrefsCodec.escape(wait)}")
        }
        state.queued.sortedBy { it.id }.forEach { q ->
            val boundaries = q.boundaries.toSortedMap().entries.joinToString("\n") { (key, value) ->
                "${wireAtom(key)}|${interval(value)}"
            }
            add("Q|${q.id}|${wireAtom(change(q.change))}|${wireAtom(expected(q.expected))}|${wireAtom(boundaries)}|${q.needsReview}")
        }
    }.joinToString("\n").also { decode(it) }

    fun decode(raw: String?): EditorCommitmentState {
        if (raw == null) return EditorCommitmentState()
        val rows = wireRows(raw, "E")
        var next: Long? = null; var notice = false; var timeline: AccessClockStamp? = null
        val causes = linkedMapOf<String, EditorCause>()
        var pending: PendingEditorChange? = null
        val queued = mutableListOf<QueuedEditorChange>()
        rows.forEach { f ->
            when (f[0]) {
                "N" -> { wireRequire(f.size == 3 && next == null); next = wireLong(f[1]); notice = bool(f[2]); wireRequire(next!! > 0) }
                "K" -> { wireRequire(f.size == 4 && timeline == null); timeline = readClock(f.drop(1)) }
                "C" -> {
                    wireRequire(f.size == 6 || f.size == 11)
                    val cause = EditorCause(readWireAtom(f[2]), readInterval(f.drop(5)), readWireAtom(f[3]), bool(f[4]))
                    wireRequire(causes.put(readWireAtom(f[1]), cause) == null)
                }
                "P" -> {
                    wireRequire(f.size == 6 && pending == null)
                    val outcome = CommitmentPolicy.Outcome.entries.firstOrNull { it.name == f[2] } ?: wireFuture()
                    val wait = if (f[5].isEmpty()) null else HarderWaitCodec.decode(readWireAtom(f[5])).pending?.operation ?: wireCorrupt()
                    pending = PendingEditorChange(wireLong(f[1]), readChange(readWireAtom(f[3])), readExpected(readWireAtom(f[4])), outcome, wait)
                }
                "Q" -> {
                    wireRequire(f.size in 5..6)
                    val boundaries = linkedMapOf<String, AnchoredInterval>()
                    readWireAtom(f[4]).split('\n').forEach { line ->
                        val b = line.split('|'); wireRequire(b.size == 7)
                        wireRequire(boundaries.put(readWireAtom(b[0]), readInterval(b.drop(1)) ?: wireCorrupt()) == null)
                    }
                    queued += QueuedEditorChange(wireLong(f[1]), readChange(readWireAtom(f[2])), readExpected(readWireAtom(f[3])), boundaries,
                        f.getOrNull(5)?.let(::bool) ?: false)
                }
                else -> wireFuture()
            }
        }
        val ids = queued.map { it.id } + listOfNotNull(pending?.id)
        wireRequire(next != null && ids.all { it > 0 && it < next!! } && ids.distinct().size == ids.size)
        wireRequire(queued.flatMap { it.change.keys }.let { it.distinct().size == it.size })
        pending?.let { p ->
            wireRequire(p.change.keys.isNotEmpty() && p.outcome in setOf(CommitmentPolicy.Outcome.ALLOW,
                CommitmentPolicy.Outcome.ALLOW_AFTER_DISCLOSURE, CommitmentPolicy.Outcome.WAIT, CommitmentPolicy.Outcome.DEFER))
            wireRequire((p.wait != null) == (p.outcome == CommitmentPolicy.Outcome.WAIT))
        }
        queued.forEach { q -> wireRequire(q.boundaries.isNotEmpty() && q.boundaries.keys.all { it in q.change.keys }) }
        causes.values.forEach { c -> wireRequire(java.util.TimeZone.getTimeZone(c.zoneId).id == c.zoneId) }
        return EditorCommitmentState(next!!, causes.toMap(), pending, queued.toList(), notice, timeline)
    }

    private fun bool(value: String) = value.toBooleanStrictOrNull() ?: wireCorrupt()
    private fun clock(value: AccessClockStamp) = "${value.wallEpochMs}|${value.elapsedRealtimeMs}|${wireAtom(value.bootSessionId)}"
    private fun readClock(f: List<String>) = AccessClockStamp(wireLong(f[0]), wireLong(f[1]), readWireAtom(f[2]))
    private fun interval(value: AnchoredInterval?): String = value?.let {
        "I|${clock(it.anchor)}|${it.remainingAtAnchorMs}|${it.wallEndEpochMs}"
    } ?: "-"
    private fun readInterval(f: List<String>): AnchoredInterval? {
        if (f == listOf("-")) return null
        wireRequire(f.size == 6 && f[0] == "I")
        return AnchoredInterval(readClock(f.subList(1, 4)), wireLong(f[4]), wireLong(f[5]))
    }
    private fun escaped(value: String) = PrefsCodec.escape(value)
    private fun unescaped(value: String) = PrefsCodec.unescape(value).also { wireRequire(escaped(it) == value) }

    private fun change(c: EditorChange): String = buildList {
        add("M|1"); add("L|${wireAtom(c.label)}|${c.timerExit}")
        add("R|${escaped(PrefsCodec.encodeRules(c.upserts))}")
        c.deleteIds.sorted().forEach { add("D|${wireAtom(it)}") }
        c.limits?.let { add("B|${wireAtom(it.packageName)}|${escaped(PrefsCodec.encodeTimeLimits(listOfNotNull(it.time)))}|${escaped(PrefsCodec.encodeOpenLimits(listOfNotNull(it.open)))}") }
    }.joinToString("\n")

    private fun readChange(raw: String): EditorChange {
        var label: String? = null; var exit = false; var rules: List<com.flint.peakfocus.core.model.BlockRule>? = null
        val deletes = linkedSetOf<String>(); var limits: EditorLimitsChange? = null
        wireRows(raw, "M").forEach { f -> when (f[0]) {
            "L" -> { wireRequire(f.size == 3 && label == null); label = readWireAtom(f[1]); exit = bool(f[2]) }
            "R" -> { wireRequire(f.size == 2 && rules == null); rules = StrictPrefsCodec.rules(unescaped(f[1])) }
            "D" -> { wireRequire(f.size == 2 && deletes.add(readWireAtom(f[1]))) }
            "B" -> {
                wireRequire(f.size == 4 && limits == null)
                val times = StrictPrefsCodec.timeLimits(unescaped(f[2])); val opens = StrictPrefsCodec.openLimits(unescaped(f[3]))
                val pkg = readWireAtom(f[1]); wireRequire(times.size <= 1 && opens.size <= 1)
                wireRequire(times.all { it.packageName == pkg } && opens.all { it.packageName == pkg })
                limits = EditorLimitsChange(pkg, times.singleOrNull(), opens.singleOrNull())
            }
            else -> wireFuture()
        } }
        wireRequire(label != null && rules != null && rules!!.none { it.id in deletes })
        return EditorChange(label!!, rules!!, deletes.toSet(), limits, exit)
    }

    private fun expected(e: EditorExpected): String = buildList {
        add("X|1"); add("R|${escaped(PrefsCodec.encodeRules(e.rules))}")
        add("T|${escaped(PrefsCodec.encodeTimeLimits(listOfNotNull(e.time)))}")
        add("O|${escaped(PrefsCodec.encodeOpenLimits(listOfNotNull(e.open)))}")
        e.occurrences.toSortedMap().forEach { (key, id) -> add("C|${wireAtom(key)}|${wireAtom(id)}") }
    }.joinToString("\n")

    private fun readExpected(raw: String): EditorExpected {
        val rows = wireRows(raw, "X")
        fun only(kind: String): String = rows.filter { it[0] == kind }.singleOrNull()?.also { wireRequire(it.size == 2) }?.get(1) ?: wireCorrupt()
        wireRequire(rows.all { it[0] in setOf("R", "T", "O", "C") })
        val times = StrictPrefsCodec.timeLimits(unescaped(only("T"))); val opens = StrictPrefsCodec.openLimits(unescaped(only("O")))
        wireRequire(times.size <= 1 && opens.size <= 1)
        val occurrences = linkedMapOf<String, String>()
        rows.filter { it[0] == "C" }.forEach { f -> wireRequire(f.size == 3 && occurrences.put(readWireAtom(f[1]), readWireAtom(f[2])) == null) }
        return EditorExpected(StrictPrefsCodec.rules(unescaped(only("R"))), times.singleOrNull(), opens.singleOrNull(), occurrences.toMap())
    }
}
