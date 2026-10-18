// Sandbox-only stand-in for the org.json surface the entry/database sources use.
//
// Unlike the other stubs this one is a *replication*: the entry document boundary (EntryJson,
// Entry) is executed here, so the value semantics below follow the org.json the app links against
// (JSON-java/Android): `put(name, null)` removes a mapping, `get(name)` throws JSONException for a
// missing name, the JSON null sentinel is stored as JSONObject.NULL, and arrays are ordered.
// The parser is a strict JSON reader; it is not the platform parser and no Android behaviour,
// error message or extension (comments, single quotes) is replicated. See ../../../README.md.

package org.json

/** Thrown for a missing name, a type mismatch or malformed JSON text. */
class JSONException(message: String?) : Exception(message)

/** The JSON null sentinel, as JSONObject.NULL. */
object JSONNull {
    override fun toString(): String = "null"
}

class JSONArray {
    private val values = ArrayList<Any?>()

    constructor()
    constructor(json: String?) : this(jsonArray(json))

    internal constructor(parsed: List<Any?>) { values.addAll(parsed.map { converted(it) }) }

    fun length(): Int = values.size

    fun get(index: Int): Any {
        if (index < 0 || index >= values.size) throw JSONException("Index $index out of range")
        return values[index] ?: JSONNull
    }

    fun getString(index: Int): String {
        val value = get(index)
        return value as? String ?: throw JSONException("Value at $index is not a string")
    }

    fun getJSONObject(index: Int): JSONObject {
        val value = get(index)
        return value as? JSONObject ?: throw JSONException("Value at $index is not an object")
    }

    fun put(value: Any?): JSONArray {
        values.add(value)
        return this
    }

    fun opt(index: Int): Any? = if (index < 0 || index >= values.size) null else values[index]

    // Like the platform JSONStringer: no whitespace between elements.
    override fun toString(): String = values.joinToString(separator = ",", prefix = "[", postfix = "]") { render(it) }
}

class JSONObject {
    private val map = LinkedHashMap<String, Any?>()

    constructor()
    constructor(json: String?) : this(jsonObject(json))

    internal constructor(parsed: Map<String, Any?>) { map.putAll(parsed.mapValues { converted(it.value) }) }

    fun has(name: String?): Boolean = map.containsKey(name)

    fun length(): Int = map.size

    fun get(name: String?): Any {
        if (name == null || !map.containsKey(name)) throw JSONException("No value for $name")
        return map[name] ?: JSONNull
    }

    fun opt(name: String?): Any? = if (name == null) null else map[name]

    fun getString(name: String?): String {
        return get(name) as? String ?: throw JSONException("Value for $name is not a string")
    }

    fun optString(name: String?): String? = map[name] as? String

    fun optString(name: String?, fallback: String?): String? = optString(name) ?: fallback

    fun getLong(name: String?): Long {
        return toLong(get(name)) ?: throw JSONException("Value for $name is not a long")
    }

    fun optLong(name: String?): Long = toLong(map[name]) ?: 0L

    fun optLong(name: String?, fallback: Long): Long = toLong(map[name]) ?: fallback

    fun getInt(name: String?): Int {
        return toLong(get(name))?.toInt() ?: throw JSONException("Value for $name is not an int")
    }

    fun optInt(name: String?): Int = toLong(map[name])?.toInt() ?: 0

    fun optInt(name: String?, fallback: Int): Int = toLong(map[name])?.toInt() ?: fallback

    fun getBoolean(name: String?): Boolean {
        val value = get(name)
        return value as? Boolean ?: (value as? String)?.toBooleanStrictOrNull()
            ?: throw JSONException("Value for $name is not a boolean")
    }

    fun optBoolean(name: String?): Boolean = optBoolean(name, false)

    fun optBoolean(name: String?, fallback: Boolean): Boolean {
        return (map[name] as? Boolean) ?: (map[name] as? String)?.toBooleanStrictOrNull() ?: fallback
    }

    fun getJSONArray(name: String?): JSONArray {
        val value = get(name)
        return value as? JSONArray ?: throw JSONException("Value for $name is not an array")
    }

    fun getJSONObject(name: String?): JSONObject {
        val value = get(name)
        return value as? JSONObject ?: throw JSONException("Value for $name is not an object")
    }

    /** As in org.json, a null value removes the mapping instead of storing a JSON null. */
    fun put(name: String?, value: Any?): JSONObject {
        if (name == null) throw NullPointerException("name == null")
        if (value == null) map.remove(name) else map[name] = value
        return this
    }

    fun keys(): Iterator<String> = ArrayList(map.keys).iterator()

    override fun toString(): String =
        map.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { "${quote(it.key)}:${render(it.value)}" }

    companion object {
        /** The JSON null sentinel; a stored null reads back as this instead of a missing name. */
        @JvmField
        val NULL: Any = JSONNull

        private fun toLong(value: Any?): Long? = when (value) {
            is Number -> value.toLong()
            is String -> value.toDoubleOrNull()?.toLong()
            else -> null
        }
    }
}

/** Nested documents become JSONObject/JSONArray, as the platform parser returns them. */
internal fun converted(value: Any?): Any? = when (value) {
    is Map<*, *> -> JSONObject(value.entries.associate { it.key as String to it.value })
    is List<*> -> JSONArray(value)
    else -> value
}

internal fun render(value: Any?): String = when (value) {
    null, is JSONNull -> "null"
    is String -> quote(value)
    is Boolean -> value.toString()
    is Double -> if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
    is Number -> value.toString()
    else -> value.toString()
}

internal fun quote(value: String): String {
    val out = StringBuilder("\"")
    for (c in value) {
        when (c) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (c < ' ') out.append("\\u%04x".format(c.code)) else out.append(c)
        }
    }
    return out.append('"').toString()
}

