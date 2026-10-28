package org.shadowice.flocke.andotp.Utilities

import java.io.IOException

/**
 * Platform port for one replaceable, crash-safe byte file (the vault, its backup or a journal).
 *
 * The Android adapter is backed by `android.util.AtomicFile`; the desktop adapter uses a
 * `java.nio` temp-file-and-move pair. Implementations must guarantee that a failed write never
 * leaves a partially promoted target and that [read] enforces the document size bound, so the
 * portable [PasswordVault] engine stays identical on both platforms.
 */
interface AtomicByteStore {
    /** True when a promoted target (or a still-recoverable staging file) exists. */
    fun exists(): Boolean

    /**
     * Reads the whole target, bounded by [BoundedInputReader.MAX_DOCUMENT_BYTES]. Throws
     * [IOException] for an I/O failure or an oversized document.
     */
    @Throws(IOException::class)
    fun read(): ByteArray

    /**
     * Writes [data] through a synced staging file, promotes it atomically, and verifies the
     * promoted bytes equal [data]. A failed promotion must not corrupt the previous target.
     */
    @Throws(IOException::class)
    fun writeAtomic(data: ByteArray)

    /** Removes the target and any staging files; a missing target is not an error. */
    fun delete()
}

/**
 * Platform port for the small, flat key/value surface a password vault needs. The Android adapter
 * is a `SharedPreferences` view; the desktop adapter is a properties file. Values are restricted
 * to strings and ints, which keeps every persisted credential encoding portable.
 */
interface KeyValueSettings {
    fun getString(key: String, default: String): String

    fun putString(key: String, value: String)

    fun getInt(key: String, default: Int): Int

    fun putInt(key: String, value: Int)

    fun remove(key: String)
}

/**
 * Stable setting keys shared by the desktop properties file and any future portable settings
 * adapter. They intentionally do not mirror Android resource IDs: a platform owns its own store,
 * only the value semantics (and Base64 encodings) are shared.
 */
object VaultSettingsKeys {
    const val AUTH_METHOD = "auth_method"
    const val ENCRYPTION = "encryption"
    const val AUTH_CREDENTIALS = "auth_credentials"
    const val AUTH_SALT = "auth_salt"
    const val AUTH_ITERATIONS = "auth_iterations"
}
