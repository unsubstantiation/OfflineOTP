package com.fairuniversal.offlineotp.Utilities


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

    fun encode(columns: List<String>, row: BrowserPasswordCsv.Row): ByteArray =
        encodeFields(columns.indices.map { columns[it] to row.valueAt(it) })

    fun encodeFields(pairs: List<Pair<String, String>>): ByteArray =
        requireNotNull(com.fairuniversal.offlineotp.domain.CredentialMaterialCodec().passwordEncode(
            pairs.map { it.first }, pairs.map { it.second })) { "Invalid password data" }

    fun decode(bytes: ByteArray): Fields {
        val fields = requireNotNull(com.fairuniversal.offlineotp.domain.CredentialMaterialCodec().passwordDecode(bytes)) { "Invalid password data" }
        return Fields(fields.names, fields.values)
    }
}
