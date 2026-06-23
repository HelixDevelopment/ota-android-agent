package digital.vasic.helix.ota.core.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Branch-coverage suite for the hand-rolled JSON codec ([Json] + [JsonValue]
 * accessors). Targets the parser error/edge paths and the typed-accessor
 * mismatch branches that the round-trip protocol tests leave uncovered:
 * malformed input, escape sequences (\n \t \" \\ \/ \b \f \uXXXX), control-char
 * emission, number variants (negative/exponent/sign), empty containers, nested
 * structures, trailing garbage, and every accessor's "wrong type" throw.
 *
 * Assertions check the codec's ACTUAL behaviour (correct parse OR the documented
 * error), not mere invocation.
 */
class JsonCodecTest {

    // ---- parse: primitives & whitespace ----

    @Test
    fun parse_leadingAndTrailingWhitespace_isSkipped() {
        assertEquals(JsonValue.Bool(true), Json.parse("  \n\t true \r\n "))
        assertEquals(JsonValue.Null, Json.parse("\tnull\n"))
    }

    @Test
    fun parse_emptyInput_throws() {
        // parseValue -> require(!atEnd())
        assertFailsWith<IllegalArgumentException> { Json.parse("") }
        assertFailsWith<IllegalArgumentException> { Json.parse("    ") }
    }

    @Test
    fun parse_unexpectedLeadingChar_throws() {
        // parseValue else-branch: not -, not digit, not a known literal opener
        val ex = assertFailsWith<IllegalArgumentException> { Json.parse("$") }
        assertTrue(ex.message!!.contains("unexpected char"))
    }

    @Test
    fun parse_trailingGarbageAfterValue_throws() {
        // Json.parse require(atEnd()) after a complete value
        val ex = assertFailsWith<IllegalArgumentException> { Json.parse("true false") }
        assertTrue(ex.message!!.contains("trailing characters"))
        assertFailsWith<IllegalArgumentException> { Json.parse("{}x") }
        assertFailsWith<IllegalArgumentException> { Json.parse("123 456") }
    }

    // ---- parse: booleans & null literals (parseBool / parseNull branches) ----

    @Test
    fun parse_booleanLiterals() {
        assertEquals(JsonValue.Bool(true), Json.parse("true"))
        assertEquals(JsonValue.Bool(false), Json.parse("false"))
    }

    @Test
    fun parse_invalidTrueLiteral_throws() {
        // dispatched on 't' but not "true"
        val ex = assertFailsWith<IllegalArgumentException> { Json.parse("trxe") }
        assertTrue(ex.message!!.contains("invalid literal"))
    }

    @Test
    fun parse_invalidFalseLiteral_throws() {
        // dispatched on 'f' but not "true"/"false"
        assertFailsWith<IllegalArgumentException> { Json.parse("fxlse") }
    }

    @Test
    fun parse_invalidNullLiteral_throws() {
        // dispatched on 'n' but not "null"
        val ex = assertFailsWith<IllegalArgumentException> { Json.parse("nul") }
        assertTrue(ex.message!!.contains("invalid literal"))
        assertFailsWith<IllegalArgumentException> { Json.parse("nxll") }
    }

    // ---- parse: numbers (parseNumber branches: -, digits, .eE+-) ----

    @Test
    fun parse_numberVariants() {
        assertEquals(JsonValue.Num("0"), Json.parse("0"))
        assertEquals(JsonValue.Num("-1"), Json.parse("-1"))
        assertEquals(JsonValue.Num("379074366"), Json.parse("379074366"))
        assertEquals(JsonValue.Num("3.14"), Json.parse("3.14"))
        assertEquals(JsonValue.Num("-2.5e10"), Json.parse("-2.5e10"))
        assertEquals(JsonValue.Num("6.02E23"), Json.parse("6.02E23"))
        assertEquals(JsonValue.Num("1e+5"), Json.parse("1e+5"))
        assertEquals(JsonValue.Num("1e-5"), Json.parse("1e-5"))
    }

    @Test
    fun parse_bareMinusSign_producesEmptyNumberToken() {
        // '-' dispatches to parseNumber; loop consumes nothing more -> "-"
        assertEquals(JsonValue.Num("-"), Json.parse("-"))
    }

    // ---- parse: strings (parseString escape branches) ----

    @Test
    fun parse_simpleString() {
        assertEquals(JsonValue.Str("hello"), Json.parse("\"hello\""))
        assertEquals(JsonValue.Str(""), Json.parse("\"\""))
    }

