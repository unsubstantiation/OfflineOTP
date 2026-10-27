package org.shadowice.flocke.andotp.Utilities

import java.io.ByteArrayOutputStream

/** Bounds plaintext/ciphertext returned by a selected external OpenPGP provider. */
class BoundedOutput(limit: Int) : ByteArrayOutputStream() {
    private val limit: Int = if (limit < 0) throw IllegalArgumentException("Invalid output limit") else limit
    private var overflowed = false

    private fun check(length: Int) {
        if (length < 0 || length > this.limit - size()) {
            overflowed = true
            throw IllegalStateException("Provider output exceeds limit")
        }
    }

    @Synchronized
    override fun write(value: Int) {
        check(1)
        super.write(value)
    }

    @Synchronized
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        check(length)
        super.write(bytes, offset, length)
    }

    @Synchronized
    fun overflowed(): Boolean = overflowed
}
