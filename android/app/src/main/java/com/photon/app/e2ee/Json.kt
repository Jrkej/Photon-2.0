package com.photon.app.e2ee

/**
 * Just enough JSON for the metadata section (e2ee.md 6.1), without Android's `org.json` (a stub in JVM
 * unit tests). [parse] reads any JSON value: objects become [Map]s (insertion order, last duplicate wins),
 * arrays [List]s, integers [Long] (or [Double] when they don't fit), other numbers [Double].
 */
object Json {
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipSpace()
        val value = p.value(0)
        p.skipSpace()
        if (!p.atEnd()) p.fail("trailing characters")
        return value
    }

    /** A JSON string literal, escaped as Python's `json.dumps` does (non-ASCII as `\uXXXX`). */
    fun quote(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c < ' ' || c > '~') out.append("\\u").append(c.code.toString(16).padStart(4, '0')) else out.append(c)
            }
        }
        return out.append('"').toString()
    }

    class JsonException(message: String) : Exception(message)

    private class Parser(private val s: String) {
        private var i = 0

        fun atEnd() = i >= s.length

        fun fail(why: String): Nothing = throw JsonException("$why at offset $i")

        fun skipSpace() {
            while (i < s.length && s[i] in " \t\r\n") i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nested too deeply")
            if (atEnd()) fail("unexpected end")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            skipSpace()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipSpace()
                if (atEnd() || s[i] != '"') fail("expected a key")
                val key = string()
                skipSpace()
                if (atEnd() || s[i] != ':') fail("expected ':'")
                i++
                skipSpace()
                out[key] = value(depth + 1)
                skipSpace()
                if (atEnd()) fail("unexpected end")
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> { i--; fail("expected ',' or '}'") }
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            skipSpace()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                skipSpace()
                out += value(depth + 1)
                skipSpace()
                if (atEnd()) fail("unexpected end")
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> { i--; fail("expected ',' or ']'") }
                }
            }
        }

        private fun string(): String {
            val out = StringBuilder()
            i++ // opening quote
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("short \\u escape")
                                val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("bad \\u escape")
                                out.append(code.toChar())
                                i += 4
                            }
                            else -> fail("bad escape")
                        }
                    }
                    c < ' ' -> fail("control character in string")
                    else -> out.append(c)
                }
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("unexpected token")
            i += word.length
            return value
        }

        private fun number(): Any {
            val start = i
            if (i < s.length && s[i] == '-') i++
            if (atEnd() || !s[i].isDigit()) fail("unexpected token")
            if (s[i] == '0') i++ else while (i < s.length && s[i].isDigit()) i++
            var integral = true
            if (i < s.length && s[i] == '.') {
                integral = false
                i++
                if (atEnd() || !s[i].isDigit()) fail("bad number")
                while (i < s.length && s[i].isDigit()) i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                integral = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                if (atEnd() || !s[i].isDigit()) fail("bad number")
                while (i < s.length && s[i].isDigit()) i++
            }
            val text = s.substring(start, i)
            return if (integral) text.toLongOrNull() ?: text.toDouble() else text.toDouble()
        }

        private companion object {
            const val MAX_DEPTH = 64
        }
    }
}
