package com.flint.peakfocus.core.history

/** Known fields are strict; opaque future JSON payloads survive unchanged in originalLine. */
object HistoryCodec {
    const val MATERIALIZATION_LIMIT = 64 * 1024
    fun encode(event: HistoryEvent): String = event.originalLine ?: FlatJson.encode(linkedMapOf<String, Any?>(
        "v" to event.v.toLong(), "id" to event.id, "type" to event.type,
        "session" to event.session, "at" to event.at, "tz" to event.tz,
    ).apply {
        if (event.inferred) put("inferred", true)
        if (event.knownType in setOf(HistoryEventType.STARTED, HistoryEventType.RESCHEDULED)) put("plannedEnd", event.plannedEnd)
        event.tier?.let { put("tier", it) }
        event.source?.let { put("source", it) }
        event.name?.let { put("name", it.take(64)) }
        event.route?.let { put("route", it) }
        event.by?.let { put("by", it) }
        event.breakEnd?.let { put("breakEnd", it) }
        event.reason?.let { put("reason", it) }
        event.originRoute?.let { put("originRoute", it) }
        event.observedAt?.let { put("observedAt", it) }
        event.kind?.let { put("kind", it) }
        event.grant?.let { put("grant", it) }
        event.sessions?.let { put("sessions", it) }
        event.recoveryEnd?.let { put("recoveryEnd", it) }
    })

    @JvmOverloads fun decode(line: String, forAppend: Boolean = false): HistoryEvent {
        val envelope = HistoryEnvelope.read(line, forAppend)
        if (envelope.v > 1 || HistoryEventType.fromWire(envelope.type) == null || envelope.opaque || line.length > MATERIALIZATION_LIMIT) return envelope.copy(opaque = true)
        val fields = FlatJson.decode(line)
        fun string(key: String): String = (fields[key] as? String)?.takeIf { it.isNotBlank() }
            ?: error("Missing string: $key")
        fun optionalString(key: String): String? = fields[key]?.let { it as? String ?: error(key) }
        fun optionalLong(key: String): Long? = fields[key]?.let { it as? Long ?: error(key) }
        val version = (fields["v"] as? Long)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: error("v")
        val type = string("type")
        if (version > 1 || HistoryEventType.fromWire(type) == null) {
            return HistoryEvent(v = version, id = string("id"), type = type, session = string("session"),
                at = fields["at"] as? Long ?: error("at"), tz = string("tz"), originalLine = line)
        }
        val event = HistoryEvent(
            v = version, id = string("id"), type = type, session = string("session"),
            at = fields["at"] as? Long ?: error("at"), tz = string("tz"),
            inferred = fields["inferred"]?.let { it as? Boolean ?: error("inferred") } ?: false,
            plannedEnd = optionalLong("plannedEnd"), tier = optionalString("tier"),
            source = optionalString("source"), name = optionalString("name"),
            route = optionalString("route"), by = optionalString("by"),
            breakEnd = optionalLong("breakEnd"), reason = optionalString("reason"), originalLine = line,
            originRoute = optionalString("originRoute"), observedAt = optionalLong("observedAt"),
            kind = optionalString("kind"), grant = optionalString("grant"), recoveryEnd = optionalLong("recoveryEnd"),
            sessions = fields["sessions"]?.let { list ->
                (list as? List<*>)?.map { it as? String ?: error("Invalid session reference") } ?: error("sessions")
            },
        )
        when (event.knownType) {
            HistoryEventType.STARTED -> {
                require(fields.containsKey("plannedEnd"))
                require(event.tier in setOf("easy", "harder", "hardcore"))
                require(event.source != null && event.name != null && event.name.length <= 64)
            }
            HistoryEventType.RESCHEDULED -> require(fields.containsKey("plannedEnd"))
            HistoryEventType.ENDED_EARLY -> require(event.route != null)
            HistoryEventType.SUPERSEDED -> require(event.by != null)
            HistoryEventType.BREAK_STARTED -> require(event.breakEnd != null)
            HistoryEventType.FAILED_TO_ARM -> require(event.reason != null)
            else -> Unit
        }
        return event
    }
}

internal object FlatJson {
    private data class JsonNumber(val raw: String)
    fun encode(fields: Map<String, Any?>): String = fields.entries.joinToString(",", "{", "}") { (k, v) ->
        quote(k) + ":" + encodeValue(v)
    }
    private fun encodeValue(v: Any?): String = when (v) {
            null -> "null"
            is String -> quote(v)
            is Long, is Boolean -> v.toString()
            is JsonNumber -> v.raw
            is Map<*, *> -> encode(v.entries.associate { (key, value) -> (key as String) to value })
            is List<*> -> v.joinToString(",", "[", "]", transform = ::encodeValue)
            else -> error("Unsupported JSON value")
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    fun decode(input: String): Map<String, Any?> = Parser(input).parse()

    private class Parser(val input: String) {
        var i = 0
        fun whitespace() { while (i < input.length && input[i] in " \t\r\n") i++ }
        fun take(c: Char) { whitespace(); require(i < input.length && input[i++] == c) }
        fun string(): String {
            take('"')
            return buildString {
                while (true) {
                    require(i < input.length)
                    val c = input[i++]
                    if (c == '"') break
                    require(c >= ' ')
                    if (c != '\\') append(c) else {
                        require(i < input.length)
                        when (val escape = input[i++]) {
                            '"', '\\', '/' -> append(escape)
                            'b' -> append('\b')
                            'f' -> append('\u000c')
                            'n' -> append('\n')
                            'r' -> append('\r')
                            't' -> append('\t')
                            'u' -> {
                                require(i + 4 <= input.length)
                                require(input.substring(i, i + 4).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                                append(input.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> error("Invalid escape")
                        }
                    }
                }
            }
        }
        fun value(depth: Int): Any? {
            require(depth <= 128) { "JSON nesting exceeds safety bound" }
            whitespace()
            if (i < input.length && input[i] == '"') return string()
            if (i < input.length && input[i] == '{') return objectValue(depth + 1)
            if (i < input.length && input[i] == '[') {
                take('['); whitespace()
                val list = mutableListOf<Any?>()
                if (i < input.length && input[i] != ']') while (true) {
                    list += value(depth + 1); whitespace(); require(i < input.length)
                    if (input[i] == ']') break
                    take(',')
                }
                take(']'); return list
            }
            val start = i
            while (i < input.length && input[i] !in ",}] \t\r\n") i++
            return when (val token = input.substring(start, i)) {
                "true" -> true
                "false" -> false
                "null" -> null
                else -> {
                    require(token.matches(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")))
                    token.toLongOrNull() ?: JsonNumber(token)
                }
            }
        }
        fun parse(): Map<String, Any?> {
            val fields = objectValue(0)
            whitespace(); require(i == input.length)
            return fields
        }
        fun objectValue(depth: Int): Map<String, Any?> {
            require(depth <= 128)
            take('{')
            val fields = linkedMapOf<String, Any?>()
            whitespace()
            if (i < input.length && input[i] != '}') while (true) {
                val key = string(); take(':')
                if (depth == 0) require(!fields.containsKey(key))
                fields[key] = value(depth)
                whitespace(); require(i < input.length)
                if (input[i] == '}') break
                take(',')
            }
            take('}')
            return fields
        }
    }
}