    @Test
    fun parse_allTwoCharEscapes() {
        assertEquals(JsonValue.Str("\""), Json.parse(""" "\"" """.trim()))
        assertEquals(JsonValue.Str("\\"), Json.parse(""" "\\" """.trim()))
        assertEquals(JsonValue.Str("/"), Json.parse(""" "\/" """.trim()))
        assertEquals(JsonValue.Str("\n"), Json.parse(""" "\n" """.trim()))
        assertEquals(JsonValue.Str("\r"), Json.parse(""" "\r" """.trim()))
        assertEquals(JsonValue.Str("\t"), Json.parse(""" "\t" """.trim()))
        assertEquals(JsonValue.Str("\b"), Json.parse(""" "\b" """.trim()))
        assertEquals(JsonValue.Str("\u000C"), Json.parse(""" "\f" """.trim()))
    }

    @Test
    fun parse_unicodeEscape() {
        assertEquals(JsonValue.Str("A"), Json.parse(""" "\u0041" """.trim()))
        assertEquals(JsonValue.Str("\u00e9"), Json.parse(""" "\u00E9" """.trim()))
        // multiple, mixed with literal chars
        assertEquals(JsonValue.Str("a\tb"), Json.parse(""" "a\u0009b" """.trim()))
    }

    @Test
    fun parse_unknownEscape_throws() {
        val ex = assertFailsWith<IllegalArgumentException> { Json.parse(""" "\x" """.trim()) }
        assertTrue(ex.message!!.contains("bad escape"))
    }

    @Test
    fun parse_unterminatedString_throws() {
        // require(!atEnd()) inside the read loop
        val ex = assertFailsWith<IllegalArgumentException> { Json.parse("\"abc") }
        assertTrue(ex.message!!.contains("unterminated string"))
    }

    @Test
    fun parse_unterminatedEscape_throws() {
        // backslash then end-of-input
        val ex = assertFailsWith<IllegalArgumentException> { Json.parse("\"abc\\") }
        assertTrue(ex.message!!.contains("unterminated escape"))
    }

    @Test
    fun parse_truncatedUnicodeEscape_throws() {
        // require(pos + 4 <= length)
        val ex = assertFailsWith<IllegalArgumentException> { Json.parse(""" "\u00" """.trim()) }
        assertTrue(ex.message!!.contains("bad unicode escape"))
    }

    // ---- parse: objects (parseObject branches) ----

    @Test
    fun parse_emptyObject() {
        assertEquals(JsonValue.Obj(emptyMap()), Json.parse("{}"))
        assertEquals(JsonValue.Obj(emptyMap()), Json.parse("{   }"))
    }

    @Test
    fun parse_objectSingleAndMultiKey_preservesInsertionOrder() {
        val v = Json.parse("""{"a":1,"b":2,"c":3}""").asObj()
        assertEquals(listOf("a", "b", "c"), v.entries.keys.toList())
        assertEquals(JsonValue.Num("2"), v.entries["b"])
    }

    @Test
    fun parse_objectMissingColon_throws() {
        // expect(':') fails
        val ex = assertFailsWith<IllegalArgumentException> { Json.parse("""{"a" 1}""") }
        assertTrue(ex.message!!.contains("expected ':'"))
    }

    @Test
    fun parse_objectBadSeparator_throws() {
        // neither ',' nor '}' after a value
        val ex = assertFailsWith<IllegalArgumentException> { Json.parse("""{"a":1 "b":2}""") }
        assertTrue(ex.message!!.contains("expected ',' or '}'"))
    }

    @Test
    fun parse_objectKeyMustBeString_throws() {
        // parseString.expect('"') fails on a non-string key
        assertFailsWith<IllegalArgumentException> { Json.parse("""{1:2}""") }
    }

    @Test
    fun parse_objectUnterminated_throws() {
        // expect(':') / parseString hits end of input
        assertFailsWith<IllegalArgumentException> { Json.parse("""{"a":1""") }
    }

