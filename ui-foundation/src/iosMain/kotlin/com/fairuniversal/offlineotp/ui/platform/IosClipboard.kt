@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.fairuniversal.offlineotp.ui.platform

import com.fairuniversal.offlineotp.ui.portable.PortableClipboardPolicy
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeIntervalSince1970
import platform.UIKit.UIPasteboard
import platform.UIKit.UIPasteboardOptionExpirationDate
import platform.UIKit.UIPasteboardOptionLocalOnly

/** Kotlin/Native pasteboard adapter. The OS enforces expiry even after the app is suspended. */
object IosClipboard {
    val expirySeconds: Double get() = PortableClipboardPolicy.expirySeconds

    fun copy(code: String): Boolean {
        val now = NSDate().timeIntervalSince1970
        return copyAt(code, now, now + expirySeconds)
    }

    fun copyUntil(code: String, expiresAtEpochSeconds: Double): Boolean =
        copyAt(code, NSDate().timeIntervalSince1970, expiresAtEpochSeconds)

    private fun copyAt(code: String, now: Double, requestedExpiry: Double): Boolean {
        val expiry = PortableClipboardPolicy.expiry(now, requestedExpiry) ?: return false
        UIPasteboard.generalPasteboard.setItems(
            listOf(mapOf("public.utf8-plain-text" to code)),
            options = mapOf(
                UIPasteboardOptionExpirationDate to NSDate.dateWithTimeIntervalSince1970(expiry),
                UIPasteboardOptionLocalOnly to true
            )
        )
        return true
    }
}
