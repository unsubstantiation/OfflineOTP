package org.shadowice.flocke.andotp.Utilities

/**
 * Screen-off teardown policy for the backup screen (andOTP #736).
 *
 * Turning the screen off used to close the backup screen while a document picker, a password
 * prompt, an OpenPGP round trip or a running export still owned the selected document, which lost
 * the operation. The screen now stays alive whenever an operation is pending, so the ordinary
 * relock path can hand that operation back to the main screen and resume it after re-auth; it is
 * only closed when it is completely idle and free to leave.
 */
object BackupScreenOffPolicy {
    @JvmStatic
    fun shouldFinish(
        allowExit: Boolean,
        pickerPending: Boolean,
        actionPending: Boolean,
        resumePending: Boolean
    ): Boolean = allowExit && !pickerPending && !actionPending && !resumePending
}
