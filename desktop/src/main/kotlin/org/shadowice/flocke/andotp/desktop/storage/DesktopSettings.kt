package org.shadowice.flocke.andotp.desktop.storage

import org.shadowice.flocke.andotp.Utilities.KeyValueSettings

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Properties

/**
 * [KeyValueSettings] backed by a plain Java properties file next to the vault. All values are
 * strings or ints (including the Base64 URL-safe salt and verifier), so the file stays portable
 * and human-inspectable without carrying any secrets beyond the verifier that Android stores the
 * same way. Each mutation is persisted through a synced temp file and an atomic move, matching the
 * durability guarantees of [NioAtomicFileStore].
 */
class DesktopSettings(private val file: File) : KeyValueSettings {

    private val properties = Properties()

    init {
        if (file.exists()) {
            file.inputStream().use { properties.load(it) }
        }
    }

    @Synchronized
    override fun getString(key: String, default: String): String =
        properties.getProperty(key, default)

    @Synchronized
    override fun putString(key: String, value: String) {
        properties.setProperty(key, value)
        persist()
    }

    @Synchronized
    override fun getInt(key: String, default: Int): Int =
        properties.getProperty(key)?.toIntOrNull() ?: default

    @Synchronized
    override fun putInt(key: String, value: Int) {
        properties.setProperty(key, value.toString())
        persist()
    }

    @Synchronized
    override fun remove(key: String) {
        if (properties.remove(key) != null) persist()
    }

    @Throws(IOException::class)
    private fun persist() {
        val buffer = ByteArrayOutputStream()
        properties.store(buffer, null)
        // Properties.store always adds a date comment; strip comment lines so the persisted file
        // is deterministic across machines and writes (only key=value lines remain).
        val deterministic = buffer.toString(Charsets.ISO_8859_1)
            .lineSequence()
            .filterNot { it.startsWith("#") }
            .joinToString(separator = "\n", postfix = "\n")
        AtomicFiles.writeAtomic(file, deterministic.toByteArray(Charsets.ISO_8859_1))
    }
}
