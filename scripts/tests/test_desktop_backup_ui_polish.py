"""Guards for the desktop backup prompts and the trimmed-down main window.

Three user-facing desktop behaviors are pinned here:

* a new backup is suggested with a **dated** file name (``otp_accounts_<timestamp>.json.aes``) like
  the Android client, instead of the fixed ``otp_accounts.json.aes`` that a second export would
  overwrite;
* creating a password-encrypted backup asks for the password **twice**, with a show-password
  toggle, exactly like Android's ``PasswordEntryDialog.Mode.UPDATE`` (restore keeps the
  single-entry prompt);
* the main window has no bottom status/footer row (entry count + "OFFLINE"), and the issuer mark on
  an entry card has no countdown ring around it.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DESKTOP_UI = ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop/ui"
CORE_KOTLIN = ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp/Utilities"
APP_KOTLIN = ROOT / "app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities"


def read(path):
    return Path(path).read_text(encoding="utf-8")


class DatedBackupNameTest(unittest.TestCase):
    def test_desktop_suggests_a_dated_encrypted_backup_name(self):
        dialogs = read(DESKTOP_UI / "BackupDialogs.kt")
        self.assertIn("BackupFileNames.encrypted(Date())", dialogs)
        self.assertIn("BackupFileNames.openPgp(Date())", dialogs)
        # The fixed names would overwrite an earlier export; they must not be offered again.
        self.assertNotIn("Constants.BACKUP_FILENAME_CRYPT,", dialogs)
        self.assertNotIn("Constants.BACKUP_FILENAME_PGP,", dialogs)
        self.assertNotIn("Constants.BACKUP_FILENAME_CRYPT)", dialogs)
        self.assertNotIn("Constants.BACKUP_FILENAME_PGP)", dialogs)

    def test_shared_name_helper_matches_the_android_client(self):
        helper = read(CORE_KOTLIN / "BackupFileNames.kt")
        self.assertIn('TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"', helper)
        self.assertIn("Constants.BACKUP_FILENAME_CRYPT_FORMAT", helper)
        self.assertIn("Constants.BACKUP_FILENAME_PGP_FORMAT", helper)
        # Android formats its dates through the same helper, so the two clients cannot drift.
        tools = read(APP_KOTLIN / "Tools.kt")
        self.assertIn("BackupFileNames.timestamp(", tools)
        self.assertNotIn('SimpleDateFormat("yyyy-MM-dd_HH-mm-ss"', tools)
        # The sandbox harness compiles an explicit core file list; a new core source must be added.
        self.assertIn('"$KOTLIN_CORE/Utilities/BackupFileNames.kt"',
                      read(ROOT / "scripts/kotlin-sandbox/run.sh"))

    def test_core_helper_has_a_jvm_test(self):
        test = read(ROOT / "core/src/test/kotlin/org/shadowice/flocke/andotp/Utilities/BackupFileNamesTest.kt")
        self.assertIn("BackupFileNames.encrypted(", test)
        self.assertIn("BackupFileNames.openPgp(", test)


class NewBackupPasswordTest(unittest.TestCase):
    def test_new_backups_prompt_for_the_password_twice(self):
        dialogs = read(DESKTOP_UI / "BackupDialogs.kt")
        save = dialogs[dialogs.index("fun saveEncryptedBackup"):dialogs.index("fun restoreEncryptedBackup")]
        self.assertIn("PasswordDialog.promptNewBackupPassword(parent)", save)
        # Restoring an existing backup must keep the single prompt: its password is whatever the
        # file was created with, so it may be shorter than the policy minimum.
        restore = dialogs[dialogs.index("fun restoreEncryptedBackup"):dialogs.index("fun saveOpenPgpBackup")]
        self.assertIn('PasswordDialog.prompt(parent, "Backup password")', restore)
        self.assertNotIn("promptNewBackupPassword", restore)

    def test_confirmation_prompt_has_a_visibility_toggle(self):
        dialog = read(DESKTOP_UI / "PasswordDialog.kt")
        self.assertIn('JCheckBox("Show password")', dialog)
        self.assertIn("echoChar = if (visible) 0.toChar() else hiddenEchoChar", dialog)
        self.assertIn("echoChar = if (visible) 0.toChar() else hiddenConfirmEchoChar", dialog)
        # Both inputs stay JPasswordFields, so hidden text is not readable as plain widget text.
        self.assertIn("internal val passwordField = JPasswordField(20)", dialog)
        self.assertIn("internal val confirmationField = JPasswordField(20)", dialog)
        self.assertNotIn("import javax.swing.JTextField", dialog)
        self.assertNotIn("JTextField(", dialog)

    def test_prompt_enforces_the_android_password_rule(self):
        dialog = read(DESKTOP_UI / "PasswordDialog.kt")
        policy = dialog[dialog.index("internal object NewBackupPassword"):dialog.index("internal class NewBackupPasswordPanel")]
        self.assertIn("password.length < Constants.AUTH_MIN_PASSWORD_LENGTH", policy)
        self.assertIn("password != confirmation", policy)
        self.assertIn("password.isEmpty()", policy)
        # A mismatch or a short password must keep the modal open instead of accepting it.
        self.assertIn("if (panel.confirmButtonEnabled) return panel.password()", dialog)
        self.assertIn("panel.clear()", dialog)

    def test_desktop_tests_cover_the_panel(self):
        test = read(ROOT / "desktop/src/test/kotlin/org/shadowice/flocke/andotp/desktop/ui/"
                           "NewBackupPasswordPanelTest.kt")
        self.assertIn("setPasswordVisible(true)", test)
        self.assertIn("confirmButtonEnabled", test)


class NoFooterRowTest(unittest.TestCase):
    def test_main_screen_has_no_bottom_status_row(self):
        screen = read(DESKTOP_UI / "MainScreen.kt")
        self.assertNotIn('"OFFLINE"', screen)
        self.assertNotIn("val status", screen)
        self.assertNotIn("add(status", screen)
        self.assertIn("add(header, BorderLayout.NORTH)", screen)
        self.assertIn("add(content, BorderLayout.CENTER)", screen)
        self.assertNotIn("BorderLayout.SOUTH", screen)

    def test_no_status_writes_remain_in_the_window(self):
        window = read(DESKTOP_UI / "MainWindow.kt")
        self.assertNotIn("setStatus", window)
        self.assertNotIn("screen.status", window)
        self.assertNotIn("entries unlocked", window)

    def test_the_heading_count_is_kept(self):
        # Only the footer goes away; the "(n)" count next to "Your entries" stays.
        screen = read(DESKTOP_UI / "MainScreen.kt")
        self.assertIn('count.text = if (unlocked) "($rows)" else ""', screen)


class IssuerMarkWithoutRingTest(unittest.TestCase):
    def test_card_avatar_paints_no_ring(self):
        cards = read(DESKTOP_UI / "EntryCards.kt")
        avatar = cards[cards.index("private class CardAvatar"):]
        self.assertNotIn("drawArc", avatar)
        self.assertNotIn("OTP_AMBER_MUTED", cards)
        self.assertNotIn("var remaining", avatar)
        self.assertNotIn("var expiring", avatar)
        # The artwork and the letter tile still paint.
        self.assertIn("g.drawImage(mark", avatar)
        self.assertIn("g.drawString(initial", avatar)

    def test_the_renderer_no_longer_feeds_a_ring(self):
        cards = read(DESKTOP_UI / "EntryCards.kt")
        self.assertNotIn("avatar.remaining", cards)
        self.assertNotIn("avatar.expiring", cards)
        # Expiring codes still switch the token color.
        self.assertIn("expiring -> ForestTheme.WARNING", cards)


if __name__ == "__main__":
    unittest.main()
