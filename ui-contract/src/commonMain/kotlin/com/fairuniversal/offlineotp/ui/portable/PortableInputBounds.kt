package com.fairuniversal.offlineotp.ui.portable

/**
 * Toolkit-free input bounds and shared warning copy across JVM, Android and Kotlin/Native Compose forms.
 *
 * Pinned to `InputPolicy`, `BrowserPasswordCsv`, and `EmailMaterial` in `PortablePresentationTest`
 * so `:ui-foundation` forms enforce the exact same length caps without importing JVM `:core`.
 */
object PortableInputBounds {
    const val MAX_TEXT_CHARS: Int = 1024
    const val MAX_URI_CHARS: Int = 16384
    const val MAX_TAG_CHARS: Int = 128
    const val MAX_TAGS: Int = 32
    const val MAX_NOTE_CHARACTERS: Int = 200
    const val MAX_PASSWORD_FIELD_CHARS: Int = 64 * 1024
    const val MAX_EMAIL_CREDENTIAL_CHARS: Int = 16 * 1024

    const val EMAIL_EDIT_WARNING: String =
        "Warning: You should only make edits if you know what you are doing and that it will work. " +
            "No changes you make here will be reflected on your server, If you make a mistake in edits " +
            "you may disable your ability to connect, there is no edit history, edits are not recoverable"
}
