package com.fairuniversal.offlineotp.ui.portable

/**
 * Toolkit-free presentation values for the portable iOS surface.
 *
 * This is deliberately not `:ui-model`: that module is compiled against JVM `core` and cannot be
 * consumed by Kotlin/Native. These values contain strings, numbers and booleans only, so the same
 * Compose declaration can be hosted by UIKit without dragging the engine into the framework.
 */

/** One vault card. [index] is an index into the host's live entry array, never a row position. */
data class PortableCard(
    val index: Int,
    val title: String,
    val subtitle: String,
    val concealedTitle: String,
    val code: String,
    val previousCode: String,
    val metadata: String,
    val secondsLeft: Int,
    val revealed: Boolean,
    val canStep: Boolean,
    val hasCode: Boolean,
    val mark: String,
    val tags: List<String>,
    val canReorder: Boolean = false,
    val pinRequired: Boolean = false
) {
    /** The name shown while the row is concealed, never the hidden label. */
    fun visibleTitle(hideIssuer: Boolean): String =
        if (revealed || !hideIssuer) title else concealedTitle

    /** A displayed code is never guessed from a hidden entry. */
    fun visibleCode(): String = if (revealed && hasCode) code else ""

    /** Matches `:ui-model`'s 5-second rollover warning window. */
    val isExpiring: Boolean
        get() = hasCode && secondsLeft in 1..5

    /** Redacts secret-bearing strings from diagnostic logs and assertion failures. */
    override fun toString(): String =
        "PortableCard(index=$index, revealed=$revealed, hasCode=$hasCode, pinRequired=$pinRequired)"
}

/** A tab or tag chip. Counts are supplied by the host so the model never re-derives them. */
data class PortableChip(
    val label: String,
    val count: Int,
    val selected: Boolean
)

/** The unlocked vault screen: everything a host has already decided, ready to draw. */
data class PortableVaultState(
    val tabs: List<PortableChip>,
    val query: String,
    val tags: List<PortableChip>,
    val includeUntagged: Boolean,
    val tagRule: String,
    val cards: List<PortableCard>,
    val sortLabel: String,
    val toast: String,
    val hiddenTitleEnabled: Boolean = false
) {
    val emptyMessage: String
        get() = if (query.isNotEmpty()) "No entries match this search."
                else "Nothing here yet. Add an entry with +."

    override fun toString(): String =
        "PortableVaultState(tabs=${tabs.size}, cards=${cards.size}, hasQuery=${query.isNotEmpty()})"

    companion object {
        val EMPTY = PortableVaultState(
            tabs = emptyList(), query = "", tags = emptyList(), includeUntagged = false,
            tagRule = "Any", cards = emptyList(), sortLabel = "Order", toast = ""
        )
    }
}

/** One row in a portable dialog. [key] is stable for the lifetime of the request. */
data class PortableRow(
    val key: String,
    val title: String,
    val value: String = "",
    val kind: String = KIND_BUTTON,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val detail: String = ""
) {
    companion object {
        const val KIND_BUTTON = "button"
        const val KIND_TOGGLE = "toggle"
        const val KIND_TEXT = "text"
        const val KIND_SECURE = "secure"
        const val KIND_INFO = "info"
        const val KIND_ACTION = "action"
    }
}

/** A titled group of rows. The renderer never invents a section. */
data class PortableSection(val title: String, val rows: List<PortableRow>)

/**
 * A dialog whose content is entirely host-supplied. Migration, backup, editor, detail and settings
 * surfaces share this one renderer; the host owns validation and every state change.
 */
data class PortableSurfaceState(
    val key: String,
    val title: String,
    val sections: List<PortableSection>,
    val confirmLabel: String = "Save",
    val dismissLabel: String = "Cancel",
    val showConfirm: Boolean = true,
    val message: String = "",
    val busy: Boolean = false
) {
    /** Rows the host marked as editable or actionable, in display order. */
    val interactiveRows: List<PortableRow>
        get() = sections.flatMap { it.rows }.filter {
            it.kind != PortableRow.KIND_INFO && it.enabled
        }
}
