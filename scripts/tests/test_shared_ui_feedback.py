"""Structural contracts for shared feedback; Kotlin gates separately type-check the hosts.

These do not execute Compose or assert runtime layout/accessibility behavior.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
UI = ROOT / "ui/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault"
FOUNDATION = ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault"


class SharedUiFeedbackTest(unittest.TestCase):
    def test_email_adapters_forward_host_error_text(self):
        for name in ("EmailSettingsDialog", "EmailPasswordDialog"):
            with self.subTest(name=name):
                text = (UI / (name + ".kt")).read_text()
                self.assertIn("errorText: String? = null", text)
                self.assertIn("errorText = errorText,", text)
        host = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt").read_text()
        for name in ("EmailSettingsComposeView", "EmailPasswordComposeView"):
            body = host.split("class " + name + "(", 1)[1].split("\n/**", 1)[0]
            self.assertIn("failed = errorText != null", body)
            self.assertIn("errorText = errorText", body)
            self.assertIn("else errorText = error", body)

    def test_desktop_notice_uses_shared_banner(self):
        screen = (UI / "VaultScreen.kt").read_text()
        self.assertIn("toast = toast,", screen)
        self.assertIn("onDismissToast = onDismissToast,", screen)
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        self.assertIn("toast = copyNotice.orEmpty()", host)
        self.assertIn("onDismissToast = { copyNotice = null }", host)
        self.assertNotIn('EntryConfirmationDialog(title = "Copy", message = message', host)

    def test_revealed_non_countdown_code_keeps_counter(self):
        text = (FOUNDATION / "SharedVaultComponents.kt").read_text()
        code_branch = text.split("displayedToken != null ->", 1)[1].split("pinRequired ->", 1)[0]
        self.assertIn("if (!showCountdown && tokenVisible && !counterText.isNullOrEmpty())", code_branch)
        self.assertIn("text = counterText", code_branch)

    def test_android_tools_share_groups_and_close_before_dispatch(self):
        host = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt").read_text()
        tools = host.split("class VaultToolsComposeView(", 1)[1]
        self.assertIn("SharedVaultToolsDialog(", tools)
        self.assertIn("vaultToolGroups(actions, strings)", tools)
        self.assertLess(tools.index("onDismiss()"), tools.index("tool.invoke(actions)"))
        self.assertIn("vaultToolGroups(actions, strings)", (UI / "VaultToolsBar.kt").read_text())
        main = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        self.assertIn("id == R.id.action_tools", main)
        body = main.split("private fun openVaultToolsDialog()", 1)[1].split("private fun openStorageLocationDialog()", 1)[0]
        self.assertIn("VaultSession.isCurrent(generation)", body)
        self.assertIn("onExportArchives = { openArchiveExportDialog() }", body)
        self.assertIn("onImportUri = { openUriImportDialog() }", body)
        self.assertEqual(2, main.count("toolsPrompt?.dismiss()\n        toolsPrompt = null"))
        self.assertEqual(2, main.count("storagePrompt?.dismiss()\n        storagePrompt = null"))

    def test_archive_tool_is_independent_of_csv_and_rechecks_reveal(self):
        main = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        body = main.split("private fun openArchiveExportDialog()", 1)[1].split("private fun openCardDisplayDialog(", 1)[0]
        self.assertNotIn("PasswordCsvExport", body)
        self.assertIn("it.isPasswordArchive() && current.isEntryRevealed(it)", body)
        self.assertIn("VaultSession.isCurrent(generation)", body)
        self.assertIn("current.isEntryRevealed(archive)", body)

    def test_tools_handoffs_are_fresh_only_and_allowlisted(self):
        base = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities"
        backup = (base / "BackupActivity.kt").read_text()
        settings = (base / "SettingsActivity.kt").read_text()
        self.assertIn("if (savedInstanceState == null) requestedVaultTool() else null", backup)
        self.assertIn("takeIf { backupTypeForPickerRequest(it) != null }", backup)
        self.assertIn("if (savedInstanceState == null) launchRequestedVaultTool()", settings)
        for host in (backup, settings):
            self.assertIn("VaultSession.isCurrent(generation)", host)
            self.assertIn("EXTRA_VAULT_TOOL_GENERATION)?.toLongOrNull()", host)
        main = (base / "MainActivity.kt").read_text()
        for callback in ("onExportEncrypted", "onRestoreEncrypted", "onExportOpenPgp",
                         "onImportOpenPgp", "onExportPreferences", "onImportPreferences"):
            self.assertIn(callback + " = { launch", main)

    def test_preferences_export_disclosure_is_shared_by_all_builds(self):
        dialog = (FOUNDATION / "PreferencesExportDialog.kt").read_text()
        self.assertIn("allowBackupPassword: Boolean = false", dialog)
        self.assertIn("mutableStateOf(false)", dialog)
        self.assertIn("allowBackupPassword && includeBackupPassword", dialog)
        self.assertIn("includeBackupPassword = false", dialog)
        android = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt").read_text()
        self.assertIn("PreferencesExportDialog(", android)
        self.assertIn("allowBackupPassword = true", android)
        desktop = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        self.assertIn("PreferencesExportDialog(", desktop)
        ios = (ROOT / "ui-foundation/src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PortableVaultViewController.kt").read_text()
        self.assertIn('"prefsExportConfirm" -> PreferencesExportDialog(', ios)
        swift = (ROOT / "ios/App/Sources/ComposeVaultHost.swift").read_text()
        action = swift.split('case "prefsExport":', 1)[1].split('case "biometric":', 1)[0]
        self.assertIn('openSurface("prefsExportConfirm")', action)
        self.assertNotIn("PreferencesExport.export", action)
        self.assertIn('"autoLock", "prefsExportConfirm", "prefsConfirm"', swift)

    def test_restore_mode_selection_is_shared_and_merge_by_default(self):
        mode = (FOUNDATION / "RestoreModeDialog.kt").read_text()
        self.assertIn("mutableStateOf(false)", mode)
        self.assertIn("Warning: Restore will delete all current entries", mode)
        self.assertIn("if (!busy) onReplaceChange(true)", mode)
        for name in ("EncryptedRestoreDialog.kt", "OpenPgpImportDialog.kt"):
            form = (FOUNDATION / name).read_text()
            self.assertIn("RestoreModeSelection(replace = replace, busy = busy", form)
            self.assertNotIn("Selected: Replace all current entries", form)
        backup = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/BackupActivity.kt").read_text()
        self.assertIn("chooseRestoreMode(Constants.INTENT_BACKUP_OPEN_DOCUMENT_CRYPT)", backup)
        self.assertIn("chooseRestoreMode(Constants.INTENT_BACKUP_OPEN_DOCUMENT_PGP)", backup)
        self.assertIn("chooseRestoreMode(requestedTool)", backup)
        self.assertEqual(2, backup.count("activeRestoreModeDialog?.dismiss()\n        activeRestoreModeDialog = null"))
        self.assertIn("ownsPrompt && VaultSession.isCurrent(generation) && canOperate()", backup)

    def test_backup_overview_is_visible_compose_with_shared_group_renderer(self):
        import xml.etree.ElementTree as ET
        backup = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/BackupActivity.kt").read_text()
        self.assertIn("BackupOverviewComposeView(this)", backup)
        self.assertIn("VaultSession.isCurrent(overviewGeneration)", backup)
        self.assertIn("ViewGroup.LayoutParams.MATCH_PARENT", backup)
        self.assertIn("finally { publishBackupOverview() }", backup)
        self.assertIn("!canOperate() || !allowExit || pendingPickerRequestCode != null", backup)
        xml = ET.parse(ROOT / "app/src/main/res/layout/content_backup.xml").getroot()
        self.assertEqual("FrameLayout", xml.tag)
        self.assertEqual("gone", xml[0].get("{http://schemas.android.com/apk/res/android}visibility"))
        screen = (FOUNDATION / "SharedBackupScreen.kt").read_text()
        tools = (FOUNDATION / "SharedVaultToolsDialog.kt").read_text()
        self.assertIn("SharedVaultToolGroups(", screen)
        self.assertIn("SharedVaultToolGroups(groups, message, onSelectTool)", tools)
        self.assertIn("if (busy) LinearProgressIndicator()", screen)

    def test_hardware_management_is_visible_compose_and_clears_credentials(self):
        import xml.etree.ElementTree as ET
        xml = ET.parse(ROOT / "app/src/main/res/layout/activity_hardware_keys.xml").getroot()
        self.assertEqual("FrameLayout", xml.tag)
        self.assertEqual("gone", xml[0].get("{http://schemas.android.com/apk/res/android}visibility"))
        screen = (FOUNDATION / "SharedHardwareKeysScreen.kt").read_text()
        self.assertIn("PasswordVisualTransformation()", screen)
        self.assertNotIn("rememberSaveable", screen)
        self.assertIn('password = ""', screen)
        self.assertIn("submitted.fill", screen)
        self.assertIn("SharedVaultToolGroups(", screen)
        activity = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/HardwareKeyActivity.kt").read_text()
        self.assertIn("HardwareKeysComposeView(this, ::dispatchHardwarePageAction)", activity)
        self.assertIn("!sessionReady() || deviceBusy || composeDialog != null || dialog != null", activity)
        self.assertIn('"account:$listRevision:$index"', activity)
        self.assertIn("hardwarePage?.close()", activity)
        self.assertIn("hardwarePage?.clear()", activity)

    def test_hardware_unlock_uses_desktop_form_with_android_transport_notice(self):
        import xml.etree.ElementTree as ET
        root = ET.parse(ROOT / "app/src/main/res/layout/activity_hardware_unlock.xml").getroot()
        self.assertEqual("ScrollView", root.tag)
        self.assertEqual("gone", root[0].get("{http://schemas.android.com/apk/res/android}visibility"))
        dialog = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/HardwareUnlockDialog.kt").read_text()
        self.assertIn("transportNotice: String", dialog)
        self.assertIn("Text(text = transportNotice)", dialog)
        self.assertIn("remember(inputKey)", dialog)
        self.assertNotIn("rememberSaveable", dialog)
        activity = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/HardwareUnlockActivity.kt").read_text()
        self.assertIn("HardwareUnlockComposeView(", activity)
        self.assertIn("R.string.hardware_connect", activity)
        self.assertIn("ownsAttempt && foreground && success", activity)
        self.assertIn("closeUnlockForm()", activity)
        self.assertIn("old?.close()", activity)
        self.assertIn("if (!foreground || !sessionReady()", activity)
        self.assertNotIn("putExtra(", activity)

    def test_settings_overview_keeps_native_only_preferences_accessible(self):
        import xml.etree.ElementTree as ET
        base = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities"
        settings = (base / "SettingsActivity.kt").read_text()
        self.assertIn("SettingsOverviewComposeView(this, ::dispatchSettingsOverviewAction)", settings)
        self.assertIn(".replace(R.id.settings_legacy_content, settingsFragment)", settings)
        self.assertIn("STATE_ADVANCED_SETTINGS, showingAdvancedSettings", settings)
        self.assertIn("returnToSettingsOverview()", settings)
        self.assertIn("overviewGeneration = VaultSession.generation()", settings)
        self.assertIn("VaultSession.getKey() === encryptionKey", settings)
        for dialog in ("CardDisplayComposeView", "AutoLockComposeView", "MultiChoiceSelectionComposeView"):
            self.assertIn(dialog + "(", settings)
        self.assertIn("settingsOverview?.close()", settings)
        self.assertIn("showingAdvancedSettings || settingsConfirmationPrompt != null", settings)
        layout = ET.parse(ROOT / "app/src/main/res/layout/content_settings.xml").getroot()
        self.assertEqual("FrameLayout", layout.tag)
        self.assertEqual("gone", layout[0].get("{http://schemas.android.com/apk/res/android}visibility"))
        screen = (FOUNDATION / "SharedBackupScreen.kt").read_text()
        self.assertIn("fun SharedVaultToolsScreen(", screen)
        host = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt").read_text()
        settings_host = host.split("class SettingsOverviewComposeView(", 1)[1]
        self.assertIn("SharedVaultToolsScreen(", settings_host)
        self.assertIn("if (closed || busy", settings_host)

    def test_tap_preferences_use_shared_single_choice_with_guarded_typed_persistence(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        section = settings.split("private fun showSharedTapAction", 1)[1].split("private fun showSharedSearchFields", 1)[0]
        self.assertIn("SingleChoiceSelectionComposeView(", section)
        self.assertIn("labels.size != modes.size", section)
        self.assertIn("VaultSession.isCurrent(generation)", section)
        self.assertIn("applyTapAction(this, doubleTap, modes[index])", section)
        for key in ("singleTap", "doubleTap"):
            self.assertIn('PortableToolItem("' + key + '"', settings)
        shared = (FOUNDATION / "MultiChoiceSelectionDialog.kt").read_text()
        self.assertIn("fun SingleChoiceSelectionDialog(", shared)
        self.assertIn("remember(inputKey)", shared)
        self.assertIn("selected in items.indices", shared)
        host = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt").read_text().split("class SingleChoiceSelectionComposeView(", 1)[1]
        self.assertIn("consumed || dismissals > 0 || index !in choices.indices", host)
