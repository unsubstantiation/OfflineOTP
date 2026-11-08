"""The two clients must share one implementation of the rules they have in common.

Android and the desktop client are thin shells over `:core`, but several small rules used to be
written out in each shell: how a use is counted, how an HOTP counter advances, how a tag selection
is matched, which tab an entry belongs to, and how a restored backup is merged with the open vault.
Those copies are now single core helpers (`EntryUsage`, `EntryTagFilter`, `VaultRestoreMerge`,
`EntryListPresentation.categoryOf`, `Entry.hasOtpUri`, `EntryDraft`); these guards keep the shells
using them and keep the duplicated forms from creeping back.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp"
DESKTOP = ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop"
CORE = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp"


def sources(root):
    return sorted(path for path in root.rglob("*.kt"))


def text(path):
    return Path(path).read_text(encoding="utf-8")


def joined(root):
    return "\n".join(text(path) for path in sources(root))


class SharedClientLogicTest(unittest.TestCase):
    def test_the_shared_helpers_live_in_core(self):
        for name in ("Utilities/EntryUsage.kt", "Database/EntryTagFilter.kt",
                     "Utilities/VaultRestoreMerge.kt", "Database/EntryDraft.kt"):
            self.assertTrue((CORE / name).is_file(), f"{name} must live in :core")
            self.assertFalse((APP / name).exists(), f"{name} must not be duplicated in the app")
            self.assertFalse((DESKTOP / name).exists(),
                             f"{name} must not be duplicated in the desktop client")

    def test_both_clients_use_the_shared_usage_rules(self):
        # A copy on either client records one use through the shared helper.
        self.assertIn("EntryUsage.recordUse(", text(APP / "View/EntriesCardAdapter.kt"))
        self.assertIn("EntryUsage.recordUse(", text(DESKTOP / "control/EntryEditor.kt"))
        # New and imported entries are stamped through the shared helper as well.
        self.assertIn("EntryUsage.markImported(", text(APP / "View/EntriesCardAdapter.kt"))
        # The manual-entry dialogs stamp through EntryDraft.create(..., newEntry = true).
        self.assertIn("EntryDraft.create(", text(APP / "Dialogs/ManualEntryDialog.kt"))
        self.assertIn("EntryUsage.markImported(", text(DESKTOP / "control/EntryEditor.kt"))
        # The HOTP advance and its ceiling are shared, not re-derived per client.
        self.assertIn("EntryUsage.advanceHotp(", text(APP / "View/EntriesCardAdapter.kt"))
        self.assertIn("EntryUsage.advanceHotp(", text(DESKTOP / "control/DesktopVaultState.kt"))
        self.assertIn("state.advanceCounter(", text(DESKTOP / "control/EntryEditor.kt"))
        self.assertIn("state.deleteEntry(", text(DESKTOP / "control/EntryEditor.kt"))
        for root in (APP, DESKTOP):
            # Copying metadata is fine; *counting* a use is what must go through the helper.
            for path in sources(root):
                body = text(path)
                self.assertIsNone(re.search(r"getUsedFrequency\(\)\s*\+\s*1", body),
                                  f"{path} must count uses through EntryUsage.recordUse")
                self.assertNotIn("Long.MAX_VALUE - 1", body,
                                 f"{path}: the HOTP ceiling belongs to EntryUsage")

    def test_swing_confirmation_keeps_entry_identity(self):
        window = text(DESKTOP / "ui/MainWindow.kt")
        self.assertIn("editor?.remove(selected)", window)
        self.assertNotIn("editor?.removeAt(unlocked.presentation.sourceIndex(row))", window)
        editor = text(DESKTOP / "control/EntryEditor.kt")
        self.assertIn("state.snapshot().none { it === entry }", editor)

    def test_the_tag_rule_and_tag_order_are_shared(self):
        self.assertIn("EntryTagFilter.matches(", text(CORE / "Database/EntryList.kt"))
        # The desktop no longer applies the tag rule itself: it hands a VaultFilter to the shared
        # projection, which is the one place the selection is applied.
        self.assertIn("EntryTagFilter.matches(", text(ROOT / "ui-model/src/commonMain/kotlin/"
                         "com/fairuniversal/offlineotp/ui/vault/VaultProjection.kt"))
        self.assertIn("projection.applyFilter(", text(DESKTOP / "control/DesktopVaultState.kt"))
        self.assertNotIn("EntryTagFilter", text(DESKTOP / "control/DesktopVaultState.kt"))
        self.assertIn("VaultFilterCombination.SEARCH_AND_TAGS",
                      text(DESKTOP / "control/DesktopVaultState.kt"))
        self.assertIn("EntryTagFilter.sortedNames(", text(APP / "View/TagsAdapter.kt"))
        self.assertIn("EntryTagFilter.sortedNames(", text(DESKTOP / "ui/MainWindow.kt"))
        self.assertIn("EntryTagFilter.sortedNames(", text(DESKTOP / "ui/TagDialog.kt"))
        # A hand-written AND/OR predicate in a shell is exactly the copy that drifted before.
        for root in (APP, DESKTOP):
            self.assertNotIn("containsAll(", joined(root),
                             "the tag selection rule belongs to EntryTagFilter.matches")

    def test_both_clients_use_the_shared_category_mapping(self):
        self.assertIn("EntryListPresentation.categoryOf(", text(APP / "Activities/MainActivity.kt"))
        self.assertIn("EntryListPresentation.categoryOf(", text(DESKTOP / "ui/MainWindow.kt"))
        self.assertIn("categoryOf(entry) == selected", text(CORE / "View/EntryListPresentation.kt"))

    def test_both_clients_use_the_shared_qr_predicate(self):
        self.assertIn("hasOtpUri()", text(APP / "View/EntriesCardAdapter.kt"))
        self.assertIn("hasOtpUri()", text(DESKTOP / "qr/QrCodeImage.kt"))
        self.assertIn("fun hasOtpUri()", text(CORE / "Database/Entry.kt"))

    def test_both_restores_use_the_shared_merge_rule(self):
        self.assertIn("VaultRestoreMerge.plan(", text(APP / "Utilities/VaultRestoreOperation.kt"))
        self.assertIn("VaultRestoreMerge.plan(", text(DESKTOP / "control/BackupCoordinator.kt"))
        # The desktop used to build the merged list by hand; that copy must not return.
        self.assertNotIn("merged.addAll(current)", text(DESKTOP / "control/BackupCoordinator.kt"))

    def test_both_manual_entry_forms_build_through_the_shared_draft(self):
        android = text(APP / "Dialogs/ManualEntryDialog.kt")
        desktop = text(DESKTOP / "ui/EntryDialog.kt")
        for body, name in ((android, "Android"), (desktop, "desktop")):
            self.assertIn("EntryDraft.create(", body,
                          f"{name} must assemble entries through EntryDraft")
            self.assertIn("EntryDraft.sanitizeSecret(", body,
                          f"{name} must read secrets through EntryDraft.sanitizeSecret")
            # The offered kinds are one list, not a predicate written out per form.
            self.assertIn("EntryDraft.offerableTypes()", body,
                          f"{name} must take its type list from EntryDraft.offerableTypes")
            # Assembling an entry per type in a shell is the copy that drifted before.
            self.assertIsNone(re.search(r"\bEntry\.sshKey\(", body),
                              f"{name} must not build SSH entries by hand")
            self.assertIsNone(re.search(r"\bEntry\(type,", body),
                              f"{name} must not build drafts from per-type constructors")
            # A Save button may test its own fields, but the values that reach the model are
            # parsed once, by the shared builder.
            for stale in ("Integer.parseInt(digitsInput", "Long.parseLong(counterInput",
                          "Integer.parseInt(periodInput"):
                self.assertNotIn(stale, body, f"{name} must parse numbers through EntryDraft")
        # A new entry is stamped; an edit keeps the stamp the entry already carries.
        self.assertIn("newEntry = true", android)
        self.assertIn("newEntry = existing == null", desktop)

    def test_the_sandbox_gate_compiles_the_shared_helpers(self):
        script = text(ROOT / "scripts/kotlin-sandbox/run.sh")
        for name in ("Utilities/EntryUsage.kt", "Database/EntryTagFilter.kt",
                     "Utilities/VaultRestoreMerge.kt", "Database/EntryDraft.kt"):
            self.assertIn(f'"$KOTLIN_CORE/{name}"', script,
                          f"{name} must be listed in scripts/kotlin-sandbox/run.sh")


if __name__ == "__main__":
    unittest.main()
