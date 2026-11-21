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

        main = MAIN.read_text(encoding="utf-8")
        for view_cls in (
            "HideableNoticeComposeView(",
            "EmailImportPasswordComposeView(",
            "EntryCopyComposeView(",
            "MultiChoiceSelectionComposeView(",
            "DeleteConfirmationComposeView(",
            "EntryDetailsComposeView(",
        ):
            self.assertIn(view_cls, main)
        for dismiss in (
            "passwordImportPrompt?.dismiss()",
            "autoTimePrompt?.dismiss()",
            "emailExportSelectionDialog?.dismiss()",
            "thunderbirdExportSelectionDialog?.dismiss()",
            "passwordExportPrompt?.dismiss()",
            "usedTokensPrompt?.dismiss()",
        ):
            self.assertIn(dismiss, main)

        settings = SETTINGS.read_text(encoding="utf-8")
        self.assertIn("EntryCopyComposeView(", settings)
        self.assertIn("DeleteConfirmationComposeView(", settings)
        self.assertIn("dismissSettingsPrompts()", settings)

        backup = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/BackupActivity.kt").read_text(encoding="utf-8")
        self.assertIn("private var activeKeyFileDialog: ComposeDialogHost? = null", backup)
        self.assertIn("EntryCopyComposeView(", backup)

        views = HOST.read_text(encoding="utf-8")
        self.assertIn("class MultiChoiceSelectionComposeView(", views)
        self.assertIn("class QrCodeComposeView(", views)

    def test_shared_ui_module_comment_matches_the_current_platform_boundary(self):
        gradle = UI_GRADLE.read_text(encoding="utf-8")
        self.assertNotRegex(gradle, re.compile(r"iOS currently uses SwiftUI", re.IGNORECASE))
        self.assertIn("iOS uses the native-compatible :ui-foundation", gradle)


if __name__ == "__main__":
    unittest.main()
