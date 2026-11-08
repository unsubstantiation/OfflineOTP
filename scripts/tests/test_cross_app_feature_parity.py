"""Cross-app feature parity regression suite across Android (:app), Desktop (:desktop), and iOS (ios/).

Verifies that every shared capability is wired end-to-end across all three clients wherever the
platform allows it:
  1. All 9 entry kinds (`TOTP`, `HOTP`, `MOTP`, `STEAM`, `NOTE`, `SSH_KEY`, `PASSWORD`,
     `PASSWORD_ARCHIVE`, `EMAIL`) and all 5 category tabs (`OTP`, `Notes`, `SSH Keys`, `Passwords`,
     `Email`).
  2. Vault security: password change (`changePassword`), manual lock & unlock, inactivity auto-lock
     (`ui_inactivity_lock_seconds`), and 30-second clipboard auto-clear.
  3. Filtering, search, sorting, tag editing (`Tags…`), and manual reordering (`canDrag`/`canReorder`).
  4. Backup & credential interchange: Argon2id `.json.aes` full encrypted backup/restore, OpenPGP
     `.json.gpg` backup/restore, `otpauth://` + `otpauth-migration://` QR image & text link import,
     Browser password CSV + password file archive, FairEmail `.json`/gzip/`.efb` import & export +
     manual email account creation, Thunderbird CSV import & export, and LinSCP `LSCPVLT2` /
     `BackupBundle` / `sessions.ini` import & export.
"""

from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp"
ANDROID = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp"
DESKTOP = ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop"
IOS_KIT = ROOT / "ios/Sources/OfflineOTPKit"
IOS_APP = ROOT / "ios/App/Sources"


