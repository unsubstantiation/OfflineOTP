package com.fairuniversal.offlineotp.Utilities

/** Ephemeral only: never serialized, exported as preferences, or put in backup configuration. */
class SessionOpenPgpSigningCredentials : AutoCloseable {
    class Snapshot internal constructor(val key: ByteArray, val passphrase: CharArray) : AutoCloseable {
        fun copy(): Snapshot = Snapshot(key.copyOf(), passphrase.copyOf())
        override fun close() { key.fill(0); passphrase.fill('\u0000') }
        override fun toString() = "OpenPgpSigningSnapshot(redacted)"
    }
    private var key: ByteArray? = null
    private var passphrase: CharArray? = null
    private var closed = false
    private var revision = 0L
    @Synchronized fun revision(): Long = revision
    @Synchronized fun available(): Boolean = !closed && key != null

    /** Consumes both caller buffers, including when validation or session admission fails. */
    @Synchronized fun load(secretKey: ByteArray, password: CharArray): Boolean {
        try {
            if (closed || secretKey.isEmpty() || secretKey.size > BoundedInputReader.MAX_DOCUMENT_BYTES ||
                password.size > InputPolicy.MAX_TEXT_CHARS) return false
            clear()
            key = secretKey.copyOf(); passphrase = password.copyOf()
            return true
        } finally { secretKey.fill(0); password.fill('\u0000') }
    }
    /** Workers must own a snapshot, never borrow the live credential buffers. */
    @Synchronized fun snapshot(expectedRevision: Long): Snapshot? {
        if (closed || revision != expectedRevision) return null
        return Snapshot(key?.copyOf() ?: return null, passphrase?.copyOf() ?: return null)
    }
    @Synchronized fun clear() {
        revision++
        key?.fill(0); passphrase?.fill('\u0000'); key = null; passphrase = null
    }
    @Synchronized override fun close() { closed = true; clear() }
    override fun toString() = "SessionOpenPgpSigningCredentials(redacted)"
}
