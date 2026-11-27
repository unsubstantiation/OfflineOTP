package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.VaultJson
import com.fairuniversal.offlineotp.Utilities.SessionOpenPgpSigningCredentials
import com.fairuniversal.offlineotp.Utilities.EncryptedBackupCodec
import com.fairuniversal.offlineotp.ui.portable.PortableAutomaticBackupPolicy
import com.fairuniversal.offlineotp.Utilities.ScreenTimerController
import com.fairuniversal.offlineotp.desktop.storage.ExclusiveExportFile
import com.fairuniversal.offlineotp.desktop.storage.PrivateFiles
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID
import java.util.concurrent.Executor

/** Foreground unlocked-session backups. Credentials are never put in portable preferences. */
class DesktopAutomaticBackup(
    private val state: DesktopVaultState,
    private val scheduler: ScreenTimerController.Scheduler,
    private val worker: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val changed: () -> Unit,
    private val snapshot: () -> String = { VaultJson.entriesToString(state.snapshot()) },
    private val seal: (String, String) -> ByteArray = EncryptedBackupCodec::seal,
    private val sealRecipients: (String, List<ByteArray>) -> ByteArray = { document, keys ->
        com.fairuniversal.offlineotp.Utilities.OpenPgpBackupCodec.seal(
            com.fairuniversal.offlineotp.desktop.openpgp.RpgpOpenPgpPort(), document, keys)
    },
    private val sealSignedRecipients: (String, List<ByteArray>, SessionOpenPgpSigningCredentials.Snapshot) -> ByteArray = { document, keys, signing ->
        com.fairuniversal.offlineotp.Utilities.OpenPgpBackupCodec.sealSigned(
            com.fairuniversal.offlineotp.desktop.openpgp.RpgpOpenPgpPort(), document, keys, signing)
    },
    private val rawSnapshot: ((javax.crypto.SecretKey) -> ByteArray)? = null
) {
    private class Input(val document: String, val password: CharArray, val recipients: List<ByteArray>, val signing: SessionOpenPgpSigningCredentials.Snapshot?, val key: ByteArray? = null) : AutoCloseable {
        val extension = if (key != null) "offlineotp" else if (recipients.isEmpty()) "aes" else "pgp"
        override fun close() { key?.fill(0); password.fill('\u0000'); recipients.forEach { it.fill(0) }; signing?.close() }
        override fun toString() = "AutomaticBackupInput(redacted)"
    }
    private class Work(private var input: Input?) {
        @Volatile var cancelled = false
        @Synchronized fun take(): Input? = if (cancelled) null else input.also { input = null }
        @Synchronized fun cancel() { cancelled = true; input?.close(); input = null }
        @Synchronized fun claimWrite() = !cancelled
    }
    private var directory: File? = null
    private var password: CharArray? = null
    private var recipients: List<ByteArray> = emptyList()
    private var signing: SessionOpenPgpSigningCredentials.Snapshot? = null
    var vaultCopy = false; private set
    val signed: Boolean get() = signing != null
    val openPgp: Boolean get() = recipients.isNotEmpty()
    private var interval = 15L * 60_000
    private var keep = 10
    private var work: Work? = null
    private var closed = false
    var enabled = false; private set
    var busy = false; private set
    var failed = false; private set
    fun dismissNotice() { failed = false; changed() }
    var message = "Disabled. Enable for this unlocked session."; private set
    private var timer: Runnable? = null
    private var pendingChange = false
    private fun cancelTimer() { val prior = timer; timer = null; prior?.let(scheduler::remove) }
    private fun scheduleNext(delay: Long) {
        cancelTimer()
        lateinit var scheduled: Runnable
        scheduled = Runnable { if (timer === scheduled) { timer = null; runNow() } }
        timer = scheduled; scheduler.postDelayed(scheduled, delay)
    }
    /** Called after a successful persisted vault change, never for presentation-only refreshes. */
    fun vaultChanged() {
        if (closed || !enabled || state.isLocked || pendingChange) return
        pendingChange = true
        if (!busy) {
            scheduleNext(PortableAutomaticBackupPolicy.CHANGE_DELAY_MILLIS)
            message = "Vault saved. Automatic backup scheduled shortly."; changed()
        }
    }

    fun configure(folder: File, password: CharArray, confirmation: CharArray, minutes: Int, retention: Int,
                  beforeEnable: () -> Unit = {}): Boolean {
        try {
            if (closed || state.isLocked || busy || !PortableAutomaticBackupPolicy.valid(
                minutes, retention, password.size, password.contentEquals(confirmation))) return false
            val canonical = folder.toPath().toRealPath().toFile()
            require(canonical.isDirectory)
            val managed = File(canonical, "OfflineOTP-automatic")
            require(!Files.isSymbolicLink(managed.toPath()))
            PrivateFiles.ensureDirectory(managed)
            beforeEnable()
            if (closed || state.isLocked) return false
            disable()
            this.directory = managed; this.password = password.copyOf(); interval = minutes * 60_000L; keep = retention
            enabled = true; message = "Enabled for this unlocked session. Running first encrypted backup…"
            runNow()
            return true
        } catch (_: Exception) {
            message = "Cannot configure automatic backups. Choose an existing writable private folder."; changed(); return false
        } finally { password.fill('\u0000'); confirmation.fill('\u0000') }
    }

    fun configureSnapshots(folder: File, minutes: Int, retention: Int, beforeEnable: () -> Unit = {}): Boolean {
        if (closed || state.isLocked || busy || rawSnapshot == null || minutes !in 1..1440 || retention !in 1..100) return false
        try {
            val canonical = folder.toPath().toRealPath().toFile()
            require(canonical.isDirectory)
            val managed = File(canonical, "OfflineOTP-automatic")
            require(!Files.isSymbolicLink(managed.toPath()))
            PrivateFiles.ensureDirectory(managed)
            // Fail visibly before remembering a configuration for a legacy/nonportable vault.
            rawSnapshot.invoke(state.key).fill(0)
            beforeEnable()
            if (closed || state.isLocked) return false
            disable(); directory = managed; password = CharArray(0); vaultCopy = true
            interval = minutes * 60_000L; keep = retention; enabled = true
            runNow(); return true
        } catch (_: Exception) {
            message = "Cannot enable full vault copies. Migrate the legacy vault in Backups first and choose a writable private folder."
            changed(); return false
        }
    }

    fun configureRecipients(folder: File, keys: List<ByteArray>, minutes: Int, retention: Int,
                            signing: SessionOpenPgpSigningCredentials.Snapshot? = null, beforeEnable: () -> Unit = {}): Boolean {
        if (closed || state.isLocked || busy || minutes !in 1..1440 || retention !in 1..100 ||
            !com.fairuniversal.offlineotp.Utilities.AutomaticBackupCredentialCodec.validRecipients(keys)) return false
        val owned = keys.map { it.copyOf() }
        val ownedSigning = signing?.copy()
        try {
            val canonical = folder.toPath().toRealPath().toFile()
            require(canonical.isDirectory)
            val managed = File(canonical, "OfflineOTP-automatic")
            require(!Files.isSymbolicLink(managed.toPath()))
            PrivateFiles.ensureDirectory(managed)
            beforeEnable()
            if (closed || state.isLocked) return false
            disable()
            directory = managed; recipients = owned.map { it.copyOf() }; password = CharArray(0)
            this.signing = ownedSigning?.copy()
            interval = minutes * 60_000L; keep = retention; enabled = true
            runNow(); return true
        } catch (_: Exception) {
            message = "Cannot configure automatic OpenPGP backups. Check the folder and public certificates."; changed(); return false
        } finally { owned.forEach { it.fill(0) }; ownedSigning?.close() }
    }

    fun disable() {
        cancelTimer(); pendingChange = false; work?.cancel(); work = null
        password?.fill('\u0000'); password = null; signing?.close(); signing = null; recipients.forEach { it.fill(0) }; recipients = emptyList(); directory = null
        vaultCopy = false; enabled = false; busy = false; failed = false; message = "Disabled. Existing backups were kept."; changed()
    }
    fun close() { closed = true; disable() }

    fun runNow() {
        if (closed || !enabled || busy) return
        if (state.isLocked) { disable(); return }
        cancelTimer(); pendingChange = false
        val folder = directory ?: return
        val secret = password?.copyOf() ?: return
        val key = if (vaultCopy) try { requireNotNull(state.key.encoded).copyOf() } catch (_: Exception) { secret.fill('\u0000'); fail(); return } else null
        val document = try { if (vaultCopy) "" else snapshot() } catch (_: Exception) { key?.fill(0); secret.fill('\u0000'); fail(); return }
        val retain = keep
        val task = Work(Input(document, secret, recipients.map { it.copyOf() }, signing?.copy(), key)); work = task; busy = true; message = "Writing automatic backup…"; changed()
        try {
            worker.execute {
                val input = task.take() ?: return@execute
                var bytes: ByteArray? = null
                var success = false
                try {
                    if (task.cancelled || state.isLocked) return@execute
                    bytes = if (input.key != null) {
                        val key = javax.crypto.spec.SecretKeySpec(input.key, "AES")
                        try { requireNotNull(rawSnapshot).invoke(key) } finally { runCatching { key.destroy() } }
                    } else if (input.recipients.isEmpty()) seal(input.password.concatToString(), input.document)
                        else if (input.signing != null) sealSignedRecipients(input.document, input.recipients, input.signing)
                        else sealRecipients(input.document, input.recipients)
                    if (state.isLocked || !task.claimWrite()) return@execute
                    require(!Files.isSymbolicLink(folder.toPath()) && folder.isDirectory)
                    val target = File(folder, "auto-${System.currentTimeMillis().toString().padStart(16, '0')}-${UUID.randomUUID()}.${input.extension}")
                    ExclusiveExportFile.writeNew(target, bytes)
                    if (!task.cancelled && !state.isLocked) prune(folder, retain, input.extension)
                    success = true
                } catch (_: Exception) { /* No filenames, plaintext or crypto diagnostics in UI. */ }
                finally { bytes?.fill(0); input.close() }
                try { dispatch(Runnable {
                    if (closed || work !== task || task.cancelled) return@Runnable
                    work = null; busy = false
                    if (state.isLocked) { disable(); return@Runnable }
                    if (!success) { fail(); return@Runnable }
                    message = if (pendingChange) "Automatic backup saved. New changes pending; another backup is scheduled shortly."
                        else "Automatic backup saved. Next periodic backup in ${interval / 60_000} minutes."
                    scheduleNext(if (pendingChange) PortableAutomaticBackupPolicy.CHANGE_DELAY_MILLIS else interval); changed()
                }) } catch (_: Exception) { task.cancel() }
            }
        } catch (_: Exception) { task.cancel(); fail() }
    }
    fun reportSigningRequired() {
        disable(); failed = true
        message = "Signing credentials are session-only. Reload the signing key and passphrase to resume. No unsigned backup was written."; changed()
    }
    fun reportRestoreFailure() {
        disable(); failed = true
        message = "Saved automatic-backup settings could not be opened or resumed. After a vault password/key change, re-enter the backup password and destination, or forget the saved configuration."; changed()
    }
    private fun fail() {
        disable(); failed = true; message = "Automatic backup failed and was disabled. Existing backups were kept; check the destination before re-enabling."; changed()
    }
    companion object {
        internal fun prune(folder: File, keep: Int, extension: String = "aes") {
            require(keep in 1..100 && extension in listOf("aes", "pgp", "offlineotp") && !Files.isSymbolicLink(folder.toPath()))
            val owned = folder.listFiles()?.filter {
                Regex("auto-[0-9]{16}-[0-9a-f-]{36}\\.$extension").matches(it.name) && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS)
            }?.sortedByDescending { it.name } ?: error("Cannot list backup directory")
            owned.drop(keep).forEach { Files.delete(it.toPath()) }
        }
    }
}
