package com.fairuniversal.offlineotp.ui.portable

/** Secret-free validation contract for session backup configuration; JVM/Swift share vectors. */
object PortableAutomaticBackupPolicy {
    const val CHANGE_DELAY_MILLIS = 1000L
    const val DEFAULT_MINUTES = 15
    const val DEFAULT_RETENTION = 10
    fun valid(minutes: Int, retention: Int, passwordUtf16Units: Int, passwordsMatch: Boolean): Boolean =
        minutes in 1..1440 && retention in 1..100 &&
            passwordUtf16Units in 1..PortableInputBounds.MAX_TEXT_CHARS && passwordsMatch
}