    @Test
    fun parse_objectKeyWithNoColonAtEnd_throws() {
        // After the key, input ends exactly where ':' is expected:
        // exercises expect()'s atEnd() == true branch (vs the wrong-char branch).
        val ex = assertFailsWith<IllegalArgumentException> { Json.parse("""{"a"""") }
        assertTrue(ex.message!!.contains("expected ':'"))
    }

    // ---- parse: arrays (parseArray branches) ----

    @Test
    fun parse_emptyArray() {
        assertEquals(JsonValue.Arr(emptyList()), Json.parse("[]"))
        assertEquals(JsonValue.Arr(emptyList()), Json.parse("[  ]"))
    }

    @Test
    fun parse_arrayMixedElements() {
        val v = Json.parse("""[1,"two",true,null,{"k":[]}]""") as JsonValue.Arr
        assertEquals(5, v.items.size)
        assertEquals(JsonValue.Num("1"), v.items[0])
        assertEquals(JsonValue.Str("two"), v.items[1])
        assertEquals(JsonValue.Bool(true), v.items[2])
        assertEquals(JsonValue.Null, v.items[3])
    }

    @Test
    fun parse_arrayBadSeparator_throws() {
        // neither ',' nor ']' after an element
        val ex = assertFailsWith<IllegalArgumentException> { Json.parse("[1 2]") }
        assertTrue(ex.message!!.contains("expected ',' or ']'"))
    }

    @Test
    fun parse_arrayUnterminated_throws() {
        // peek() hits end of input
        assertFailsWith<IllegalArgumentException> { Json.parse("[1,2") }
    }

    @Test
    fun parse_deeplyNestedStructure() {
        val v = Json.parse("""{"a":{"b":{"c":[1,[2,[3]]]}}}""").asObj()
        val c = v.obj("a").obj("b").arr("c")
        assertEquals(JsonValue.Num("1"), c.items[0])
        assertEquals(JsonValue.Arr(listOf(JsonValue.Num("2"), JsonValue.Arr(listOf(JsonValue.Num("3"))))), c.items[1])
    }

    // ---- write: escaping + control chars (writeString branches) ----

    @Test
    fun write_escapesAllSpecialChars() {
        val s = "q\"b\\s\nl\rr\tt\bb\u000Cf"
        val out = Json.write(JsonValue.Str(s))
        assertEquals("\"q\\\"b\\\\s\\nl\\rr\\tt\\bb\\ff\"", out)
    }

    @Test
    fun write_lowControlChar_emitsUnicodeEscape() {
        // c < ' ' and not one of the named escapes -> \u00xx
        assertEquals("\"\\u0001\"", Json.write(JsonValue.Str("\u0001")))
        assertEquals("\"\\u001f\"", Json.write(JsonValue.Str("\u001f")))
    }

    @Test
    fun write_printableCharsPassThrough() {
        assertEquals("\"abc 123!\"", Json.write(JsonValue.Str("abc 123!")))
    }

    @Test
    fun write_allValueKinds() {
        assertEquals("null", Json.write(JsonValue.Null))
        assertEquals("true", Json.write(JsonValue.Bool(true)))
        assertEquals("false", Json.write(JsonValue.Bool(false)))
        assertEquals("42", Json.write(JsonValue.Num("42")))
        assertEquals("[1,2]", Json.write(JsonValue.Arr(listOf(JsonValue.Num("1"), JsonValue.Num("2")))))
        assertEquals("[]", Json.write(JsonValue.Arr(emptyList())))
        assertEquals("{}", Json.write(JsonValue.Obj(emptyMap())))
        assertEquals(
            """{"a":1,"b":true}""",
            Json.write(
                JsonValue.Obj(linkedMapOf("a" to JsonValue.Num("1"), "b" to JsonValue.Bool(true))),
            ),
        )
    }

    @Test
    fun write_then_parse_roundTripsNestedTree() {
        val tree = JsonValue.Obj(
            linkedMapOf(
                "name" to JsonValue.Str("tab\there"),
                "nums" to JsonValue.Arr(listOf(JsonValue.Num("-1"), JsonValue.Num("2.5e3"))),
                "nested" to JsonValue.Obj(linkedMapOf("flag" to JsonValue.Bool(false), "n" to JsonValue.Null)),
            ),
        )
        assertEquals(tree, Json.parse(Json.write(tree)))
    }

    // ---- builder helpers (Json.obj/arr/str/num/bool null-handling) ----

    @Test
    fun builders_nullArgsBecomeJsonNull() {
        assertEquals(JsonValue.Null, Json.str(null))
        assertEquals(JsonValue.Null, Json.num(null as Long?))
        assertEquals(JsonValue.Null, Json.num(null as Int?))
        assertEquals(JsonValue.Null, Json.bool(null))
        assertEquals(JsonValue.Str("x"), Json.str("x"))
        assertEquals(JsonValue.Num("7"), Json.num(7L))
        assertEquals(JsonValue.Num("7"), Json.num(7))
        assertEquals(JsonValue.Bool(true), Json.bool(true))
    }

    @Test
    fun builders_objMapsNullValuesToJsonNull() {
        val o = Json.obj("present" to JsonValue.Str("v"), "absent" to null)
        assertEquals(JsonValue.Str("v"), o.entries["present"])
        assertEquals(JsonValue.Null, o.entries["absent"])
    }

    @Test
    fun builders_arrWrapsList() {
        assertEquals(JsonValue.Arr(listOf(JsonValue.Num("1"))), Json.arr(listOf(JsonValue.Num("1"))))
    }

    // ---- accessors: asObj ----

    @Test
    fun asObj_onObj_returnsSameInstance() {
        val o = JsonValue.Obj(mapOf("a" to JsonValue.Num("1")))
        assertSame(o, o.asObj())
    }

    @Test
    fun asObj_onNonObj_throws() {
        val ex = assertFailsWith<IllegalArgumentException> { JsonValue.Str("x").asObj() }
        assertTrue(ex.message!!.contains("expected JSON object"))
    }

    // ---- accessors: str / strOrNull ----

    private fun obj(vararg pairs: Pair<String, JsonValue>) = JsonValue.Obj(linkedMapOf(*pairs))

    @Test
    fun str_present_returnsValue() {
        assertEquals("v", obj("k" to JsonValue.Str("v")).str("k"))
    }

    @Test
    fun str_missing_throws() {
        assertFailsWith<IllegalArgumentException> { obj("k" to JsonValue.Str("v")).str("other") }
    }

    @Test
    fun str_wrongType_throws() {
        assertFailsWith<IllegalArgumentException> { obj("k" to JsonValue.Num("1")).str("k") }
    }

    @Test
    fun strOrNull_threeBranches() {
        assertEquals("v", obj("k" to JsonValue.Str("v")).strOrNull("k"))
        assertNull(obj("k" to JsonValue.Null).strOrNull("k"))   // explicit JSON null
        assertNull(obj("k" to JsonValue.Str("v")).strOrNull("absent")) // missing key
        assertFailsWith<IllegalArgumentException> { obj("k" to JsonValue.Num("1")).strOrNull("k") }
    }

    // ---- accessors: long / longOrNull ----

    @Test
    fun long_present_parses() {
        assertEquals(379074366L, obj("k" to JsonValue.Num("379074366")).long("k"))
    }

    @Test
    fun long_missing_throws() {
        assertFailsWith<IllegalArgumentException> { obj("k" to JsonValue.Num("1")).long("absent") }
    }

    @Test
    fun long_wrongType_throws() {
        assertFailsWith<IllegalArgumentException> { obj("k" to JsonValue.Str("1")).long("k") }
    }

    @Test
    fun long_nonNumericNumToken_throwsNumberFormat() {
        // Num.raw is not coerced at parse time; toLong() throws on a malformed token.
        assertFailsWith<NumberFormatException> { obj("k" to JsonValue.Num("-")).long("k") }
    }

    @Test
    fun longOrNull_branches() {
        assertEquals(5L, obj("k" to JsonValue.Num("5")).longOrNull("k"))
        assertNull(obj("k" to JsonValue.Null).longOrNull("k"))
        assertNull(obj("k" to JsonValue.Num("5")).longOrNull("absent"))
        assertFailsWith<IllegalArgumentException> { obj("k" to JsonValue.Bool(true)).longOrNull("k") }
    }

    // ---- accessors: intOrNull ----

    @Test
    fun intOrNull_branches() {
        assertEquals(88, obj("k" to JsonValue.Num("88")).intOrNull("k"))
        assertNull(obj("k" to JsonValue.Null).intOrNull("k"))
        assertNull(obj("k" to JsonValue.Num("88")).intOrNull("absent"))
        assertFailsWith<IllegalArgumentException> { obj("k" to JsonValue.Str("x")).intOrNull("k") }
    }

    // ---- accessors: obj / objOrNull ----

    @Test
    fun obj_present_returnsNested() {
        val inner = JsonValue.Obj(mapOf("x" to JsonValue.Num("1")))
        assertEquals(inner, obj("k" to inner).obj("k"))
    }

    @Test
    fun obj_missingOrWrongType_throws() {
        assertFailsWith<IllegalArgumentException> { obj("k" to JsonValue.Num("1")).obj("absent") }
        assertFailsWith<IllegalArgumentException> { obj("k" to JsonValue.Num("1")).obj("k") }
    }

    @Test
    fun objOrNull_branches() {
        val inner = JsonValue.Obj(mapOf("x" to JsonValue.Num("1")))
        assertEquals(inner, obj("k" to inner).objOrNull("k"))
        assertNull(obj("k" to JsonValue.Null).objOrNull("k"))
        assertNull(obj("k" to inner).objOrNull("absent"))
        assertFailsWith<IllegalArgumentException> { obj("k" to JsonValue.Str("x")).objOrNull("k") }
    }

    // ---- accessors: arr ----

    @Test
    fun arr_present_returnsArray() {
        val a = JsonValue.Arr(listOf(JsonValue.Num("1")))
        assertEquals(a, obj("k" to a).arr("k"))
    }

    @Test
    fun arr_missingOrWrongType_throws() {
        assertFailsWith<IllegalArgumentException> { obj("k" to JsonValue.Num("1")).arr("absent") }
        assertFailsWith<IllegalArgumentException> { obj("k" to JsonValue.Num("1")).arr("k") }
    }
}
