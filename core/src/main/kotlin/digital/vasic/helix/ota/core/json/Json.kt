/*
 * Helix OTA — tiny dependency-free JSON for the OTA DTOs.
 * Deliberately minimal: enough to round-trip the protocol DTOs (objects, strings,
 * numbers, booleans, null, nested objects, arrays of objects). No kotlinx.
 *
 * This is NOT a general-purpose JSON library; it is scoped to the OTA wire shapes
 * so the :core module stays pure Kotlin/JVM with zero external runtime deps.
 */
package digital.vasic.helix.ota.core.json

/** A minimal JSON value model. */
sealed interface JsonValue {
    data class Obj(val entries: Map<String, JsonValue>) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val raw: String) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data object Null : JsonValue
}

/** Builder helpers for emitting [JsonValue.Obj] concisely. */
object Json {
    fun obj(vararg pairs: Pair<String, JsonValue?>): JsonValue.Obj =
        JsonValue.Obj(pairs.associate { (k, v) -> k to (v ?: JsonValue.Null) })

    fun arr(items: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(items)
    fun str(value: String?): JsonValue = value?.let { JsonValue.Str(it) } ?: JsonValue.Null
    fun num(value: Long?): JsonValue = value?.let { JsonValue.Num(it.toString()) } ?: JsonValue.Null
    fun num(value: Int?): JsonValue = value?.let { JsonValue.Num(it.toString()) } ?: JsonValue.Null
    fun bool(value: Boolean?): JsonValue = value?.let { JsonValue.Bool(it) } ?: JsonValue.Null

    /** Serialize a [JsonValue] tree to a compact JSON string. */
    fun write(value: JsonValue): String {
        val sb = StringBuilder()
        writeTo(sb, value)
        return sb.toString()
    }

    private fun writeTo(sb: StringBuilder, value: JsonValue) {
        when (value) {
            is JsonValue.Null -> sb.append("null")
            is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
            is JsonValue.Num -> sb.append(value.raw)
            is JsonValue.Str -> writeString(sb, value.value)
            is JsonValue.Arr -> {
                sb.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(',')
                    writeTo(sb, item)
                }
                sb.append(']')
            }
            is JsonValue.Obj -> {
                sb.append('{')
                var first = true
                for ((k, v) in value.entries) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k)
                    sb.append(':')
                    writeTo(sb, v)
                }
                sb.append('}')
            }
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else ->
                    if (c < ' ') sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else sb.append(c)
            }
        }
        sb.append('"')
    }

    /** Parse a JSON string into a [JsonValue] tree. */
    fun parse(text: String): JsonValue {
        val p = Parser(text)
        val v = p.parseValue()
        p.skipWhitespace()
        require(p.atEnd()) { "trailing characters after JSON value at index ${p.pos}" }
        return v
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd(): Boolean = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): JsonValue {
            skipWhitespace()
            require(!atEnd()) { "unexpected end of input" }
            return when (val c = s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else ->
                    if (c == '-' || c.isDigit()) parseNumber()
                    else throw IllegalArgumentException("unexpected char '$c' at index $pos")
            }
        }

        private fun parseObject(): JsonValue.Obj {
            expect('{')
            val map = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') { pos++; return JsonValue.Obj(map) }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++; continue }
                    '}' -> { pos++; break }
                    else -> throw IllegalArgumentException("expected ',' or '}' at index $pos")
                }
            }
            return JsonValue.Obj(map)
        }

        private fun parseArray(): JsonValue.Arr {
            expect('[')
            val list = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { pos++; return JsonValue.Arr(list) }
            while (true) {
                list.add(parseValue())
                skipWhitespace()
                when (peek()) {
                    ',' -> { pos++; continue }
                    ']' -> { pos++; break }
                    else -> throw IllegalArgumentException("expected ',' or ']' at index $pos")
                }
            }
            return JsonValue.Arr(list)
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                require(!atEnd()) { "unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(!atEnd()) { "unterminated escape" }
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                require(pos + 4 <= s.length) { "bad unicode escape" }
                                val hex = s.substring(pos, pos + 4)
                                pos += 4
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$e' at index ${pos - 1}")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            while (!atEnd() && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            return JsonValue.Num(s.substring(start, pos))
        }

        private fun parseBool(): JsonValue.Bool =
            if (s.startsWith("true", pos)) { pos += 4; JsonValue.Bool(true) }
            else if (s.startsWith("false", pos)) { pos += 5; JsonValue.Bool(false) }
            else throw IllegalArgumentException("invalid literal at index $pos")

        private fun parseNull(): JsonValue {
            require(s.startsWith("null", pos)) { "invalid literal at index $pos" }
            pos += 4
            return JsonValue.Null
        }

        private fun peek(): Char {
            require(!atEnd()) { "unexpected end of input" }
            return s[pos]
        }

        private fun expect(c: Char) {
            require(!atEnd() && s[pos] == c) { "expected '$c' at index $pos" }
            pos++
        }
    }
}

/** Accessors over [JsonValue.Obj] used by DTO codecs. */
fun JsonValue.asObj(): JsonValue.Obj =
    this as? JsonValue.Obj ?: throw IllegalArgumentException("expected JSON object, got $this")

fun JsonValue.Obj.str(key: String): String =
    (entries[key] as? JsonValue.Str)?.value
        ?: throw IllegalArgumentException("missing/non-string field '$key'")

fun JsonValue.Obj.strOrNull(key: String): String? =
    when (val v = entries[key]) {
        is JsonValue.Str -> v.value
        null, JsonValue.Null -> null
        else -> throw IllegalArgumentException("non-string field '$key'")
    }

fun JsonValue.Obj.long(key: String): Long =
    (entries[key] as? JsonValue.Num)?.raw?.toLong()
        ?: throw IllegalArgumentException("missing/non-number field '$key'")

fun JsonValue.Obj.longOrNull(key: String): Long? =
    when (val v = entries[key]) {
        is JsonValue.Num -> v.raw.toLong()
        null, JsonValue.Null -> null
        else -> throw IllegalArgumentException("non-number field '$key'")
    }

fun JsonValue.Obj.intOrNull(key: String): Int? =
    when (val v = entries[key]) {
        is JsonValue.Num -> v.raw.toInt()
        null, JsonValue.Null -> null
        else -> throw IllegalArgumentException("non-number field '$key'")
    }

fun JsonValue.Obj.obj(key: String): JsonValue.Obj =
    (entries[key] as? JsonValue.Obj)
        ?: throw IllegalArgumentException("missing/non-object field '$key'")

fun JsonValue.Obj.objOrNull(key: String): JsonValue.Obj? =
    when (val v = entries[key]) {
        is JsonValue.Obj -> v
        null, JsonValue.Null -> null
        else -> throw IllegalArgumentException("non-object field '$key'")
    }

fun JsonValue.Obj.arr(key: String): JsonValue.Arr =
    (entries[key] as? JsonValue.Arr)
        ?: throw IllegalArgumentException("missing/non-array field '$key'")
