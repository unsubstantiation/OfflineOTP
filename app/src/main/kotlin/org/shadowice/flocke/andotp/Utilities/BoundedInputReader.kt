package org.shadowice.flocke.andotp.Utilities

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Bounded reads of untrusted documents; does not rely on provider-reported sizes. */
object BoundedInputReader {
    // Applies to encrypted backup and QR image bytes. Decoded-image limits are separate.
    const val MAX_DOCUMENT_BYTES = 16 * 1024 * 1024

    /** Caller owns the stream. Reads at most `limit + 1` bytes to detect oversized input. */
    @JvmStatic
    @Throws(IOException::class)
    fun read(input: InputStream?, limit: Int): ByteArray {
        if (input == null) {
            throw IOException("Document provider returned no stream")
        }
        if (limit < 0 || limit == Int.MAX_VALUE) {
            throw IllegalArgumentException("Invalid document size limit")
        }
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            var count = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
            if (count == -1) {
                return output.toByteArray()
            }
            // Some providers return zero without reaching EOF. Guarantee progress.
            if (count == 0) {
                val next = input.read()
                if (next == -1) {
                    return output.toByteArray()
                }
                buffer[0] = next.toByte()
                count = 1
            }
            if (count > limit - total) {
                throw IOException("Document exceeds size limit")
            }
            output.write(buffer, 0, count)
            total += count
        }
    }
}
