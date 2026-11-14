package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Utilities.InputPolicy

/** UI-thread, session-bound URI import. Owns the coordinator's incomplete migration batch. */
class DesktopUriImport(private val state: DesktopVaultState, private val coordinator: BackupCoordinator) {
    class Request internal constructor() {
        var message: String? = null
            internal set
        internal var duplicatePolicy: Boolean? = null
        val collecting: Boolean get() = duplicatePolicy != null
    }
    var pending: Request? = null
        private set
    private var closed = false

    fun open() {
        if (closed || state.isLocked || pending != null) return
        pending = Request()
    }
    fun dismiss(request: Request) {
        if (pending !== request) return
        coordinator.cancelMigrationImport(); pending = null
    }
    fun close() { closed = true; coordinator.cancelMigrationImport(); pending = null }

    /** Returns true only when the vault changed; neither raw URI nor parser details are retained. */
    fun submit(request: Request, uri: String, allowDuplicateIssuers: Boolean, active: () -> Boolean): Boolean {
        if (!current(request) || !active() || !current(request)) return false
        if (uri.isBlank() || uri.length > InputPolicy.MAX_URI_CHARS) {
            request.message = "Enter a supported OTP URI within the size limit."
            return false
        }
        val policy = request.duplicatePolicy ?: allowDuplicateIssuers
        val result = try {
            coordinator.importQrPayload(uri, BackupCoordinator.DuplicateIssuerPolicy { policy })
        } catch (_: Exception) {
            if (!afterImport(request, active)) return false
            coordinator.cancelMigrationImport(); request.duplicatePolicy = null
            request.message = "Import failed. Check the URI and vault storage, then try again."
            return false
        }
        if (!afterImport(request, active)) return false
        request.duplicatePolicy = if (coordinator.migrationInProgress()) policy else null
        request.message = when (result) {
            is BackupCoordinator.ImportResult.Imported -> "Imported ${result.count} accounts. Exact duplicates and excluded issuers are skipped."
            is BackupCoordinator.ImportResult.MigrationPart -> "Received ${result.received} of ${result.expected} parts. Paste the next part; nothing is saved until the batch is complete."
            is BackupCoordinator.ImportResult.DuplicateIssuerRejected -> "An account from this issuer already exists. Enable additional accounts and paste the URI again to import it."
            is BackupCoordinator.ImportResult.DuplicateEntry -> "This account is already in the vault."
            is BackupCoordinator.ImportResult.PersistenceFailed -> "Could not save the import. No accounts were added; paste the URI or all migration parts again."
            is BackupCoordinator.ImportResult.MigrationRejected -> when (result.why) {
                BackupCoordinator.ImportResult.MigrationRejection.DUPLICATE_PART -> "This part was already received. Paste a different part."
                BackupCoordinator.ImportResult.MigrationRejection.DIFFERENT_EXPORT -> "This part belongs to another export. Finish this batch or cancel before starting another."
                BackupCoordinator.ImportResult.MigrationRejection.EXPECTED_MIGRATION -> "A migration batch is pending. Paste the next migration part or cancel."
                else -> "The migration batch was rejected. Cancel and start again with all parts."
            }
            else -> "The URI is not a valid supported OTP import."
        }
        return result is BackupCoordinator.ImportResult.Imported && result.count > 0
    }
    private fun current(request: Request) = !closed && !state.isLocked && pending === request

    private fun afterImport(request: Request, active: () -> Boolean): Boolean {
        // A write may already be committed. Invalidate completion and buffered migration data,
        // but never cancel a newer request created by a reentrant guard.
        val live = active()
        if (live && current(request)) return true
        if (pending === request) dismiss(request)
        return false
    }

}
