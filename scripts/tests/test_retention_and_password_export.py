"""Guards for the two gaps closed on 2026-11-06 after the PR #112 audit.

**1. Automatic backups were never pruned.** Every automatic export wrote a new
`otp_accounts_<uuid>.json.aes` and left the previous ones behind, so the backup folder grew by one
file per password save forever (upstream #925/#531). The policy now lives in `:core`
(`BackupRetention`), the SAF deletion in the app (`AutomaticBackupPruner`), and the choice is a
ListPreference that defaults to *keep everything* — the old, non-destructive behaviour.

**2. Individual browser passwords could not be edited or re-exported.** `PASSWORD` entries carried
every source CSV column but nothing could write them back out, so a changed password meant deleting
the entry and re-importing the whole file. `PasswordCsvExport` (`:core`, `OfflineOTPKit` on iOS) now
decides which entries have a browser CSV form, serialises them in their own dialect, and applies an
edited URL/username/password while keeping every other column.

The Android and iOS halves need a device and a simulator to exercise end to end, so their wiring is
pinned by reading the sources; the policy itself is unit-tested in `:core` (7 tests), the Swift
package, and the sandbox check *browser passwords can be edited and re-exported without losing their
columns* plus *automatic backup retention prunes only this app's old automatic backups*.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities"
CORE_TESTS = ROOT / "core/src/test/kotlin/com/fairuniversal/offlineotp/Utilities"
APP = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp"
RES = ROOT / "app/src/main/res"
DESKTOP = ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop"
IOS_KIT = ROOT / "ios/Sources/OfflineOTPKit"
IOS_APP = ROOT / "ios/App/Sources"
SANDBOX = ROOT / "scripts/kotlin-sandbox"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class BackupRetentionTest(unittest.TestCase):
    def test_the_policy_is_android_free_and_unit_tested(self):
        policy = read(CORE / "BackupRetention.kt")
        self.assertNotIn("import android", policy)
        self.assertIn("fun plan(", policy)
        self.assertIn("fun isPrunableName(", policy)
        # Defaults matter: 0 means "keep everything", which is what every existing vault does today.
        self.assertIn("DEFAULT_KEEP = 0", policy)
        self.assertIn("BackupRetentionTest.kt", "BackupRetentionTest.kt")
        self.assertTrue((CORE_TESTS / "BackupRetentionTest.kt").is_file())

    def test_only_this_app_s_automatic_backups_are_candidates(self):
        policy = read(CORE / "BackupRetention.kt")
        # A dated manual export and the shared otp_accounts.json.aes must never be auto-deleted.
        self.assertIn("json.aes", policy)
        self.assertIn("json.gpg", policy)
        self.assertIn("isUuid", policy)
        self.assertNotIn("\"otp_accounts.json", policy.replace("otp_accounts_", ""))

    def test_pruning_runs_only_after_a_successful_automatic_export(self):
        task = read(APP / "Tasks/GenericBackupTask.kt")
        self.assertIn("protected open fun isAutomatic(): Boolean = false", task)
        self.assertIn("if (isAutomatic()) pruneOldAutomaticBackups()", task)
        encrypted = read(APP / "Tasks/EncryptedBackupTask.kt")
        self.assertIn("override fun isAutomatic(): Boolean = mode != Mode.USER", encrypted)

    def test_the_provider_refusal_is_counted_and_never_fails_the_backup(self):
        pruner = read(APP / "Utilities/AutomaticBackupPruner.kt")
        self.assertIn("class Result(val deleted: Int, val planned: Int, val failed: Int)", pruner)
        self.assertIn("RuntimeException", pruner)
        self.assertNotIn("throw", pruner.split("fun pruneTree")[1])

    def test_the_choice_is_a_bounded_list_preference_that_defaults_to_off(self):
        prefs = read(RES / "xml/preferences.xml")
        self.assertIn('android:key="@string/settings_key_auto_backup_keep"', prefs)
        self.assertRegex(prefs, r'settings_key_auto_backup_keep"[^>]*android:defaultValue="0"')
        strings = read(RES / "values/strings_settings.xml")
        for name in ("settings_title_auto_backup_keep", "settings_desc_auto_backup_keep",
                     "settings_entries_auto_backup_keep", "settings_values_auto_backup_keep"):
            self.assertIn(name, strings)
        settings = read(APP / "Utilities/Settings.kt")
        self.assertIn("fun getAutoBackupKeepCount()", settings)
        self.assertIn("BackupRetention.normalizeKeep", settings)

    def test_the_sandbox_compiles_the_new_sources(self):
        run = read(SANDBOX / "run.sh")
        self.assertIn("Utilities/BackupRetention.kt", run)
        self.assertIn("Utilities/AutomaticBackupPruner.kt", run)
        self.assertIn("automatic backup retention prunes", read(SANDBOX / "Checks.kt"))


class BrowserPasswordExportTest(unittest.TestCase):
    def test_the_policy_exists_on_every_client(self):
        self.assertTrue((CORE / "PasswordCsvExport.kt").is_file())
        self.assertTrue((IOS_KIT / "PasswordCsvExport.swift").is_file())
        self.assertTrue((CORE_TESTS / "PasswordCsvExportTest.kt").is_file())
        self.assertTrue((ROOT / "ios/Tests/OfflineOTPKitTests/PasswordCsvExportTests.swift").is_file())

    def test_foreign_column_sets_are_refused_rather_than_flattened(self):
        kotlin = read(CORE / "PasswordCsvExport.kt")
        self.assertIn("object Foreign", kotlin)
        self.assertIn("object NotPassword", kotlin)
        self.assertIn("require(support(entry) is Support.Exportable)", kotlin)
        swift = read(IOS_KIT / "PasswordCsvExport.swift")
        self.assertIn("case foreign", swift)
        self.assertIn("case notPassword", swift)
        self.assertIn("PasswordCsvExportError.notABrowserEntry", swift)

    def test_an_edit_keeps_every_other_column_and_refuses_an_empty_password(self):
        kotlin = read(CORE / "PasswordCsvExport.kt")
        self.assertIn("fun withEditedFields(", kotlin)
        self.assertIn('require(!effectivePassword.isNullOrEmpty())', kotlin)
        swift = read(IOS_KIT / "PasswordCsvExport.swift")
        self.assertIn("public static func withEditedFields(", swift)
        self.assertIn("PasswordCsvExportError.emptyPassword", swift)

    def test_android_reaches_the_policy_from_the_entry_menu(self):
        menu = read(RES / "menu/menu_popup.xml")
        self.assertIn('android:id="@+id/menu_popup_edit_password"', menu)
        self.assertIn('android:id="@+id/menu_popup_export_password_csv"', menu)
        adapter = read(APP / "View/EntriesCardAdapter.kt")
        self.assertIn("PasswordEntryEditDialog.show(hostActivity, actual)", adapter)
        self.assertIn("requestPasswordCsvExport(actual)", adapter)
        self.assertIn("PasswordCsvExport.support(popupEntry) is PasswordCsvExport.Support.Exportable", adapter)
        dialog = read(APP / "Dialogs/PasswordEntryEditDialog.kt")
        # The password box starts empty: opening the form must not reveal a stored secret.
        self.assertIn('field(context.getString(R.string.password_edit_password), "", secret = true)', dialog)
        self.assertIn("password = typedPassword.ifEmpty { null }", dialog)

    def test_android_exports_after_the_picker_returns_and_wipes_the_buffer(self):
        main = read(APP / "Activities/MainActivity.kt")
        self.assertIn("Constants.INTENT_MAIN_PASSWORD_CSV_EXPORT", main)
        self.assertIn("fun requestPasswordCsvExport(", main)
        self.assertIn("passwordCsvExportPending?.fill(0)", main)
        self.assertIn("PasswordCsvExport.exportCsv(chosen, dialect)", main)
        constants = read(CORE / "Constants.kt")
        self.assertIn("INTENT_MAIN_PASSWORD_CSV_EXPORT = 113", constants)

    def test_desktop_offers_the_same_edit_and_export(self):
        window = read(DESKTOP / "ui/MainWindow.kt")
        self.assertIn('"Edit password…"', window)
        self.assertIn('"Export as browser CSV…"', window)
        self.assertIn('"Export passwords as browser CSV…"', window)
        self.assertIn("PasswordCsvExport.support(entry) is PasswordCsvExport.Support.Exportable", window)
        dialogs = read(DESKTOP / "ui/PasswordImportDialogs.kt")
        self.assertIn("fun edit(parent: JFrame, entry: Entry): Entry?", dialogs)
        self.assertIn("fun exportCsv(parent: JFrame, entries: List<Entry>", dialogs)

    def test_ios_edits_in_place_and_exports_one_entry(self):
        details = read(IOS_APP / "CredentialDetailViews.swift")
        self.assertIn("struct PasswordEditSheet: View", details)
        self.assertIn("store.update(at: index, with: replacement)", details)
        self.assertIn("password.isEmpty ? nil : password", details)
        view = read(IOS_APP / "EntryListView.swift")
        self.assertIn("private func exportBrowserPasswords(", view)
        self.assertIn("onExportCsv", view)
        self.assertIn("archiveExportIsBrowserCsv", view)


if __name__ == "__main__":
    unittest.main()
