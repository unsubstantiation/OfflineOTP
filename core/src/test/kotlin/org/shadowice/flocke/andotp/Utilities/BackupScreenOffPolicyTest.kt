package org.shadowice.flocke.andotp.Utilities

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the screen-off contract of the backup screen (andOTP #736): a pending picker, password
 * prompt/OpenPGP round trip, or deferred restart keeps the screen alive; a fully idle screen is
 * free to close. A running export also keeps it alive by clearing `allowExit`.
 */
class BackupScreenOffPolicyTest {
    @Test
    fun anIdleScreenMayFinish() {
        assertTrue(BackupScreenOffPolicy.shouldFinish(
            allowExit = true, pickerPending = false, actionPending = false, resumePending = false))
    }

    @Test
    fun aPendingPickerKeepsTheScreenAlive() {
        assertFalse(BackupScreenOffPolicy.shouldFinish(
            allowExit = true, pickerPending = true, actionPending = false, resumePending = false))
    }

    @Test
    fun aPendingPasswordPromptOrOpenPgpRoundTripKeepsTheScreenAlive() {
        assertFalse(BackupScreenOffPolicy.shouldFinish(
            allowExit = true, pickerPending = false, actionPending = true, resumePending = false))
    }

    @Test
    fun aDeferredRestartKeepsTheScreenAlive() {
        assertFalse(BackupScreenOffPolicy.shouldFinish(
            allowExit = true, pickerPending = false, actionPending = false, resumePending = true))
    }

    @Test
    fun aRunningTaskKeepsTheScreenAliveEvenWhenNothingElseIsPending() {
        assertFalse(BackupScreenOffPolicy.shouldFinish(
            allowExit = false, pickerPending = false, actionPending = false, resumePending = false))
    }

    @Test
    fun everyPendingOperationBlocksTeardown() {
        for (picker in booleanArrayOf(false, true)) {
            for (action in booleanArrayOf(false, true)) {
                for (resume in booleanArrayOf(false, true)) {
                    val expected = !picker && !action && !resume
                    assertTrue("idle screen must finish",
                        BackupScreenOffPolicy.shouldFinish(true, picker, action, resume) == expected)
                    assertFalse("a busy screen must never finish",
                        BackupScreenOffPolicy.shouldFinish(false, picker, action, resume))
                }
            }
        }
    }
}
