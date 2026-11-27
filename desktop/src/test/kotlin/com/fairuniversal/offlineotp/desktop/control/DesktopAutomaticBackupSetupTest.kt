package com.fairuniversal.offlineotp.desktop.control

import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopAutomaticBackupSetupTest {
    private class Queue : Executor {
        val tasks = arrayListOf<Runnable>()
        override fun execute(task: Runnable) { tasks.add(task) }
        fun run() { tasks.removeAt(0).run() }
    }
    @Test fun filesAreReadByWorkerAndBorrowedCredentialsAreWipedAfterApply() {
        val dir = Files.createTempDirectory("backup-setup-").toFile()
        val worker = Queue(); val ui = Queue()
        val setup = DesktopAutomaticBackupSetup(worker, { ui.execute(it) }, {})
        try {
            val publicKey = File(dir, "public").apply { writeBytes(byteArrayOf(1)) }
            val privateKey = File(dir, "private").apply { writeBytes(byteArrayOf(7, 8)) }
            val password = "session-pass".toCharArray(); var applied = 0
            var borrowed: DesktopAutomaticBackupSetup.Prepared? = null
            assertTrue(setup.submit(emptyList(), listOf(publicKey), privateKey, password, { true }) { prepared ->
                applied++; borrowed = prepared
                assertEquals("session-pass", prepared.signing!!.passphrase.concatToString())
                assertTrue(prepared.signing!!.key.contentEquals(byteArrayOf(7, 8)))
                true
            })
            assertTrue(password.all { it == '\u0000' }); assertTrue(setup.busy)
            assertEquals(0, applied); worker.run(); assertEquals(0, applied); ui.run()
            assertEquals(1, applied); assertFalse(setup.busy); assertFalse(setup.failed)
            assertTrue(borrowed!!.signing!!.key.all { it == 0.toByte() })
            assertTrue(borrowed!!.signing!!.passphrase.all { it == '\u0000' })
            assertTrue(borrowed!!.recipients.single().all { it == 0.toByte() })
            assertTrue(privateKey.readBytes().contentEquals(byteArrayOf(7, 8)))
        } finally { setup.close(); dir.deleteRecursively() }
    }
    @Test fun cancelBeforeWorkerAndAfterReadCannotPublishAndRetiredDeliveryCannotClaimReplacement() {
        val dir = Files.createTempDirectory("backup-setup-cancel-").toFile()
        val worker = Queue(); val ui = Queue(); var applied = 0
        val setup = DesktopAutomaticBackupSetup(worker, { ui.execute(it) }, {})
        try {
            val file = File(dir, "public").apply { writeBytes(byteArrayOf(1)) }
            assertTrue(setup.submit(emptyList(), listOf(file), null, charArrayOf(), { true }) { applied++; true })
            setup.cancel(); file.delete(); worker.run(); assertEquals(0, applied)
            file.writeBytes(byteArrayOf(2))
            assertTrue(setup.submit(emptyList(), listOf(file), null, charArrayOf(), { true }) { applied++; true })
            worker.run(); setup.cancel()
            assertTrue(setup.submit(emptyList(), listOf(file), null, charArrayOf(), { true }) { applied++; true })
            ui.run(); assertTrue(setup.busy); assertEquals(0, applied)
            worker.run(); ui.run(); assertEquals(1, applied); assertFalse(setup.busy)
        } finally { setup.close(); dir.deleteRecursively() }
    }
    @Test fun failureAndExpiredAdmissionAreRetryableAndNeverApply() {
        val worker = Queue(); val ui = Queue(); var active = true
        val setup = DesktopAutomaticBackupSetup(worker, { ui.execute(it) }, {})
        try {
            assertTrue(setup.submit(emptyList(), emptyList(), null, charArrayOf(), { active }) { error("invalid applied") })
            worker.run(); ui.run(); assertTrue(setup.failed); assertFalse(setup.busy)
            assertTrue(setup.submit(listOf(byteArrayOf(1)), emptyList(), null, charArrayOf(), { active }) { error("expired applied") })
            worker.run(); active = false; ui.run(); assertFalse(setup.busy)
            val password = "secret".toCharArray()
            assertFalse(setup.submit(emptyList(), emptyList(), null, password, { active }) { true })
            assertTrue(password.all { it == '\u0000' })
        } finally { setup.close() }
    }
}
