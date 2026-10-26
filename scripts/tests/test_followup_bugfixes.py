"""Regression guards for follow-up UI, lifecycle, vault-lock, and backup bug fixes."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def read_text(rel_path: str) -> str:
    return (ROOT / rel_path).read_text(encoding="utf-8")


class PasswordConfirmationImeGuardTest(unittest.TestCase):
    """Every password confirmation input paired with OnEditorActionListener or a primary input
    must declare actionDone so soft keyboards emit IME_ACTION_DONE instead of newline/unspecified."""

    def test_password_confirmation_inputs_declare_action_done(self):
        layouts = {
            "app/src/main/res/layout/dialog_password_entry.xml": ("passwordInput", "passwordConfirm"),
            "app/src/main/res/layout/component_authentication.xml": ("passwordEdit", "passwordConfirm"),
            "app/src/main/res/layout/component_password.xml": ("passwordEdit", "passwordConfirm"),
            "app/src/main/res/layout/component_intro_authentication.xml": ("introPasswordEdit", "introPasswordConfirm"),
        }
        for rel_path, (primary_id, confirm_id) in layouts.items():
            tree = ET.parse(ROOT / rel_path)
            by_id = {}
            for elem in tree.iter():
                raw_id = elem.attrib.get(f"{ANDROID_NS}id", "")
                if raw_id.startswith("@+id/"):
                    by_id[raw_id.split("/", 1)[1]] = elem
            self.assertEqual(
                "actionNext",
                by_id[primary_id].attrib.get(f"{ANDROID_NS}imeOptions"),
                f"{rel_path}: {primary_id} must declare android:imeOptions='actionNext'",
            )
            self.assertEqual(
                "actionDone",
                by_id[confirm_id].attrib.get(f"{ANDROID_NS}imeOptions"),
                f"{rel_path}: {confirm_id} must declare android:imeOptions='actionDone'",
            )

    def test_editor_action_helper_does_not_throw_npe_on_null_key_event(self):
        text = read_text(
            "app/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/EditorActionHelper.kt"
        )
        self.assertNotIn("NullPointerException", text)
        self.assertIn("val keyEvent = event ?: return false", text)


class ManualEntryAndDialogFixTest(unittest.TestCase):
    def test_manual_entry_dialog_refreshes_tags_for_hotp_and_validates_motp_secret_bounds(self):
        text = read_text(
            "app/src/main/kotlin/org/shadowice/flocke/andotp/Dialogs/ManualEntryDialog.kt"
        )
        self.assertEqual(
            3,
            text.count("callingActivity.refreshTags()"),
            "TOTP/STEAM, HOTP, and MOTP branches must all refresh the tag drawer",
        )
        self.assertIn("type == Entry.OTPType.MOTP", text)
        self.assertIn("InputPolicy.MAX_SECRET_BYTES", text)
        self.assertIn("ContextCompat.getColor(callingActivity, R.color.colorPrimary)", text)

    def test_manual_entry_dialog_reenables_fields_when_leaving_steam(self):
        text = read_text(
            "app/src/main/kotlin/org/shadowice/flocke/andotp/Dialogs/ManualEntryDialog.kt"
        )
        totp_branch = text.split("else if (type == Entry.OTPType.TOTP)", 1)[1].split(
            "else if (type == Entry.OTPType.HOTP)", 1
        )[0]
        self.assertIn("digitsInput.isEnabled = true", totp_branch)
        self.assertIn("periodInput.isEnabled = true", totp_branch)


class LifecycleAndVaultSessionGuardTest(unittest.TestCase):
    def test_main_activity_persists_in_flight_auth_and_intro_generations(self):
        text = read_text(
            "app/src/main/kotlin/org/shadowice/flocke/andotp/Activities/MainActivity.kt"
        )
        for token in (
            "STATE_AUTH_PENDING",
            "STATE_AUTH_GENERATION",
            "STATE_INTRO_PENDING",
            "STATE_INTRO_GENERATION",
        ):
            self.assertIn(token, text, f"MainActivity must persist {token} across recreation")

    def test_main_activity_single_tag_drawer_keeps_all_tags_and_no_tags_consistent(self):
        text = read_text(
            "app/src/main/kotlin/org/shadowice/flocke/andotp/Activities/MainActivity.kt"
        )
        self.assertIn(
            "if (settings!!.getTagFunctionality() != Constants.TagFunctionality.SINGLE &&\n"
            "                    drawerAdapter.allTagsActive())",
            text,
        )

    def test_settings_clear_keystore_guards_on_vault_locked_and_clears_orphaned_backup_password(self):
        text = read_text(
            "app/src/main/kotlin/org/shadowice/flocke/andotp/Activities/SettingsActivity.kt"
        )
        self.assertIn("if (clearingKeyStore) return", text)
        self.assertIn(".remove(getString(R.string.settings_key_backup_password_enc))", text)
        self.assertIn("VaultSession.activate(key, unlockAttempt.generation)", text)

    def test_password_encrypted_preference_loads_keystore_dynamically(self):
        text = read_text(
            "app/src/main/kotlin/org/shadowice/flocke/andotp/Preferences/PasswordEncryptedPreference.kt"
        )
        self.assertNotIn("private val key: KeyPair?", text)
        self.assertIn("loadOrGenerateKey()", text)
        self.assertIn("loadExistingKey()", text)

    def test_backup_activity_preserves_binary_openpgp_and_running_task_type(self):
        text = read_text(
            "app/src/main/kotlin/org/shadowice/flocke/andotp/Activities/BackupActivity.kt"
        )
        self.assertIn("result.rawPayload", text)
        self.assertIn("STATE_CURRENT_TASK", text)
        self.assertIn("activePasswordDialog?.dismiss()", text)

    def test_entries_adapter_guards_transient_dialogs_and_re_resolves_hidden_entry_index(self):
        text = read_text(
            "app/src/main/kotlin/org/shadowice/flocke/andotp/View/EntriesCardAdapter.kt"
        )
        self.assertIn("ManualEntryDialog.dismissActive()", text)
        self.assertIn("activeQrDialog?.dismiss()", text)
        self.assertIn("val currentPos = presentation.indexOf(entry)", text)
        self.assertIn("override fun onViewRecycled(holder: EntryViewHolder)", text)

    def test_encrypted_backup_worker_guards_zero_message_id(self):
        text = read_text(
            "app/src/main/kotlin/org/shadowice/flocke/andotp/Tasks/EncryptedBackupWorker.kt"
        )
        self.assertIn(
            "val resolvedMessageId = if (messageId != 0) messageId else R.string.backup_receiver_unexpected_error",
            text,
        )


if __name__ == "__main__":
    unittest.main()
