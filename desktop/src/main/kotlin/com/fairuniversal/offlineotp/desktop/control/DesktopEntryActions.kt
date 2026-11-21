package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.EntryUsage
import com.fairuniversal.offlineotp.Utilities.ThumbnailCatalog
import com.fairuniversal.offlineotp.desktop.qr.QrCodeImage

/**
 * Headless entry sub-dialog owner for the desktop Compose client (QR code display from Edit,
 * HOTP counter editor, tag checklist, and artwork selection), enforcing live session and reveal
 * checks before disclosing secrets or persisting mutations.
 */
class DesktopEntryActions(
    private val state: DesktopVaultState,
    private val persist: () -> Boolean,
    private val changed: () -> Unit
) {
    enum class Surface { QR, TAGS, THUMBNAIL, COUNTER }

    class Request internal constructor(
        val entryId: Long,
        val surface: Surface,
        val title: String = "",
        val qrMatrix: QrCodeImage.Matrix? = null,
        val tagStates: LinkedHashMap<String, Boolean> = LinkedHashMap(),
        val tagCounts: Map<String, Int> = emptyMap(),
        val selectedThumbnail: String = ThumbnailCatalog.DEFAULT_NAME,
        val initialCounter: String = "0"
    )

    var pending: Request? = null
        private set
    private var closed = false

    /** Opens the scannable `otpauth://` QR dialog for a revealed OTP entry (from Edit). */
    fun openQr(entryId: Long, revealed: Boolean, active: () -> Boolean): Boolean {
        if (closed || state.isLocked || !revealed || !active()) return false
        val entry = liveEntry(entryId) ?: return false
        val payload = QrCodeImage.payloadFor(entry) ?: return false
        val matrix = runCatching { QrCodeImage.matrixFor(payload) }.getOrNull() ?: return false
        pending = Request(
            entryId = entryId,
            surface = Surface.QR,
            title = "QR code — ${displayTitle(entry)}",
            qrMatrix = matrix
        )
        changed()
        return true
    }

    /** Opens the HOTP counter dialog directly (e.g. from long-clicking `Step`). */
    fun openCounter(entryId: Long, revealed: Boolean, active: () -> Boolean): Boolean {
        if (closed || state.isLocked || !revealed || !active()) return false
        val entry = liveEntry(entryId) ?: return false
        if (!entry.isCounterBased()) return false
        pending = Request(
            entryId = entryId,
            surface = Surface.COUNTER,
            title = "Set HOTP counter",
            initialCounter = entry.getCounter().toString()
        )
        changed()
        return true
    }

    /** Opens the tag checklist for [entryId]. */
    fun openTags(entryId: Long, active: () -> Boolean): Boolean {
        if (closed || state.isLocked || !active()) return false
        val entry = liveEntry(entryId) ?: return false
        val counts = LinkedHashMap<String, Int>()
        for (item in state.snapshot()) {
            for (tag in item.getTags()) {
                counts[tag] = (counts[tag] ?: 0) + 1
            }
        }
        val currentTags = entry.getTags().toSet()
        val states = LinkedHashMap<String, Boolean>()
        for (tag in counts.keys.sorted()) {
            states[tag] = tag in currentTags
        }
        for (tag in entry.getTags()) {
            if (tag !in states) states[tag] = true
        }
        pending = Request(
            entryId = entryId,
            surface = Surface.TAGS,
            title = "Manage tags",
            tagStates = states,
            tagCounts = counts
        )
        changed()
        return true
    }

    /** Opens the artwork picker for [entryId]. */
    fun openThumbnail(entryId: Long, active: () -> Boolean): Boolean {
        if (closed || state.isLocked || !active()) return false
        val entry = liveEntry(entryId) ?: return false
        pending = Request(
            entryId = entryId,
            surface = Surface.THUMBNAIL,
            title = "Change artwork",
            selectedThumbnail = entry.getThumbnailName()
        )
        changed()
        return true
    }

    fun submitTags(request: Request, selectedStates: Map<String, Boolean>, active: () -> Boolean): Boolean {
        if (!current(request) || request.surface != Surface.TAGS || !active()) return false
        val chosen = selectedStates.filterValues { it }.keys.toList()
        val ok = state.setTags(request.entryId, chosen, persist)
        dismiss(request)
        return ok
    }

    fun submitThumbnail(request: Request, chosen: String?, active: () -> Boolean): Boolean {
        if (!current(request) || request.surface != Surface.THUMBNAIL || !active()) return false
        val resolved = chosen ?: ThumbnailCatalog.DEFAULT_NAME
        val ok = state.setThumbnail(request.entryId, resolved, persist)
        dismiss(request)
        return ok
    }

    fun submitCounter(request: Request, counterText: String, revealed: Boolean, active: () -> Boolean): Boolean {
        if (!current(request) || request.surface != Surface.COUNTER || !revealed || !active()) return false
        val parsed = counterText.trim().toLongOrNull() ?: return false
        if (parsed < 0L || parsed >= EntryUsage.MAX_COUNTER) return false
        val ok = state.setCounter(request.entryId, parsed, persist)
        dismiss(request)
        return ok
    }

    fun dismiss(request: Request) {
        if (pending !== request) return
        pending = null
        changed()
    }

    fun close() {
        closed = true
        pending = null
    }

    private fun current(request: Request): Boolean =
        !closed && !state.isLocked && pending === request

    private fun liveEntry(id: Long): Entry? =
        state.snapshot().firstOrNull { it.getListId() == id }

    companion object {
        fun isValidCounter(text: String): Boolean =
            text.trim().toLongOrNull()?.let { it in 0L until EntryUsage.MAX_COUNTER } == true

        private fun displayTitle(entry: Entry): String =
            entry.getIssuer().ifBlank { entry.getLabel().ifBlank { "Entry" } }
    }
}
