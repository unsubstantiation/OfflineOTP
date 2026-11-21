package com.fairuniversal.offlineotp.ui.portable

/**
 * Toolkit-free input bounds shared across JVM, Android and Kotlin/Native Compose forms.
 *
 * Pinned to `InputPolicy` in `PortablePresentationTest` so `:ui-foundation` forms enforce the
 * exact same length caps without importing JVM `:core`.
 */
object PortableInputBounds {
    const val MAX_TEXT_CHARS: Int = 1024
    const val MAX_URI_CHARS: Int = 16384
    const val MAX_TAG_CHARS: Int = 128
}
