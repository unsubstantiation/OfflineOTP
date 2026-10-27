"""Source guards supplement the host and device tests for the repaired UI paths."""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp"


def body(text, signature):
    start = text.index(signature)
    end = text.index("\n    }\n", start)
    return text[start:end]


class CompletedFeatureWiringTest(unittest.TestCase):
    def test_search_text_is_kept_in_the_adapter_during_list_refresh(self):
        main = (APP / "Activities/MainActivity.kt").read_text(encoding="utf-8")
        adapter = (APP / "View/EntriesCardAdapter.kt").read_text(encoding="utf-8")
        self.assertIn("currentAdapter.setSearchQuery(newText)", body(main, "private fun setFilterString"))
        self.assertIn("presentation.setSearch(query, settings.getSearchValues())",
                      body(adapter, "fun setSearchQuery"))
        self.assertIn("presentation.refreshTags(", body(adapter, "private fun entriesChanged"))
        self.assertIn("presentation.filterByTags(", body(adapter, "fun filterByTags"))

    def test_import_confirmation_is_closed_on_lock_and_destruction(self):
        main = (APP / "Activities/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("dismissPendingImport()", body(main, "override fun onVaultLocked"))
        self.assertIn("dismissPendingImport()", body(main, "override fun onDestroy"))
        confirmation = body(main, "private fun showImportConfirmation")
        self.assertIn("synchronized(VaultSession.MutationLock)", confirmation)
        self.assertIn("pending.take(VaultSession.generation(), authorized)", confirmation)
        self.assertIn("dialog.setOnDismissListener", confirmation)
        self.assertIn("pending.close()", confirmation)
        cleanup = body(main, "private fun dismissPendingImport")
        self.assertLess(cleanup.index("pendingImport?.close()"), cleanup.index("dialog?.dismiss()"))

    def test_card_and_import_preview_use_the_actual_token_step(self):
        holder = (APP / "View/EntryViewHolder.kt").read_text(encoding="utf-8")
        adapter = (APP / "View/EntriesCardAdapter.kt").read_text(encoding="utf-8")
        main = (APP / "Activities/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("entry.hasNonDefaultTokenStep()", holder)
        self.assertIn("entry.getTokenStepSeconds()", holder)
        self.assertIn("e.hasNonDefaultTokenStep()", body(adapter, "fun updateTimeBasedTokens"))
        preview = body(main, "private fun importPreviewText")
        self.assertIn("entry.getTokenStepSeconds()", preview)
        self.assertIn("R.string.security_import_motp_details", preview)


if __name__ == "__main__":
    unittest.main()
