package com.flint.peakfocus.core.history

/** Validates JSON iteratively. Only the six common envelope fields are materialized. */
internal object HistoryEnvelope {
    fun read(line: String): HistoryEvent = Scanner(line).read()

    private class Scanner(private val input: String) {
        private var i = 0
        private var stack = ByteArray(64)
        private var depth = 0
        private var maximumDepth = 0
        private fun whitespace() { while (i < input.length && input[i] in " \t\r\n") i++ }
        private fun take(c: Char) { whitespace(); require(i < input.length && input[i++] == c) }
        private fun string(capture: Boolean): String? {
            take('"')
            val value = if (capture) StringBuilder() else null
            var bounded = capture
            while (true) {
                require(i < input.length)
                var c = input[i++]
                if (c == '"') return if (bounded) value.toString() else null
                require(c >= ' ')
                if (c == '\\') {
                    require(i < input.length)
                    c = when (val escape = input[i++]) {
                        '"', '\\', '/' -> escape
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            require(i + 4 <= input.length)
                            var code = 0
                            repeat(4) { code = code * 16 + (input[i++].digitToIntOrNull(16) ?: error("Invalid escape")) }
                            code.toChar()
                        }
                        else -> error("Invalid escape")
                    }
                }
                if (bounded) {
                    if (value!!.length < 4096) value.append(c) else bounded = false
                }
            }
        }
        private fun number(): Long? {
            val start = i
            if (i < input.length && input[i] == '-') i++
            require(i < input.length)
            if (input[i] == '0') i++ else {
                require(input[i] in '1'..'9')
                while (i < input.length && input[i] in '0'..'9') i++
            }
            var integral = true
            if (i < input.length && input[i] == '.') {
                integral = false; i++
                val digits = i
                while (i < input.length && input[i] in '0'..'9') i++
                require(i > digits)
            }
            if (i < input.length && input[i] in "eE") {
                integral = false; i++
                if (i < input.length && input[i] in "+-") i++
                val digits = i
                while (i < input.length && input[i] in '0'..'9') i++
                require(i > digits)
            }
            return if (integral && i - start <= 20) input.substring(start, i).toLongOrNull() else null
        }
        private fun push(state: Int) {
            if (depth == stack.size) stack = stack.copyOf(stack.size * 2)
            stack[depth++] = state.toByte()
            maximumDepth = maxOf(maximumDepth, depth)
        }
        private fun value() {
            whitespace(); require(i < input.length)
            when (input[i]) {
                '"' -> string(false)
                '{' -> { i++; push(0) }
                '[' -> { i++; push(3) }
                't', 'f', 'n' -> {
                    val literal = when (input[i]) { 't' -> "true"; 'f' -> "false"; else -> "null" }
                    require(input.regionMatches(i, literal, 0, literal.length)); i += literal.length
                }
                else -> number()
            }
        }
        private fun skipValue() {
            value()
            while (depth > 0) {
                whitespace(); require(i < input.length)
                when (stack[depth - 1].toInt()) {
                    0, 1 -> {
                        if (stack[depth - 1].toInt() == 0 && input[i] == '}') { i++; depth-- } else {
                            string(false); take(':'); stack[depth - 1] = 2; value()
                        }
                    }
                    2 -> when (input[i++]) { ',' -> stack[depth - 1] = 1; '}' -> depth--; else -> error("Invalid object") }
                    3, 4 -> {
                        if (stack[depth - 1].toInt() == 3 && input[i] == ']') { i++; depth-- } else {
                            stack[depth - 1] = 5; value()
                        }
                    }
                    5 -> when (input[i++]) { ',' -> stack[depth - 1] = 4; ']' -> depth--; else -> error("Invalid array") }
                }
            }
        }
        fun read(): HistoryEvent {
            val fields = mutableMapOf<String, Any?>()
            take('{'); whitespace()
            if (i < input.length && input[i] != '}') while (true) {
                val key = string(true); take(':'); whitespace()
                if (key in setOf("v", "id", "type", "session", "at", "tz")) {
                    require(!fields.containsKey(key))
                    fields[key!!] = if (key == "v" || key == "at") number() else string(true)
                } else skipValue()
                whitespace(); require(i < input.length)
                if (input[i] == '}') break
                take(',')
            }
            take('}'); whitespace(); require(i == input.length)
            fun text(key: String) = (fields[key] as? String)?.takeIf { it.isNotBlank() } ?: error(key)
            val version = (fields["v"] as? Long)?.takeIf { it in 1..Int.MAX_VALUE }?.toInt() ?: error("v")
            return HistoryEvent(v = version, id = text("id"), type = text("type"), session = text("session"),
                at = fields["at"] as? Long ?: error("at"), tz = text("tz"), originalLine = input, opaque = maximumDepth > 128)
        }
    }
}
