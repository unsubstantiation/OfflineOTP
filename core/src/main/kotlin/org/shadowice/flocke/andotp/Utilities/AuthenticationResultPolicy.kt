package org.shadowice.flocke.andotp.Utilities

/** Accept an unlock result only for the outstanding challenge and its still-current session. */
object AuthenticationResultPolicy {
    @JvmStatic
    fun accepts(resultOk: Boolean, requestPending: Boolean, sessionGenerationCurrent: Boolean): Boolean {
        return resultOk && requestPending && sessionGenerationCurrent
    }
}
