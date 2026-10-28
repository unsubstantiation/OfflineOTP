/*
 * Copyright (c) 2026 unsubstantiation (fairuniversal.com)
 *
 * Headless vectors for the rejection branches of the zxing QR decoder. Actual QR decoding needs
 * the real zxing-core library (compile-verified here through stubs), but every guard that runs
 * before the reader is fully exercisable: empty input, oversized documents and unreadable image
 * bytes all return null rather than throwing.
 */
package org.shadowice.flocke.andotp.desktop.qr

import org.junit.Assert.assertNull
import org.junit.Test
import org.shadowice.flocke.andotp.Utilities.BoundedInputReader

class ZxingQrDecoderTest {

    private val decoder = ZxingQrDecoder()

    @Test
    fun emptyAndOversizedInputIsRejectedBeforeAnyDecode() {
        assertNull(decoder.decode(ByteArray(0)))
        assertNull(decoder.decode(ByteArray(BoundedInputReader.MAX_DOCUMENT_BYTES + 1)))
    }

    @Test
    fun nonImageBytesDecodeToNullInsteadOfThrowing() {
        // ImageIO cannot parse arbitrary bytes, and the adapter must surface that as a null result.
        assertNull(decoder.decode("this is definitely not an image".toByteArray()))
    }
}
