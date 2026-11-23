"""Guard the Android dialogs that use the shared Compose forms across :ui-foundation and :ui.

Entry action dialogs (delete, mOTP PIN, HOTP counter, thumbnail picker), entry detail/copy/tag
prompts (SSH field copy, FairEmail connection details, password details, tag picker), and
MainActivity prompts (automatic-time warning, password import mode chooser, FairEmail encrypted
backup password prompt, and biometric enrollment) share Compose forms rather than duplicating
native AlertDialog/EditText builders. Android retains session checks, keychain/SAF adapters, and
lock-time host dismissal.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HOST = ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt"
SETTINGS = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt"
ACTIONS = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/View/EntryActionDialogs.kt"
CONTROLLER = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/View/AndroidVaultController.kt"
MAIN = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt"
UI_GRADLE = ROOT / "ui/build.gradle"


def function_body(text: str, signature: str, closing: str) -> str:
    start = text.index(signature)
    end = text.index(closing, start)
    return text[start:end]


class AndroidSharedDialogTest(unittest.TestCase):
    def test_android_hosts_render_the_shared_forms(self):
        host = HOST.read_text(encoding="utf-8")
        for form in (
            "BiometricEnableDialog(",
            "EntryConfirmationDialog(",
            "SharedEntryPinDialog(",
            "EntryCounterDialog(",
            "ThumbnailPickerDialog(",
            "TagsDialog(",
            "HideableDialog(",
            "EntryCopyDialog(",
            "EmailDetailsDialog(",
            "EntryDetailsDialog(",
            "EmailImportDialog(",
            "PasswordImportDialog(",
            "PasswordExportDialog(",
            "ArchiveExportDialog(",
            "PreferencesImportDialog(",
        ):
            self.assertIn(form, host)
        self.assertIn("ForestTheme {", host)
        self.assertIn("ViewCompositionStrategy.DisposeOnDetachedFromWindow", host)
        self.assertIn("fun show()", host)
        self.assertIn("fun dismiss()", host)
        self.assertIn("activity.getWindow().decorView", host)
        self.assertIn("attachedTo?.removeView(this)", host)
        # A Material 3 AlertDialog opens its own window; a second Android Dialog would nest windows.
        self.assertNotIn("android.app.Dialog", host)
        self.assertNotIn("android.app.AlertDialog", host)
        form = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/BiometricEnableDialog.kt").read_text()
        self.assertNotIn("rememberSaveable", form)
        self.assertIn("password = \"\"", form)

    def test_biometric_enrollment_keeps_native_verification_and_keychain(self):
        settings = SETTINGS.read_text(encoding="utf-8")
        body = function_body(settings, "private fun promptBiometricEnrollment(", "\n        }")
        self.assertIn("BiometricEnableComposeView(", body)
        self.assertIn("verifyVaultPassword(typed)", body)
        self.assertIn("BiometricKeyStore.save(fragmentActivity, vaultKey)", body)
        self.assertIn("dismissBiometricPrompt()", body)
        self.assertIn("view.show()", body)
        self.assertNotIn("AlertDialog.Builder", body)
        self.assertNotIn("android.widget.EditText", body)
        self.assertNotIn("passwordInput", body)

    def test_delete_confirmation_is_the_shared_compose_form(self):
        actions = ACTIONS.read_text(encoding="utf-8")
        body = function_body(actions, "fun delete(", "\n    }\n")
        self.assertIn("DeleteConfirmationComposeView(", body)
        self.assertIn("view.show()", body)
        self.assertIn("confirm.run()", body)
        self.assertIn("pending.host?.dismiss()", body)
        self.assertNotIn("AlertDialog.Builder", body)

    def test_all_entry_action_dialogs_use_shared_compose_hosts(self):
        actions = ACTIONS.read_text(encoding="utf-8")
        for view_cls in (
            "CounterEntryComposeView(",
            "PinEntryComposeView(",
            "DeleteConfirmationComposeView(",
            "ThumbnailPickerComposeView(",
        ):
            self.assertIn(view_cls, actions)
        self.assertNotIn("AlertDialog.Builder", actions)
        self.assertNotIn("android.widget.EditText", actions)

    def test_vault_controller_and_main_activity_route_prompts_to_shared_compose_hosts(self):
        controller = CONTROLLER.read_text(encoding="utf-8")
        for view_cls in (
            "EntryCopyComposeView(",
            "EmailDetailsComposeView(",
            "EntryDetailsComposeView(",
            "TagsComposeView(",
            "QrCodeComposeView(",
        ):
            self.assertIn(view_cls, controller)
        self.assertIn("activeTagsDialog?.dismiss()", controller)
        self.assertIn("activeQrDialog?.dismiss()", controller)
        self.assertNotIn("AlertDialog.Builder", controller)
        self.assertNotIn("ManualEntryDialog.show(", controller)
        self.assertNotIn("Dialogs.TagsDialog", controller)
        self.assertNotIn("TagsDialog.show(", controller)

        main = MAIN.read_text(encoding="utf-8")
        for view_cls in (
            "HideableNoticeComposeView(",
            "EmailImportPasswordComposeView(",
            "PasswordImportComposeView(",
            "PasswordExportComposeView(",
            "ArchiveExportComposeView(",
            "MultiChoiceSelectionComposeView(",
            "DeleteConfirmationComposeView(",
            "EntryDetailsComposeView(",
            "UriImportComposeView(",
        ):
            self.assertIn(view_cls, main)
        for dismiss in (
            "passwordImportPrompt?.dismiss()",
            "thunderbirdImportPrompt?.dismiss()",
            "autoTimePrompt?.dismiss()",
            "emailExportSelectionDialog?.dismiss()",
            "thunderbirdExportSelectionDialog?.dismiss()",
            "passwordExportPrompt?.dismiss()",
            "usedTokensPrompt?.dismiss()",
            "uriImportPrompt?.dismiss()",
        ):
            self.assertIn(dismiss, main)
        self.assertIn("private var activeImportDialog: ComposeDialogHost? = null", main)
        self.assertNotIn("com.fairuniversal.offlineotp.Dialogs.", main)

        settings = SETTINGS.read_text(encoding="utf-8")
        self.assertIn("EntryCopyComposeView(", settings)
        self.assertIn("DeleteConfirmationComposeView(", settings)
        self.assertIn("EntryDetailsComposeView(", settings)
        self.assertIn("PreferencesImportComposeView(", settings)
        self.assertIn("StorageLocationComposeView(", settings)
        self.assertIn("dismissSettingsPrompts()", settings)
        self.assertNotIn("UIHelper.showGenericDialog", settings)

        backup = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/BackupActivity.kt").read_text(encoding="utf-8")
        self.assertIn("private var activePasswordDialog: ComposeDialogHost? = null", backup)
        self.assertIn("private var activeKeyFileDialog: ComposeDialogHost? = null", backup)
        self.assertIn("EntryCopyComposeView(", backup)
        self.assertIn("OpenPgpExportComposeView(", backup)
        self.assertIn("EncryptedRestorePasswordComposeView(", backup)
        self.assertIn("EncryptedExportComposeView(", backup)
        self.assertIn("BiometricEnableComposeView(", backup)
        self.assertNotIn("PasswordEntryDialog", backup)
        self.assertNotIn("AlertDialog", backup)

        views = HOST.read_text(encoding="utf-8")
        self.assertIn("class MultiChoiceSelectionComposeView(", views)
        self.assertIn("class QrCodeComposeView(", views)
        self.assertIn("class CheckboxConfirmationComposeView(", views)
        self.assertIn("class EncryptedRestorePasswordComposeView(", views)
        self.assertIn("class EncryptedExportComposeView(", views)
        self.assertIn("class UriImportComposeView(", views)
        self.assertIn("class PasswordImportComposeView(", views)
        self.assertIn("class PasswordExportComposeView(", views)
        self.assertIn("class ArchiveExportComposeView(", views)
        self.assertIn("class HardwareCopyComposeView(", views)
        self.assertIn("class OpenPgpExportComposeView(", views)
        self.assertIn("class StorageLocationComposeView(", views)
        self.assertIn("class PreferencesImportComposeView(", views)
        self.assertIn("class BusyProgressComposeView(", views)
        self.assertIn("class HardwareEnrollmentComposeView(", views)
        self.assertIn("class CardDisplayComposeView(", views)
        self.assertIn("class AutoLockComposeView(", views)
        self.assertIn("fun setOnDismissListener(", views)

        main_activity = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("CardDisplayComposeView(", main_activity)
        self.assertIn("AutoLockComposeView(", main_activity)
        self.assertIn("openSearchFieldsDialog()", main_activity)
        self.assertIn("cardDisplayPrompt?.dismiss()", main_activity)
        self.assertIn("searchFieldsPrompt?.dismiss()", main_activity)
        self.assertIn("autoLockPrompt?.dismiss()", main_activity)

        intro = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/IntroScreenActivity.kt").read_text(encoding="utf-8")
        self.assertIn("EncryptedRestorePasswordComposeView(", intro)
        self.assertIn("firstRunPasswordDialog?.dismiss()", intro)
        self.assertNotIn("AlertDialog", intro)

        hardware = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/HardwareKeyActivity.kt").read_text(encoding="utf-8")
        self.assertIn("HardwareCopyComposeView(", hardware)
        self.assertIn("HardwareEnrollmentComposeView(", hardware)
        self.assertIn("DeleteConfirmationComposeView(", hardware)
        self.assertIn("BusyProgressComposeView(", hardware)
        self.assertIn("dismissComposeDialog()", hardware)
        self.assertNotIn("AlertDialog", hardware)

        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text(encoding="utf-8")
        self.assertIn("BusyProgressComposeView(", settings)
        self.assertNotIn("AlertDialog", settings)

        hardware_unlock = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/HardwareUnlockActivity.kt").read_text(encoding="utf-8")
        self.assertIn("BusyProgressComposeView(", hardware_unlock)
        self.assertNotIn("AlertDialog", hardware_unlock)

    def test_shared_ui_module_comment_matches_the_current_platform_boundary(self):
        gradle = UI_GRADLE.read_text(encoding="utf-8")
        self.assertNotRegex(gradle, re.compile(r"iOS currently uses SwiftUI", re.IGNORECASE))
        self.assertIn("iOS uses the native-compatible :ui-foundation", gradle)


if __name__ == "__main__":
    unittest.main()
