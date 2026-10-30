package com.flint.peakfocus.core.datastore

import com.flint.peakfocus.core.model.*

/** Versioned additive receipts; existing v1 accounting keys retain their exact format. */
internal object FocusLedgerCodec {
    data class Ledger(
        val exemptions: Map<String, PersistedExemption> = emptyMap(),
        val breaksByCause: Map<String, BreakSessionState> = emptyMap(),
        val revision: Long = 0,
        val minimumRequestEpochMs: Long = 0,
        /** H3 refuses unsafe old-build writes; its required pass record shares the same edit. */
        val protectionInitialized: Boolean = false,
    )

    fun encode(value: Ledger): String = buildList {
        require(value.revision >= 0 && value.minimumRequestEpochMs >= 0)
        add("H|${if (value.protectionInitialized) 3 else 2}|${value.revision}|${value.minimumRequestEpochMs}")
        value.exemptions.toSortedMap().forEach { (id, grant) ->
            require(id == grant.requestId && grant.untilEpochMs >= 0)
            add(listOf("E", atom(id), atom(grant.packageName), atom(grant.causeKey), grant.kind.name,
                grant.untilEpochMs.toString(), grant.bootSessionId?.let {
                    require(it != "-"); atom(it)
                } ?: "-").joinToString("|"))
        }
        value.breaksByCause.toSortedMap().forEach { (cause, session) ->
            val encoded = PrefsCodec.encodeBreakSession(session)
            StrictPrefsCodec.session(encoded)
            add("B|${atom(cause)}|${PrefsCodec.escape(encoded)}")
        }
    }.joinToString("\n").also { require(it.length <= 4_194_304) }

    fun decode(encoded: String?): Ledger {
        if (encoded == null) return Ledger()
        fun corrupt(): Nothing = throw StorageUnavailableException(StorageFailure.CORRUPT)
        fun future(): Nothing = throw StorageUnavailableException(StorageFailure.UNSUPPORTED_VERSION)
        if (encoded.length > 4_194_304) corrupt()
        val rows = encoded.split('\n')
        val header = rows.first().split('|')
        if (header.size < 2 || header[0] != "H" || header[1].toIntOrNull() == null) corrupt()
        if (header[1] !in listOf("1", "2", "3")) future()
        val version = header[1].toInt()
        if (header.size > if (version == 1) 3 else 4) future()
        if (version >= 2 && header.size != 4) corrupt()
        // Initial additive-v1 fixtures without a revision migrate losslessly on first write.
        val revision = if (header.size == 2) 0L else header[2].toLongOrNull()?.takeIf { it >= 0 } ?: corrupt()
        val floor = if (version == 1) 0L else header[3].toLongOrNull()?.takeIf { it >= 0 } ?: corrupt()
        val exemptions = linkedMapOf<String, PersistedExemption>()
        val causes = linkedMapOf<String, BreakSessionState>()
        fun readAtom(raw: String): String {
            val decoded = PrefsCodec.unescape(raw)
            if (decoded.isBlank() || decoded.length > 2_048 || PrefsCodec.escape(decoded) != raw) corrupt()
            return decoded
        }
        rows.drop(1).forEach { row ->
            if (row.isBlank()) corrupt()
            val f = row.split('|')
            when (f[0]) {
                "E" -> {
                    if (f.size !in 6..7 || (version == 1 && f.size != 6)) corrupt()
                    val id = readAtom(f[1])
                    val kind = ExemptionKind.entries.firstOrNull { it.name == f[4] } ?: future()
                    val deadline = f[5].toLongOrNull()?.takeIf { it >= 0 } ?: corrupt()
                    // Older v1/unshipped-v2 rows remain readable but cannot prove boot-scoped access.
                    val boot = f.getOrNull(6)?.takeUnless { it == "-" }?.let(::readAtom)
                    val grant = PersistedExemption(id, readAtom(f[2]), readAtom(f[3]), kind, deadline, boot)
                    if (exemptions.put(id, grant) != null) corrupt()
                }
                "B" -> {
                    if (f.size != 3) corrupt()
                    val cause = readAtom(f[1])
                    val session = StrictPrefsCodec.session(PrefsCodec.unescape(f[2]))
                    if (PrefsCodec.escape(PrefsCodec.unescape(f[2])) != f[2] || causes.put(cause, session) != null) corrupt()
                }
                else -> future()
            }
        }
        return Ledger(exemptions.toMap(), causes.toMap(), revision, floor, version == 3)
    }

    private fun atom(raw: String): String {
        require(raw.isNotBlank() && raw.length <= 2_048)
        return PrefsCodec.escape(raw)
    }
}