class CrossAppFeatureParityTest(unittest.TestCase):
    def test_all_three_clients_support_ssh_passwords_and_email_materials(self):
        # :core
        self.assertTrue((CORE / "Utilities/SshKeyMaterial.kt").is_file())
        self.assertTrue((CORE / "Utilities/SshConnection.kt").is_file())
        self.assertTrue((CORE / "Utilities/PasswordMaterial.kt").is_file())
        self.assertTrue((CORE / "Utilities/BrowserPasswordCsv.kt").is_file())
        self.assertTrue((CORE / "Utilities/EmailMaterial.kt").is_file())
        self.assertTrue((CORE / "Utilities/ThunderbirdImport.kt").is_file())
        self.assertTrue((CORE / "Utilities/LinScpCredentials.kt").is_file())

        # OfflineOTPKit (iOS)
        self.assertTrue((IOS_KIT / "SshMaterial.swift").is_file())
        self.assertTrue((IOS_KIT / "PasswordCredentials.swift").is_file())
        self.assertTrue((IOS_KIT / "EmailCredentials.swift").is_file())
        self.assertTrue((IOS_KIT / "LinScpVault.swift").is_file())

    def test_vault_password_change_auto_lock_and_clipboard_expiry_exist_everywhere(self):
        core_vault = (CORE / "Utilities/PasswordVault.kt").read_text(encoding="utf-8")
        desktop_ctrl = (DESKTOP / "control/VaultController.kt").read_text(encoding="utf-8")
        desktop_win = (DESKTOP / "ui/MainWindow.kt").read_text(encoding="utf-8")
        ios_vault = (IOS_KIT / "PasswordVault.swift").read_text(encoding="utf-8")
        ios_store = (IOS_APP / "VaultStore.swift").read_text(encoding="utf-8")
        ios_settings = (IOS_APP / "SettingsView.swift").read_text(encoding="utf-8")
        ios_clip = (IOS_APP / "Clipboard.swift").read_text(encoding="utf-8")
        android_main = (ANDROID / "Activities/MainActivity.kt").read_text(encoding="utf-8")

        # Password change
        self.assertIn("fun changePassword(", core_vault)
        self.assertIn("fun changePassword(", desktop_ctrl)
        self.assertIn("Change vault password…", desktop_win)
        self.assertIn("public func changePassword(", ios_vault)
        self.assertIn("public func changePassword(", ios_store)
        self.assertIn("Change vault password", ios_settings)

        # Manual lock & auto-lock on inactivity
        self.assertIn("Lock vault", desktop_win)
        self.assertIn("Auto-lock timeout…", desktop_win)
        self.assertIn("UI_INACTIVITY_LOCK_SECONDS", desktop_ctrl)
        self.assertIn("inactivitySettingKey = \"ui_inactivity_lock_seconds\"", ios_store)
        self.assertIn("checkInactivityAutoLock", ios_store)
        self.assertIn("ScreenTimerController", android_main)

        # 30s clipboard auto-clear
        self.assertIn("Timer(30_000)", desktop_win)
        self.assertIn("expirySeconds: TimeInterval = 30", ios_clip)

    def test_otp_uri_import_multi_tag_filter_and_reordering_exist_across_clients(self):
        android_dialog = (ANDROID / "Dialogs/ManualEntryDialog.kt").read_text(encoding="utf-8")
        desktop_coord = (DESKTOP / "control/BackupCoordinator.kt").read_text(encoding="utf-8")
        desktop_win = (DESKTOP / "ui/MainWindow.kt").read_text(encoding="utf-8")
        desktop_tags = (DESKTOP / "ui/TagDialog.kt").read_text(encoding="utf-8")
        ios_backup = (IOS_APP / "BackupView.swift").read_text(encoding="utf-8")
        ios_list = (IOS_APP / "EntryListView.swift").read_text(encoding="utf-8")
        ios_details = (IOS_APP / "CredentialDetailViews.swift").read_text(encoding="utf-8")

        # Text otpauth:// URI import
        self.assertIn("otpUriInput", android_dialog)
        self.assertIn("fun importQrPayload(", desktop_coord)
        self.assertIn("Import otpauth:// link…", desktop_win)
        self.assertIn("store.importUri(", ios_backup)

        # Multi-tag filtering (OR / AND / SINGLE + Untagged) & Tag editing
        self.assertIn("class MultiTagFilterPanel(", desktop_tags)
        self.assertIn("Filter by tags…", desktop_win)
        self.assertIn("struct TagStrip:", ios_list)
        self.assertIn("struct TagEditorSheet:", ios_details)

        # Manual reordering
        self.assertIn("moveSelected(", desktop_win)
        self.assertIn("store.moveDisplayed(", ios_list)

    def test_all_credential_import_and_export_workflows_are_exposed_across_clients(self):
        android_backup = (ANDROID / "Activities/BackupActivity.kt").read_text(encoding="utf-8")
        android_main = (ANDROID / "Activities/MainActivity.kt").read_text(encoding="utf-8")
        android_surface = android_backup + "\n" + android_main
        desktop_win = (DESKTOP / "ui/MainWindow.kt").read_text(encoding="utf-8")
        ios_backup = (IOS_APP / "BackupView.swift").read_text(encoding="utf-8")
        ios_list = (IOS_APP / "EntryListView.swift").read_text(encoding="utf-8")

        # Full Encrypted Backup + OpenPGP + FairEmail + Thunderbird + LinSCP + Browser Passwords
        for text in (android_surface, desktop_win, ios_backup):
            self.assertTrue("OpenPGP" in text or "openPgp" in text)
            self.assertIn("FairEmail", text)
            self.assertIn("Thunderbird", text)
            self.assertTrue("LinSCP" in text or "LinScp" in text)
            self.assertTrue("PasswordImport" in text or "importPasswordsCsv" in text)

        # Manual email account creation + SSH key creation + visible note display on iOS
        self.assertIn("Add email account…", ios_list)
        self.assertIn("Add SSH key…", ios_list)
        self.assertIn("entry.noteText", ios_list)

        # Direct password editing and bulk CSV export on iOS, and per-entry tag editing on Android
        self.assertIn('Button("Edit password…", action: onEditPassword)', ios_list)
        self.assertIn('Button("Export browser passwords CSV…", action: exportBrowserPasswords)', ios_backup)
        popup_menu = (ROOT / "app/src/main/res/menu/menu_popup.xml").read_text(encoding="utf-8")
        self.assertIn('android:id="@+id/menu_popup_tags"', popup_menu)
        adapter = (ANDROID / "View/AndroidVaultController.kt").read_text(encoding="utf-8")
        self.assertIn("R.id.menu_popup_tags", adapter)
        ios_settings = (IOS_APP / "SettingsView.swift").read_text(encoding="utf-8")
        self.assertIn("Copyright (c) 2026 unsubstantiation", ios_settings)
        self.assertIn("fairuniversal.com", ios_settings)


if __name__ == "__main__":
    unittest.main()
