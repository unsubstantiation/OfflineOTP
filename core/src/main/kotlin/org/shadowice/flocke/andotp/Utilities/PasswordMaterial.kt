package org.shadowice.flocke.andotp.Utilities

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Bounded payload for one imported password, retaining every source CSV column. */
object PasswordMaterial {
    const val MAX_BYTES = BrowserPasswordCsv.MAX_FILE_BYTES
    private const val MAGIC = 0x50574431 // PWD1

    class Fields internal constructor(private val names: List<String>, private val values: List<String>) {
        fun value(name: String): String = names.indexOfFirst { it.trim().equals(name, ignoreCase = true) }
            .let { if (it < 0) "" else values[it] }
        fun columns(): List<String> = names.toList()
        fun valueAt(index: Int): String = values[index]
        override fun toString(): String = "PasswordMaterial.Fields(redacted)"
    }

    fun encode(columns: List<String>, row: BrowserPasswordCsv.Row): ByteArray {
        require(columns.size in 1..BrowserPasswordCsv.MAX_COLUMNS && columns.size == row.size)
        val fields = ArrayList<ByteArray>(columns.size * 2)
        try {
            var size = 8L
            fun field(value: String) {
                require(value.length <= BrowserPasswordCsv.MAX_FIELD_CHARS)
                val bytes = value.toByteArray(Charsets.UTF_8)
                fields.add(bytes)
                size += 4L + bytes.size
                require(size <= MAX_BYTES)
            }
            for (i in columns.indices) { field(columns[i]); field(row.valueAt(i)) }
            val result = ByteBuffer.allocate(size.toInt()).putInt(MAGIC).putInt(columns.size)
            for (bytes in fields) result.putInt(bytes.size).put(bytes)
            return result.array().also { decode(it) }
        } finally { fields.forEach { it.fill(0) } }
    }

    fun decode(bytes: ByteArray): Fields {
        require(bytes.size in 8..MAX_BYTES)
        val input = ByteBuffer.wrap(bytes)
        require(input.int == MAGIC)
        val count = input.int
        require(count in 1..BrowserPasswordCsv.MAX_COLUMNS)
        fun field(): String {
            require(input.remaining() >= 4)
            val length = input.int
            require(length in 0..input.remaining() && length <= MAX_BYTES)
            val slice = input.slice()
            slice.limit(length)
            val value = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(slice).toString()
            } catch (_: Exception) { throw IllegalArgumentException("Invalid password data") }
            require(value.length <= BrowserPasswordCsv.MAX_FIELD_CHARS)
            input.position(input.position() + length)
            return value
        }
        val names = ArrayList<String>(count)
        val values = ArrayList<String>(count)
        repeat(count) { names.add(field()); values.add(field()) }
        require(!input.hasRemaining())
        val normalized = names.map { it.trim().lowercase(Locale.ROOT) }
        require(normalized.none { it.isEmpty() } && normalized.toSet().size == count)
        require(normalized.containsAll(listOf("url", "username", "password")))
        return Fields(names, values)
    }
}
