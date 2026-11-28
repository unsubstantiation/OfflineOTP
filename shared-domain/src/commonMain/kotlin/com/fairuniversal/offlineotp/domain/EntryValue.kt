package com.fairuniversal.offlineotp.domain

/** Lossless JSON values: numbers retain their decimal lexeme, never a platform Double.
 * Values can contain passwords/private keys. Diagnostic rendering is always redacted.
 * Containers own snapshots; strings are immutable and cannot be guaranteed physically wiped.
 */
sealed class EntryValue {
    abstract fun json(): String
    final override fun toString(): String = "EntryValue(redacted)"
}
class EntryText(val value: String) : EntryValue() { override fun json() = EntryJsonText.quote(value) }
class EntryNumber internal constructor(val decimal: String) : EntryValue() { override fun json() = decimal }
class EntryBoolean(val value: Boolean) : EntryValue() { override fun json() = if (value) "true" else "false" }
object EntryNull : EntryValue() { override fun json() = "null" }
class EntryArray(values: List<EntryValue>) : EntryValue() {
    private val owned = values.toList()
    val values: List<EntryValue> get() = owned.toList()
    override fun json() = owned.joinToString(",", "[", "]") { it.json() }
}
class EntryObject(fields: Map<String, EntryValue>) : EntryValue() {
    private val owned = fields.toMap()
    val fields: Map<String, EntryValue> get() = owned.toMap()
    operator fun get(name: String): EntryValue? = owned[name]
    override fun json() = owned.entries.joinToString(",", "{", "}") { EntryJsonText.quote(it.key) + ":" + it.value.json() }
}

/** Strict bounded RFC 8259 reader shared by all document adapters. No comments, trailing commas,
 * duplicate names, coercing numbers, or partial-document success. Max nesting includes the root.
 */
internal object EntryJsonText {
    fun quote(text: String): String = buildString {
        append('"')
        var previous = '\u0000'
        for (char in text) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '/' -> { if (previous == '<') append('\\'); append('/') }
                '\b' -> append("\\b")
                '\t' -> append("\\t")
                '\n' -> append("\\n")
                '\u000c' -> append("\\f")
                '\r' -> append("\\r")
                else -> if (char < ' ' || char in '\u0080'..'\u009f' || char in '\u2000'..'\u20ff') {
                    append("\\u"); append(char.code.toString(16).padStart(4, '0'))
                } else append(char)
            }
            previous = char
        }
        append('"')
    }

    fun parse(text: String): EntryValue = Reader(text).read()

    private class Reader(private val text: String) {
        private var offset = 0
        private var nodes = 0
        fun read(): EntryValue {
            val value = value(0)
            whitespace()
            require(offset == text.length)
            return value
        }
        private fun whitespace() { while (offset < text.length && text[offset] in " \t\n\r") offset++ }
        private fun take(char: Char): Boolean {
            if (offset < text.length && text[offset] == char) { offset++; return true }
            return false
        }
        private fun value(depth: Int): EntryValue {
            require(++nodes <= EntryDocumentCodec.MAX_VALUES)
            whitespace()
            require(offset < text.length)
            return when (text[offset]) {
                '{' -> {
                    require(depth < EntryDocumentCodec.MAX_DEPTH); offset++
                    val fields = linkedMapOf<String, EntryValue>()
                    whitespace()
                    if (!take('}')) {
                        do {
                            whitespace(); val name = string()
                            require(!fields.containsKey(name)); whitespace(); require(take(':'))
                            fields[name] = value(depth + 1); whitespace()
                        } while (take(','))
                        require(take('}'))
                    }
                    EntryObject(fields)
                }
                '[' -> {
                    require(depth < EntryDocumentCodec.MAX_DEPTH); offset++
                    val values = ArrayList<EntryValue>()
                    whitespace()
                    if (!take(']')) {
                        do { values.add(value(depth + 1)); whitespace() } while (take(','))
                        require(take(']'))
                    }
                    EntryArray(values)
                }
                '"' -> EntryText(string())
                't' -> { literal("true"); EntryBoolean(true) }
                'f' -> { literal("false"); EntryBoolean(false) }
                'n' -> { literal("null"); EntryNull }
                else -> number()
            }
        }
        private fun literal(word: String) { require(text.startsWith(word, offset)); offset += word.length }
        private fun string(): String {
            require(take('"'))
            val result = StringBuilder()
            while (offset < text.length) {
                var char = text[offset++]
                if (char == '"') return result.toString().also { validUnicode(it) }
                require(char >= ' ')
                if (char == '\\') {
                    require(offset < text.length)
                    char = text[offset++]
                    result.append(when (char) {
                        '"', '\\', '/' -> char
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        'u' -> {
                            require(offset + 4 <= text.length)
                            val hex = text.substring(offset, offset + 4)
                            require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                            offset += 4; hex.toInt(16).toChar()
                        }
                        else -> throw IllegalArgumentException()
                    })
                } else result.append(char)
            }
            throw IllegalArgumentException()
        }
        private fun validUnicode(value: String) {
            var index = 0
            while (index < value.length) {
                val char = value[index++]
                if (char in '\ud800'..'\udbff') {
                    require(index < value.length && value[index++] in '\udc00'..'\udfff')
                } else require(char !in '\udc00'..'\udfff')
            }
        }
        private fun number(): EntryNumber {
            val start = offset
            take('-')
            if (!take('0')) {
                require(offset < text.length && text[offset] in '1'..'9')
                digits()
            }
            if (take('.')) { val startFraction = offset; digits(); require(offset > startFraction) }
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                val startExponent = offset; digits(); require(offset > startExponent)
            }
            return EntryNumber(text.substring(start, offset))
        }
        private fun digits() { while (offset < text.length && text[offset] in '0'..'9') offset++ }
    }
}
