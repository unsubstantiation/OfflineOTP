package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.Utilities.Constants
import com.fairuniversal.offlineotp.View.EntryListPresentation

/**
 * User-facing wording for the shared vault screen.
 *
 * The Compose UI owns no resource bundle of its own: Android already ships thirty translated
 * `values-*` lists and the desktop client is English-only. Hosts therefore pass this value in
 * (Android from its string resources, the desktop preview from [English]), and the module keeps a
 * single English default so previews and tests still render complete sentences.
 */
data class VaultStrings(
    val appTitle: String = "OfflineOTP",
    val tools: String = "Tools",
    val importTools: String = "Import accounts",
    val backupTools: String = "Encrypted backups",
    val settingsTools: String = "Display and security",
    val closeTools: String = "Close",
    val addEntry: String = "Add entry",
    val cardLayout: String = "Card layout",
    val autoLock: String = "Auto-lock",
    val emailDetails: String = "Connection details",
    val importLinScp: String = "Import LinSCP credentials",
    val importEmail: String = "Import FairEmail backup",
    val importPasswords: String = "Import password file",
    val importUri: String = "Import OTP URI",
    val restoreEncrypted: String = "Restore encrypted backup",
    val exportEncrypted: String = "Export encrypted backup",
    val editEntry: String = "Edit",
    val searchHint: String = "Search vault",
    val lockAction: String = "Lock",
    val sortAction: String = "Sort",
    val clearFilters: String = "Clear",
    val allTags: String = "All tags",
    val privacyReveal: String = "Reveal entry details",
    val privacyHide: String = "Hide entry details",
    val moveUp: String = "Move up",
    val moveDown: String = "Move down",
    val copyContent: String = "Copy",
    val copyAction: String = "Copy code",
    val revealAction: String = "Reveal",
    val hideAction: String = "Hide",
    val entryActions: String = "Account actions",
    val deleteAction: String = "Delete",
    val enterPin: String = "Enter PIN",
    val pinRequired: String = "PIN required",
    val expiringLabel: String = "Expiring",
    val counterLabel: String = "Counter",
    val nextCode: String = "Next code",
    val noCode: String = "No code yet",
    val unlockTitle: String = "Unlock OfflineOTP",
    val passwordLabel: String = "Vault password",
    val unlockAction: String = "Unlock",
    val unlocking: String = "Unlocking…",
    val emptyTitle: String = "No entries yet",
    val emptyDescription: String = "Add an account and it will appear in this tab.",
    val noMatchesTitle: String = "No matches",
    val noMatchesDescription: String = "No entry matches the current search and tag filter.",
    val emptyTabTitle: String = "Nothing in this tab",
    val emptyTabDescription: String = "Entries of this kind appear here.",
    val tagChipUntagged: String = "Untagged",
    /** Noun after the entry count in the app bar. */
    val entriesSuffix: String = "entries",
    /** Host formatting preserves localized units and number formatting (Android's existing resource). */
    val customPeriod: (Int) -> String = { seconds -> "$seconds s" }
) {
    /**
     * Tab label for one category, in `EntryListPresentation.Category` order.
     *
     * The wording is the Android resources' own (`tab_otp`…`tab_email`), which the desktop client
     * also shows, so the three renderers cannot label the same tab differently. `scripts/tests/
     * test_compose_ui_module.py` fails if these defaults drift from `strings_main.xml`.
     */
    fun categoryTitle(category: EntryListPresentation.Category): String = when (category) {
        EntryListPresentation.Category.OTP -> "OTP"
        EntryListPresentation.Category.NOTES -> "Notes"
        EntryListPresentation.Category.SSH_KEYS -> "SSH Keys"
        EntryListPresentation.Category.PASSWORDS -> "Passwords"
        EntryListPresentation.Category.EMAILS -> "Email"
    }

    /** Label for one list order, matching the Android sort menu. */
    fun sortTitle(mode: Constants.SortMode): String = when (mode) {
        Constants.SortMode.UNSORTED -> "Unsorted"
        Constants.SortMode.ISSUER -> "Issuer"
        Constants.SortMode.LABEL -> "Label"
        Constants.SortMode.LAST_USED -> "Last used"
        Constants.SortMode.MOST_USED -> "Most used"
    }

    /** `Tag (3)` as the tag panel shows it. */
    fun tagCount(name: String, count: Int): String = if (count <= 0) name else "$name ($count)"

    /** `Codes (12)` in a tab label. */
    fun categoryCount(category: EntryListPresentation.Category, count: Int): String =
        if (count <= 0) categoryTitle(category) else "${categoryTitle(category)} ($count)"

    companion object {
        /** The default wording, used by the desktop preview, previews and unit tests. */
        val English: VaultStrings = VaultStrings()
    }
}
