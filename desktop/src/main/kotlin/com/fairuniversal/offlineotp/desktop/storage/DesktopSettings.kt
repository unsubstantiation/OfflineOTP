package com.fairuniversal.offlineotp.desktop.storage

import com.fairuniversal.offlineotp.Utilities.KeyValueSettings

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
class DesktopSettings(private val file: File,
    private val write: (File, ByteArray) -> Unit = AtomicFiles::writeAtomic) : KeyValueSettings {

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
        mutate(key, value)
    }

    @Synchronized
    override fun getInt(key: String, default: Int): Int =
        properties.getProperty(key)?.toIntOrNull() ?: default

    @Synchronized
    override fun putInt(key: String, value: Int) {
        mutate(key, value.toString())
    }

    @Synchronized
    override fun keys(): Set<String> = properties.stringPropertyNames().toSet()

    @Synchronized
    override fun remove(key: String) {
        if (properties.containsKey(key)) mutate(key, null)
    }

    /** Merge a validated batch with one file promotion. Publish the cache only after persistence. */
    @Synchronized
    internal fun putAll(values: Map<String, String>) {
        if (values.isEmpty()) return
        val candidate = Properties().apply { putAll(properties); putAll(values) }
        persist(candidate)
        properties.clear()
        properties.putAll(candidate)
    }

    /** Call under the settings monitor; readers cannot observe a partially applied mutation. */
    private fun mutate(key: String, value: String?) {
        val candidate = Properties().apply { putAll(properties) }
        if (value == null) candidate.remove(key) else candidate.setProperty(key, value)
        persist(candidate)
        properties.clear()
        properties.putAll(candidate)
    }

    @Throws(IOException::class)
    private fun persist(candidate: Properties) {
        val buffer = ByteArrayOutputStream()
        candidate.store(buffer, null)
        // Properties.store always adds a date comment; strip comment lines so the persisted file
        // is deterministic across machines and writes (only key=value lines remain).
        val deterministic = buffer.toString(Charsets.ISO_8859_1)
            .lineSequence()
            .filterNot { it.startsWith("#") }
            .joinToString(separator = "\n", postfix = "\n")
        write(file, deterministic.toByteArray(Charsets.ISO_8859_1))
    }
}
