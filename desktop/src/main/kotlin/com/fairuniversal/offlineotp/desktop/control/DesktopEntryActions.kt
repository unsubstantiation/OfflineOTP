package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.Utilities.EntryUsage
import com.fairuniversal.offlineotp.Utilities.ThumbnailCatalog
import com.fairuniversal.offlineotp.desktop.qr.QrCodeImage

/**
 * Headless per-entry actions owner for the desktop Compose client, bringing desktop parity with
 * Android's popup menu (`EntryActionDialogs` / `AndroidVaultController`) and iOS's `more:<index>`:
 *
 * - Read-only entry details (`DETAILS`) — gated on live reveal;
 * - Scannable `otpauth://` QR code (`QR`) — gated on live reveal and [Entry.hasOtpUri];
 * - Interactive tag checklist & creation (`TAGS`);
 * - Built-in catalog artwork selection (`THUMBNAIL`);
 * - Explicit HOTP counter editor (`COUNTER`) — gated on live reveal and [Entry.isCounterBased];
 * - Accessible `Move up` / `Move down` commands when manual ordering is active.
 */
class DesktopEntryActions(
    private val state: DesktopVaultState,
    private val persist: () -> Boolean,
    private val changed: () -> Unit
) {
    enum class Surface { MENU, DETAILS, QR, TAGS, THUMBNAIL, COUNTER }

    enum class Action(val label: String) {
        REVEAL_OR_HIDE("Reveal or hide"),
        DETAILS("Details"),
        SHOW_QR("Show QR code"),
        MANAGE_TAGS("Manage tags"),
        CHANGE_THUMBNAIL("Change artwork"),
        SET_COUNTER("Set counter…"),
        MOVE_UP("Move up"),
        MOVE_DOWN("Move down")
    }

    class Request internal constructor(
        val entryId: Long,
        var surface: Surface,
        val actions: List<Action> = emptyList(),
        val title: String = "",
        val detailsBody: String = "",
        val qrMatrix: QrCodeImage.Matrix? = null,
        val tagStates: LinkedHashMap<String, Boolean> = LinkedHashMap(),
        val tagCounts: Map<String, Int> = emptyMap(),
        val selectedThumbnail: String = ThumbnailCatalog.DEFAULT_NAME,
        val initialCounter: String = "0"
    )

    var pending: Request? = null
        private set
    private var closed = false

    /** Opens the action chooser for [entryId] with only the actions valid for its current state. */
    fun openMenu(
        entryId: Long,
        revealed: Boolean,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        active: () -> Boolean
    ): Boolean {
        if (closed || state.isLocked || !active()) return false
        val entry = liveEntry(entryId) ?: return false
        val items = buildList {
            add(Action.REVEAL_OR_HIDE)
            if (revealed) {
                add(Action.DETAILS)
                if (QrCodeImage.payloadFor(entry) != null) add(Action.SHOW_QR)
            }
            add(Action.MANAGE_TAGS)
            add(Action.CHANGE_THUMBNAIL)
            if (revealed && entry.isCounterBased()) add(Action.SET_COUNTER)
            if (canMoveUp) add(Action.MOVE_UP)
            if (canMoveDown) add(Action.MOVE_DOWN)
        }
        pending = Request(
            entryId = entryId,
            surface = Surface.MENU,
            actions = items,
            title = displayTitle(entry)
        )
        changed()
        return true
    }

    /** Opens the HOTP counter dialog directly (e.g. from long-clicking `Next code`). */
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

    /** Transitions from the action menu into one of the sub-dialogs, re-verifying session & reveal. */
    fun selectSubSurface(
        request: Request,
        action: Action,
        revealed: Boolean,
        active: () -> Boolean
    ): Boolean {
        if (!current(request) || !active()) {
            dismiss(request)
            return false
        }
        val entry = liveEntry(request.entryId) ?: run {
            dismiss(request)
            return false
        }
        val next: Request? = when (action) {
            Action.DETAILS -> if (!revealed) null else Request(
                entryId = request.entryId,
                surface = Surface.DETAILS,
                title = displayTitle(entry),
                detailsBody = buildDetailsBody(entry)
            )
            Action.SHOW_QR -> if (!revealed) null else {
                val payload = QrCodeImage.payloadFor(entry)
                val matrix = payload?.let { runCatching { QrCodeImage.matrixFor(it) }.getOrNull() }
                if (matrix == null) null else Request(
                    entryId = request.entryId,
                    surface = Surface.QR,
                    title = "QR code — ${displayTitle(entry)}",
                    qrMatrix = matrix
                )
            }
            Action.MANAGE_TAGS -> {
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
                Request(
                    entryId = request.entryId,
                    surface = Surface.TAGS,
                    title = "Manage tags",
                    tagStates = states,
                    tagCounts = counts
                )
            }
            Action.CHANGE_THUMBNAIL -> Request(
                entryId = request.entryId,
                surface = Surface.THUMBNAIL,
                title = "Change artwork",
                selectedThumbnail = entry.getThumbnailName()
            )
            Action.SET_COUNTER -> if (!revealed || !entry.isCounterBased()) null else Request(
                entryId = request.entryId,
                surface = Surface.COUNTER,
                title = "Set HOTP counter",
                initialCounter = entry.getCounter().toString()
            )
            Action.REVEAL_OR_HIDE, Action.MOVE_UP, Action.MOVE_DOWN -> null
        }
        pending = next
        changed()
        return next != null
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

        internal fun buildDetailsBody(entry: Entry): String = buildList {
            add("Type: ${entry.getType().name}")
            if (entry.getIssuer().isNotBlank()) add("Issuer: ${entry.getIssuer()}")
            if (entry.getLabel().isNotBlank()) add("Label: ${entry.getLabel()}")
            if (entry.getHiddenLabel().isNotBlank()) add("Hidden label: ${entry.getHiddenLabel()}")
            when {
                entry.isEmail() -> {
                    val account = runCatching { entry.getEmailAccount() }.getOrNull()
                    if (account != null) add("Identities: ${account.identities.size}")
                }
                entry.isPassword() -> {
                    val fields = runCatching { entry.getPasswordFields() }.getOrNull()
                    if (fields != null) {
                        val url = fields.value("url").ifBlank { fields.value("hostname") }
                        if (url.isNotBlank()) add("URL: $url")
                        val user = fields.value("username")
                        if (user.isNotBlank()) add("Username: $user")
                        add("Stored columns: ${fields.columns().size}")
                    }
                }
                entry.isPasswordArchive() -> {
                    add("Archive size: ${entry.getSecretSize()} bytes")
                }
                entry.isSshKey() -> {
                    val conn = entry.getSshConnection()
                    if (conn != null) add("Destination: ${conn.summary()}")
                }
                entry.isNote() -> {
                    add("Note length: ${entry.getNote().length} characters")
                }
                else -> {
                    if (entry.getType() == Entry.OTPType.TOTP || entry.getType() == Entry.OTPType.HOTP) {
                        add("Algorithm: ${entry.getAlgorithm().name}")
                    }
                    add("Digits: ${entry.getDigits()}")
                    if (entry.isCounterBased()) {
                        add("Counter: ${entry.getCounter()}")
                    } else {
                        add("Period: ${entry.getTokenStepSeconds()} s")
                    }
                }
            }
            if (entry.getTags().isNotEmpty()) {
                add("Tags: ${entry.getTags().joinToString(", ")}")
            }
        }.joinToString("\n")
    }
}
