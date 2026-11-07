package com.fairuniversal.offlineotp.Utilities

import android.content.Context
import android.net.Uri

import androidx.documentfile.provider.DocumentFile

/**
 * Deletes the oldest automatic backups once the folder holds more than the retention count.
 *
 * The **decision** is [BackupRetention]'s, which is Android-free and unit-tested: only names this
 * app generates for an automatic export are candidates, the newest N are never touched, and an
 * unknown timestamp counts as newest. This object only applies that plan to a Storage Access
 * Framework tree, and it is deliberately timid about doing so:
 *
 * - It runs **after** a successful export, so the fresh document is already in the kept set.
 * - Anything it cannot enumerate (a provider that returns null) is left alone rather than guessed
 *   at; a null listing deletes nothing.
 * - Each delete is independent: one provider refusal is counted and skipped, and the caller gets a
 *   count of what actually went, not what was planned.
 * - It is best-effort throughout. A failed prune must never fail the backup that just succeeded,
 *   so every provider call is wrapped; the vault is already safely written.
 *
 * Automatic backups only. A manual export the user named (even under the same folder) is never a
 * candidate, which is why [BackupRetention.isPrunableName] matches the UUID form only.
 */
object AutomaticBackupPruner {

    /** What one prune run did; reported so the notification can be honest about it. */
    class Result(val deleted: Int, val planned: Int, val failed: Int) {
        val pruned: Boolean get() = deleted > 0
        override fun toString(): String = "pruned $deleted of $planned (failed $failed)"
    }

    private val NONE = Result(deleted = 0, planned = 0, failed = 0)

    /**
     * Keeps the newest [keep] automatic backups in the configured folder and deletes older ones.
     * Returns [NONE] when retention is off, no folder is configured, or nothing needs pruning.
     */
    @JvmStatic
    fun prune(context: Context, settings: Settings, keep: Int): Result {
        val retain = BackupRetention.normalizeKeep(keep)
        if (retain <= 0) return NONE
        if (!settings.isBackupLocationSet()) return NONE
        val location: Uri = try {
            settings.getBackupLocation()
        } catch (_: RuntimeException) {
            return NONE
        }
        return pruneTree(context, location, retain)
    }

    /** The tree-level half, split out so a test can drive it with a fake folder. */
    @JvmStatic
    fun pruneTree(context: Context, location: Uri, keep: Int): Result {
        val retain = BackupRetention.normalizeKeep(keep)
        if (retain <= 0) return NONE
        val tree = try {
            DocumentFile.fromTreeUri(context, location)
        } catch (_: RuntimeException) {
            null
        } ?: return NONE

        val files = try {
            tree.listFiles()
        } catch (_: RuntimeException) {
            null
        } ?: return NONE

        val documents = ArrayList<DocumentFile>(files.size)
        val candidates = ArrayList<BackupRetention.Candidate>(files.size)
        for (file in files) {
            val name = try {
                file.getName()
            } catch (_: RuntimeException) {
                null
            }
            // A directory has no prunable name; isPrunableName() rejects it, and isDirectory()
            // would be another provider call whose failure mode we would have to guess at.
            if (!BackupRetention.isPrunableName(name)) continue
            documents.add(file)
            candidates.add(BackupRetention.Candidate(name!!, lastModifiedOf(file)))
        }
        val doomed = BackupRetention.plan(candidates, retain)
        if (doomed.isEmpty()) return NONE

        val byName = HashMap<String, DocumentFile>(documents.size)
        for (index in documents.indices) byName[candidates[index].name] = documents[index]

        var deleted = 0
        var failed = 0
        for (name in doomed) {
            val file = byName[name] ?: continue
            val removed = try {
                file.delete()
            } catch (_: RuntimeException) {
                false
            }
            if (removed) deleted++ else failed++
        }
        return Result(deleted, doomed.size, failed)
    }

    /** A provider that does not report a timestamp yields 0, which [BackupRetention] treats as newest. */
    private fun lastModifiedOf(file: DocumentFile): Long = try {
        file.lastModified()
    } catch (_: RuntimeException) {
        0L
    }
}