/** Strict JSON reader: objects, arrays, strings with escapes, numbers, booleans and null. */
internal class JSONReader(private val text: String) {
    private var position = 0

    fun readDocument(): Any? {
        val value = readValue()
        skipWhitespace()
        if (position != text.length) fail("Trailing content")
        return value
    }

    /** [JSONTokener.nextValue]: one value from the current position, no whole-document check. */
    internal fun readValueForTokener(): Any? = readValue()

    /**
     * [JSONTokener.nextClean]: consumes and returns the next non-whitespace character, or NUL at the
     * end of the input. The platform class would also skip comments here; the vault parser rejects
     * comment syntax before the tokener runs (see [org.shadowice.flocke.andotp.Utilities.InputPolicy.jsonDepth]).
     */
    internal fun nextCleanForTokener(): Char {
        while (position < text.length) {
            val c = text[position]
            position++
            if (c == '\t' || c == ' ' || c == '\n' || c == '\r') continue
            return c
        }
        return 0.toChar()
    }

    private fun readValue(): Any? {
        skipWhitespace()
        if (position >= text.length) fail("Unexpected end of input")
        return when (val c = text[position]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> readLiteral("true", true)
            'f' -> readLiteral("false", false)
            'n' -> readLiteral("null", null)
            else -> if (c == '-' || c.isDigit()) readNumber() else fail("Unexpected character '$c'")
        }
    }

    private fun readObject(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        expect('{')
        skipWhitespace()
        if (peek() == '}') { position++; return result }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("Expected a name")
            val name = readString()
            skipWhitespace()
            expect(':')
            result[name] = readValue()
            skipWhitespace()
            when (val c = peek()) {
                ',' -> position++
                '}' -> { position++; return result }
                else -> fail("Expected ',' or '}' but found '${c ?: "end"}'")
            }
        }
    }

    private fun readArray(): List<Any?> {
        val result = ArrayList<Any?>()
        expect('[')
        skipWhitespace()
        if (peek() == ']') { position++; return result }
        while (true) {
            result.add(readValue())
            skipWhitespace()
            when (val c = peek()) {
                ',' -> position++
                ']' -> { position++; return result }
                else -> fail("Expected ',' or ']' but found '${c ?: "end"}'")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (position >= text.length) fail("Unterminated string")
            when (val c = text[position++]) {
                '"' -> return out.toString()
                '\\' -> out.append(readEscape())
                else -> out.append(c)
            }
        }
    }

    private fun readEscape(): Char {
        if (position >= text.length) fail("Unterminated escape")
        return when (val c = text[position++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (position + 4 > text.length) fail("Truncated unicode escape")
                val hex = text.substring(position, position + 4)
                position += 4
                hex.toIntOrNull(16)?.toChar() ?: fail("Invalid unicode escape")
            }
            else -> fail("Invalid escape '\\$c'")
        }
    }

    /** Integral literals stay integral: the model reads numbers through toString(). */
    private fun readNumber(): Any {
        val start = position
        if (peek() == '-') position++
        while (position < text.length && text[position].isDigit()) position++
        if (peek() == '.') {
            position++
            while (position < text.length && text[position].isDigit()) position++
        }
        if (peek() == 'e' || peek() == 'E') {
            position++
            if (peek() == '+' || peek() == '-') position++
            while (position < text.length && text[position].isDigit()) position++
        }
        val literal = text.substring(start, position)
        if ('.' !in literal && 'e' !in literal && 'E' !in literal) {
            literal.toLongOrNull()?.let { return it }
        }
        return literal.toDoubleOrNull() ?: fail("Invalid number '$literal'")
    }

    private fun readLiteral(literal: String, value: Any?): Any? {
        if (!text.startsWith(literal, position)) fail("Invalid literal")
        position += literal.length
        return value
    }

    private fun expect(c: Char) {
        if (position >= text.length || text[position] != c) fail("Expected '$c'")
        position++
    }

    private fun peek(): Char? = if (position >= text.length) null else text[position]

    private fun skipWhitespace() {
        while (position < text.length && text[position].isWhitespace()) position++
    }

    private fun fail(message: String): Nothing = throw JSONException("$message at $position")
}

/**
 * Stand-in for the platform tokener over the same reader: `nextValue()` (values converted to
 * [JSONObject]/[JSONArray] as the platform does) and `nextClean()`. Only the two entry points the
 * vault document parser uses exist here; comments, `nextString`, `back`, `skipTo`, `more` and the
 * remaining tokener API are not implemented. The vault document path runs through it in the
 * sandbox (over real `Entry` objects since the thumbnail catalogue is Kotlin). The platform
 * tokener's leniency is not replicated: single quotes and comments never reach it (the vault parser
 * rejects them first in `InputPolicy.jsonDepth`, which *is* executed here), while other platform
 * leniency such as unquoted names or values is strict in this stand-in and only a device shows it.
 */
class JSONTokener(json: String?) {
    private val reader = JSONReader(json ?: throw JSONException("Null input"))

    fun nextValue(): Any? = converted(reader.readValueForTokener())

    fun nextClean(): Char = reader.nextCleanForTokener()
}

private fun jsonObject(json: String?): Map<String, Any?> {
    if (json == null) throw JSONException("Null input")
    val parsed = JSONReader(json).readDocument()
    return parsed as? Map<String, Any?> ?: throw JSONException("Not an object")
}

private fun jsonArray(json: String?): List<Any?> {
    if (json == null) throw JSONException("Null input")
    val parsed = JSONReader(json).readDocument()
    return parsed as? List<Any?> ?: throw JSONException("Not an array")
}
