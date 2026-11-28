package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.ScreenTimerController
import java.nio.file.Files
import java.io.File
import java.util.concurrent.Executor
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopAutomaticBackupTest {
    private class Queue : Executor {
        val tasks = ArrayList<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun run() { tasks.removeAt(0).run() }
    }
    private class Timer : ScreenTimerController.Scheduler {
        var pending: Runnable? = null
        var delay = 0L
        override fun postDelayed(runnable: Runnable, delayMillis: Long) { pending = runnable; delay = delayMillis }
        override fun remove(runnable: Runnable) { if (pending === runnable) pending = null }
        override fun elapsedRealtime() = 0L
    }
    @Test fun fullVaultCopiesNeverSerializeOrReencryptAndKeepTheirOwnRetentionFamily() {
        val folder = Files.createTempDirectory("auto-copy-").toFile()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32) { 7 }, "AES"))
        val worker = Queue(); val ui = Queue(); val timer = Timer()
        var reads = 0
        val saved = byteArrayOf(4, 3, 2, 1)
        val backup = DesktopAutomaticBackup(state, timer, worker, ui::execute, {},
            snapshot = { error("must not serialize") }, seal = { _, _ -> error("must not encrypt") },
            rawSnapshot = { key -> reads++; assertTrue(key.encoded.all { it == 7.toByte() }); saved.copyOf() })
        try {
            assertTrue(backup.configureSnapshots(folder, 1, 1)); assertTrue(backup.vaultCopy)
            assertEquals(1, reads) // portability preflight, no entry serialization
            val managed = File(folder, "OfflineOTP-automatic")
            val oldExport = File(managed, "auto-0000000000000000-00000000-0000-0000-0000-000000000000.aes").apply { writeText("keep") }
            worker.run(); ui.run()
            assertEquals(2, reads); assertFalse(backup.busy); assertTrue(backup.enabled)
            assertTrue(managed.listFiles()!!.single { it.extension == "offlineotp" }.readBytes().contentEquals(saved))
            assertEquals(60_000L, timer.delay)
            backup.vaultChanged(); assertEquals(1_000L, timer.delay)
            timer.pending!!.run(); worker.run(); ui.run()
            assertEquals(1, managed.listFiles()!!.count { it.extension == "offlineotp" })
            assertEquals("keep", oldExport.readText())
            backup.disable(); assertFalse(backup.vaultCopy); assertTrue(timer.pending == null)
        } finally { backup.close(); state.lock(); folder.deleteRecursively() }
    }

    @Test fun fullCopyInputsAreRevokedBeforeWorkersRunAndLegacyPreflightIsNotRemembered() {
        val folder = Files.createTempDirectory("auto-copy-cancel-").toFile()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val worker = Queue(); val timer = Timer()
        var reads = 0; var remembered = false; var legacy = true
        val backup = DesktopAutomaticBackup(state, timer, worker, { it.run() }, {}, rawSnapshot = {
            reads++; if (legacy) error("legacy") else byteArrayOf(1)
        })
        try {
            assertFalse(backup.configureSnapshots(folder, 1, 2) { remembered = true })
            assertFalse(remembered); assertFalse(backup.enabled)
            legacy = false
            assertTrue(backup.configureSnapshots(folder, 1, 2) { remembered = true })
            assertTrue(remembered); assertEquals(2, reads)
            val task = backup.javaClass.getDeclaredField("work").apply { isAccessible = true }.get(backup)
            val input = task.javaClass.getDeclaredField("input").apply { isAccessible = true }.get(task)
            val key = input.javaClass.getDeclaredField("key").apply { isAccessible = true }.get(input) as ByteArray
            backup.disable(); assertTrue(key.all { it == 0.toByte() }); worker.run()
            assertEquals(2, reads); assertEquals(0, File(folder, "OfflineOTP-automatic").listFiles()!!.size)
        } finally { backup.close(); state.lock(); folder.deleteRecursively() }
    }

    @Test fun sessionCredentialsAreConsumedAndSuccessRequiresDeliveryBeforeRescheduling() {
        val folder = Files.createTempDirectory("auto-backup-").toFile()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        val worker = Queue(); val ui = Queue(); val timer = Timer()
        var snapshots = 0
        val backup = DesktopAutomaticBackup(state, timer, worker, ui::execute, {},
            snapshot = { snapshots++; "detached-document" }, seal = { password, document ->
                assertEquals("separate", password); assertEquals("detached-document", document); byteArrayOf(9, 8)
            })
        try {
            val password = "separate".toCharArray(); val confirmation = password.copyOf()
            assertTrue(backup.configure(folder, password, confirmation, 1, 2))
            assertTrue(password.all { it == '\u0000' }); assertTrue(confirmation.all { it == '\u0000' })
            assertTrue(backup.busy); assertEquals(1, snapshots)
            backup.runNow(); assertEquals(1, snapshots)
            worker.run(); assertTrue(backup.busy); assertTrue(timer.pending == null)
            ui.run(); assertFalse(backup.busy); assertTrue(backup.enabled); assertTrue(timer.pending != null)
            val managed = File(folder, "OfflineOTP-automatic")
            assertEquals(1, managed.listFiles()!!.size)
            assertTrue(managed.listFiles()!!.single().readBytes().contentEquals(byteArrayOf(9, 8)))
            backup.disable(); assertFalse(backup.enabled); assertTrue(timer.pending == null)
            assertEquals(1, managed.listFiles()!!.size)
        } finally { backup.close(); state.lock(); folder.deleteRecursively() }
    }
    @Test fun cancelBeforeWorkerSuppressesWriteAndInvalidBoundsConsumePasswords() {
        val folder = Files.createTempDirectory("auto-cancel-").toFile()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        val worker = Queue(); val timer = Timer()
        val backup = DesktopAutomaticBackup(state, timer, worker, { it.run() }, {}, snapshot = { "document" }, seal = { _, _ -> error("cancelled seal ran") })
        try {
            val invalid = "secret".toCharArray(); val confirm = invalid.copyOf()
            assertFalse(backup.configure(folder, invalid, confirm, 0, 2))
            assertTrue(invalid.all { it == '\u0000' }); assertTrue(confirm.all { it == '\u0000' })
            assertTrue(backup.configure(folder, "secret".toCharArray(), "secret".toCharArray(), 1, 2))
            backup.disable(); worker.run()
            assertEquals(0, File(folder, "OfflineOTP-automatic").listFiles()!!.size)
        } finally { backup.close(); state.lock(); folder.deleteRecursively() }
    }
    @Test fun retentionIgnoresUnrelatedFilesAndDoesNotFollowSymlinks() {
        val folder = Files.createTempDirectory("auto-retain-").toFile()
        try {
            for (index in 1..4) File(folder, "auto-${index.toString().padStart(16, '0')}-00000000-0000-0000-0000-000000000000.aes").writeText("ciphertext")
            val unrelated = File(folder, "manual.aes").apply { writeText("keep") }
            val link = File(folder, "auto-0000000000000000-00000000-0000-0000-0000-000000000000.aes")
            Files.createSymbolicLink(link.toPath(), unrelated.toPath())
            DesktopAutomaticBackup.prune(folder, 2)
            assertTrue(Files.isSymbolicLink(link.toPath()))
            assertTrue(unrelated.exists()); assertEquals(4, folder.listFiles()!!.size)
            assertTrue(folder.listFiles()!!.any { it.name.contains("0000000000000004") })
        } finally { folder.deleteRecursively() }
    }
    @Test fun cancellationAfterEncryptionSuppressesWriteAndLateCompletionCannotSchedule() {
        for (cancelDuringEncryption in listOf(false, true)) {
            val folder = Files.createTempDirectory("auto-stale-").toFile()
            val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
            val worker = Queue(); val ui = Queue(); val timer = Timer()
            lateinit var backup: DesktopAutomaticBackup
            backup = DesktopAutomaticBackup(state, timer, worker, ui::execute, {}, snapshot = { "document" }, seal = { _, _ ->
                if (cancelDuringEncryption) backup.disable()
                byteArrayOf(1)
            })
            try {
                backup.configure(folder, "secret".toCharArray(), "secret".toCharArray(), 1, 2)
                worker.run()
                if (!cancelDuringEncryption) { backup.disable(); ui.run() }
                assertFalse(backup.enabled); assertFalse(backup.busy); assertTrue(timer.pending == null)
                assertEquals(if (cancelDuringEncryption) 0 else 1, File(folder, "OfflineOTP-automatic").listFiles()!!.size)
            } finally { backup.close(); state.lock(); folder.deleteRecursively() }
        }
    }

    @Test fun failedEncryptionDisablesInsteadOfRetryingOrPruning() {
        val folder = Files.createTempDirectory("auto-fail-").toFile()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        val worker = Queue(); val ui = Queue(); val timer = Timer()
        val backup = DesktopAutomaticBackup(state, timer, worker, ui::execute, {}, snapshot = { "document" }, seal = { _, _ -> error("failure") })
        try {
            assertTrue(backup.configure(folder, "secret".toCharArray(), "secret".toCharArray(), 1, 2))
            worker.run(); ui.run()
            assertFalse(backup.enabled); assertFalse(backup.busy); assertTrue(timer.pending == null)
            assertTrue(backup.message.contains("failed")); assertTrue(backup.failed)
            backup.dismissNotice(); assertFalse(backup.failed)
            assertTrue(backup.message.contains("failed"))
        } finally { backup.close(); state.lock(); folder.deleteRecursively() }
    }
    @Test fun successfulChangesCoalesceAndCatchUpAfterInFlightBackupWithoutOverlapping() {
        val folder = Files.createTempDirectory("auto-change-").toFile()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        val worker = Queue(); val ui = Queue(); val timer = Timer()
        var document = "first"; val sealed = arrayListOf<String>()
        val backup = DesktopAutomaticBackup(state, timer, worker, ui::execute, {}, snapshot = { document },
            seal = { _, text -> sealed.add(text); byteArrayOf(1) })
        try {
            assertTrue(backup.configure(folder, "secret".toCharArray(), "secret".toCharArray(), 15, 3))
            document = "latest"; repeat(5) { backup.vaultChanged() }
            assertEquals(1, worker.tasks.size)
            worker.run(); ui.run()
            assertEquals(listOf("first"), sealed)
            assertEquals(1000L, timer.delay)
            val firstChange = timer.pending!!
            repeat(5) { backup.vaultChanged() }
            assertTrue(firstChange === timer.pending) // A busy vault cannot postpone backup indefinitely.
            firstChange.run(); assertEquals(1, worker.tasks.size)
            worker.run(); ui.run()
            assertEquals(listOf("first", "latest"), sealed)
            assertEquals(15 * 60_000L, timer.delay)
            firstChange.run(); assertTrue(worker.tasks.isEmpty()) // Retired callback is inert.
            backup.vaultChanged(); assertEquals(1000L, timer.delay)
            val retired = timer.pending!!
            backup.disable(); retired.run(); backup.vaultChanged()
            assertTrue(worker.tasks.isEmpty()); assertFalse(backup.enabled)
        } finally { backup.close(); state.lock(); folder.deleteRecursively() }
    }
    @Test fun disablingRevokesQueuedPlaintextAndWipesItsPasswordBeforeWorkerRuns() {
        val folder = Files.createTempDirectory("auto-revoke-").toFile()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        val worker = Queue(); val timer = Timer()
        val backup = DesktopAutomaticBackup(state, timer, worker, { it.run() }, {}, snapshot = { "queued synthetic plaintext" },
            seal = { _, _ -> error("revoked input reached encryption") })
        try {
            assertTrue(backup.configure(folder, "secret".toCharArray(), "secret".toCharArray(), 15, 3))
            val workField = backup.javaClass.getDeclaredField("work").apply { isAccessible = true }
            val task = workField.get(backup)
            val inputField = task.javaClass.getDeclaredField("input").apply { isAccessible = true }
            val input = inputField.get(task)
            val passwordField = input.javaClass.getDeclaredField("password").apply { isAccessible = true }
            val ownedPassword = passwordField.get(input) as CharArray
            backup.disable()
            assertTrue(ownedPassword.all { it == '\u0000' }); assertEquals(null, inputField.get(task))
            worker.run(); assertFalse(backup.busy)
            assertTrue(File(folder, "OfflineOTP-automatic").listFiles()!!.isEmpty())
        } finally { backup.close(); state.lock(); folder.deleteRecursively() }
    }
    @Test fun staleTimerCannotRunAfterReconfigurationAndLockedChangesDoNotSchedule() {
        val folder = Files.createTempDirectory("auto-epoch-").toFile()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        val worker = Queue(); val ui = Queue(); val timer = Timer()
        val backup = DesktopAutomaticBackup(state, timer, worker, ui::execute, {}, snapshot = { "document" }, seal = { _, _ -> byteArrayOf(1) })
        try {
            backup.configure(folder, "secret".toCharArray(), "secret".toCharArray(), 15, 3)
            worker.run(); ui.run(); val stale = timer.pending!!
            backup.disable()
            backup.configure(folder, "new password".toCharArray(), "new password".toCharArray(), 15, 3)
            worker.run(); ui.run()
            stale.run(); assertTrue(worker.tasks.isEmpty())
            state.lock(); backup.vaultChanged(); assertEquals(15 * 60_000L, timer.delay)
        } finally { backup.close(); state.lock(); folder.deleteRecursively() }
    }

    @Test fun configureCannotReenableAfterPersistenceHookLocksOrClosesOwner() {
        for (close in listOf(false, true)) {
            val folder = Files.createTempDirectory("auto-admission-").toFile()
            val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
            val worker = Queue(); val timer = Timer()
            val backup = DesktopAutomaticBackup(state, timer, worker, { it.run() }, {}, snapshot = { error("stale snapshot") })
            try {
                val password = "secret".toCharArray(); val confirmation = password.copyOf()
                assertFalse(backup.configure(folder, password, confirmation, 15, 3) {
                    if (close) backup.close() else state.lock()
                })
                assertTrue(password.all { it == '\u0000' }); assertTrue(confirmation.all { it == '\u0000' })
                assertFalse(backup.enabled); assertTrue(worker.tasks.isEmpty())
            } finally { backup.close(); state.lock(); folder.deleteRecursively() }
        }
    }

    @Test fun recipientModeOwnsSnapshotWritesPgpAndRevokesQueuedKeys() {
        val folder = Files.createTempDirectory("auto-pgp-").toFile()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val queue = Queue(); val timer = Timer()
        var calls = 0; var borrowed: List<ByteArray>? = null
        val backup = DesktopAutomaticBackup(state, timer, queue, { it.run() }, {}, snapshot = { "synthetic vault" },
            seal = { _, _ -> error("password encoder selected") }, sealRecipients = { document, keys ->
                assertEquals("synthetic vault", document); assertEquals(7, keys.single()[0].toInt())
                borrowed = keys; calls++; byteArrayOf(99, 100)
            })
        try {
            val keys = listOf(byteArrayOf(7, 8))
            assertTrue(backup.configureRecipients(folder, keys, 15, 2)); keys[0].fill(0)
            queue.run(); assertEquals(1, calls)
            assertTrue(borrowed!!.all { bytes -> bytes.all { it == 0.toByte() } })
            val managed = File(folder, "OfflineOTP-automatic")
            val file = managed.listFiles()!!.single()
            assertTrue(file.name.endsWith(".pgp")); assertTrue(file.readBytes().contentEquals(byteArrayOf(99, 100)))
            val passwordFile = File(managed, "auto-0000000000000000-00000000-0000-0000-0000-000000000000.aes").apply { writeBytes(byteArrayOf(1)) }
            DesktopAutomaticBackup.prune(managed, 1, "pgp"); assertTrue(passwordFile.exists())
            backup.runNow(); backup.disable(); queue.run(); assertEquals(1, calls)
            assertFalse(backup.openPgp); assertFalse(backup.enabled)
        } finally { backup.close(); state.lock(); folder.deleteRecursively() }
    }
    @Test fun invalidRecipientBoundsDuplicatesAndFailureNeverProduceBackup() {
        val folder = Files.createTempDirectory("auto-pgp-invalid-").toFile()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val queue = Queue()
        val backup = DesktopAutomaticBackup(state, Timer(), queue, { it.run() }, {}, snapshot = { "synthetic" },
            sealRecipients = { _, _ -> error("invalid certificate") })
        try {
            for (keys in listOf(emptyList(), listOf(byteArrayOf()), listOf(byteArrayOf(1), byteArrayOf(1)), listOf(ByteArray(32769)))) {
                assertFalse(backup.configureRecipients(folder, keys, 15, 2))
            }
            assertTrue(queue.tasks.isEmpty())
            assertTrue(backup.configureRecipients(folder, listOf(byteArrayOf(1)), 15, 2))
            queue.run(); assertTrue(backup.failed); assertFalse(backup.enabled)
            assertTrue(File(folder, "OfflineOTP-automatic").listFiles()!!.isEmpty())
        } finally { backup.close(); state.lock(); folder.deleteRecursively() }
    }

    @Test fun signedJobsOwnCredentialsWipeCopiesAndNeverRetryUnsignedOnFailure() {
        val folder = Files.createTempDirectory("auto-signed-").toFile()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES")); val queue = Queue()
        val holder = com.fairuniversal.offlineotp.Utilities.SessionOpenPgpSigningCredentials()
        var signedCalls = 0; var captured: com.fairuniversal.offlineotp.Utilities.SessionOpenPgpSigningCredentials.Snapshot? = null
        val backup = DesktopAutomaticBackup(state, Timer(), queue, { it.run() }, {}, snapshot = { "synthetic" },
            sealRecipients = { _, _ -> error("unsigned fallback") },
            sealSignedRecipients = { _, _, signing ->
                signedCalls++; captured = signing
                assertEquals("session-pass", signing.passphrase.concatToString())
                assertEquals(7, signing.key[0].toInt())
                error("wrong signing passphrase")
            })
        try {
            assertTrue(holder.load(byteArrayOf(7, 8), "session-pass".toCharArray()))
            holder.snapshot(holder.revision())!!.use { signing ->
                assertTrue(backup.configureRecipients(folder, listOf(byteArrayOf(1)), 15, 3, signing))
            }
            holder.close(); queue.run()
            assertEquals(1, signedCalls); assertTrue(backup.failed); assertFalse(backup.enabled); assertFalse(backup.signed)
            assertTrue(captured!!.key.all { it == 0.toByte() }); assertTrue(captured!!.passphrase.all { it == '\u0000' })
            assertTrue(File(folder, "OfflineOTP-automatic").listFiles()!!.isEmpty())
            backup.reportSigningRequired()
            assertTrue(backup.message.contains("No unsigned backup")); assertTrue(queue.tasks.isEmpty())
        } finally { holder.close(); backup.close(); state.lock(); folder.deleteRecursively() }
    }

    @Test fun keyReplacementRevokesQueuedAndInFlightAutomaticExports() {
        for (duringEncryption in listOf(false, true)) {
            val folder = Files.createTempDirectory("auto-rekey-").toFile()
            val state = DesktopVaultState(SecretKeySpec(ByteArray(32) { 1 }, "AES"))
            val worker = Queue(); val ui = Queue(); val timer = Timer()
            var seals = 0
            val backup = DesktopAutomaticBackup(state, timer, worker, ui::execute, {}, snapshot = { "sensitive" }, seal = { _, _ ->
                seals++
                if (duringEncryption) state.updateKey(SecretKeySpec(ByteArray(32) { 2 }, "AES"))
                byteArrayOf(1)
            })
            try {
                assertTrue(backup.configure(folder, "secret".toCharArray(), "secret".toCharArray(), 1, 2))
                if (!duringEncryption) state.updateKey(SecretKeySpec(ByteArray(32) { 2 }, "AES"))
                worker.run(); ui.run()
                assertEquals(if (duringEncryption) 1 else 0, seals)
                assertEquals(0, File(folder, "OfflineOTP-automatic").listFiles()!!.size)
                assertFalse(backup.enabled); assertFalse(backup.busy); assertTrue(timer.pending == null)
            } finally { backup.close(); state.lock(); folder.deleteRecursively() }
        }
    }
    @Test fun rekeyDuringConfigurationCannotEnableTheOldGeneration() {
        val folder = Files.createTempDirectory("auto-config-rekey-").toFile()
        val state = DesktopVaultState(SecretKeySpec(ByteArray(32), "AES"))
        val worker = Queue()
        val backup = DesktopAutomaticBackup(state, Timer(), worker, { it.run() }, {})
        try {
            assertFalse(backup.configure(folder, "secret".toCharArray(), "secret".toCharArray(), 1, 2) {
                state.updateKey(SecretKeySpec(ByteArray(32) { 3 }, "AES"))
            })
            assertFalse(backup.enabled); assertTrue(worker.tasks.isEmpty())
        } finally { backup.close(); state.lock(); folder.deleteRecursively() }
    }

}
