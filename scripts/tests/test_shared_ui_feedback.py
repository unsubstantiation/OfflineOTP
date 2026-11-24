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

    def test_screen_privacy_uses_shared_form_without_changing_other_checklist_defaults(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        form = settings.split("private fun showSharedScreenPrivacy", 1)[1].split("private fun showSharedTapAction", 1)[0]
        for marker in ("MultiChoiceSelectionComposeView(", "allowEmpty = true", "indices.all { it in 0..2 }",
                       "settingsConfirmationPrompt === host", "VaultSession.isCurrent(generation)",
                       "applyScreenPrivacy(this, ScreenPrivacySelection(", "applyScreenshotPolicyForForeground()"):
            self.assertIn(marker, form)
        shared = (FOUNDATION / "MultiChoiceSelectionDialog.kt").read_text()
        self.assertIn("allowEmpty: Boolean = false", shared)
        self.assertIn("enabled = allowEmpty || selected.isNotEmpty()", shared)
        adapter = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/AndroidPreferencesPort.kt").read_text()
        policy = adapter.split("fun Settings.applyScreenPrivacy", 1)[1].split("enum class AppearancePreference", 1)[0]
        for key in ("enable_screenshot", "hide_in_app_switcher", "relock_screen_off"):
            self.assertIn("R.string.settings_key_" + key, policy)
        self.assertEqual(1, policy.count(".commit()"))

    def test_shared_appearance_choices_preserve_wire_values_and_recreation_ownership(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        form = settings.split("private fun showSharedAppearance", 1)[1].split("private fun showSharedScreenPrivacy", 1)[0]
        for marker in ("SingleChoiceSelectionComposeView(", "labels.size != values.size", "values.distinct()",
                       "VaultSession.isCurrent(generation)", "applyAppearanceValue(this, preference, values[index])"):
            self.assertIn(marker, form)
        self.assertLess(form.index("closeHost()", form.index("onConfirm =")), form.index("applyAppearanceValue"))
        for key in ("language", "themeMode", "theme", "blackAuto"):
            self.assertIn('PortableToolItem("' + key + '"', settings)
        adapter = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/AndroidPreferencesPort.kt").read_text()
        self.assertIn("value !in context.resources.getStringArray(preference.valuesId)", adapter)
        self.assertIn('editor.putBoolean(context.getString(preference.keyId), value == "true")', adapter)

    def test_reveal_and_input_protection_use_shared_forms_with_platform_bounds(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        numeric = settings.split("private fun showSharedRevealTimeout", 1)[1].split("private fun showSharedInputProtection", 1)[0]
        for marker in ("NumericPreferenceComposeView(", "R.integer.settings_min_tap_to_reveal_timeout",
                       "R.integer.settings_max_tap_to_reveal_timeout", "VaultSession.isCurrent(generation)",
                       "applyRevealTimeout(this, seconds)"):
            self.assertIn(marker, numeric)
        protection = settings.split("private fun showSharedInputProtection", 1)[1].split("private fun showSharedAppearance", 1)[0]
        for marker in ("MultiChoiceSelectionComposeView(", "allowEmpty = true", "indices.all { it in 0..2 }",
                       "VaultSession.isCurrent(generation)", "InputProtectionSelection(0 in indices, 1 in indices, 2 in indices)"):
            self.assertIn(marker, protection)
        host = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt").read_text().split("class NumericPreferenceComposeView(", 1)[1]
        self.assertIn("EntryCounterDialog(", host)
        self.assertIn("text.toIntOrNull() ?: return", host)
        self.assertIn("consumed || dismissals > 0 || value !in minimum..maximum", host)

    def test_shared_backup_preferences_keep_retention_warning_and_platform_persistence(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        form = settings.split("private fun showSharedBackupRetention", 1)[1].split("private fun showSharedRevealTimeout", 1)[0]
        for marker in ("SingleChoiceSelectionComposeView(", "message = getString(R.string.settings_desc_auto_backup_keep)",
                       "VaultSession.isCurrent(generation)", "applyBackupRetention(this, requireNotNull(values[index]))"):
            self.assertIn(marker, form)
        files = settings.split("private fun showSharedBackupFiles", 1)[1].split("private fun showSharedBackupRetention", 1)[0]
        self.assertIn("allowEmpty = true", files)
        self.assertIn("indices.all { it in 0..1 }", files)
        self.assertIn("BackupFileSelection(0 in indices, 1 in indices)", files)
        adapter = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/AndroidPreferencesPort.kt").read_text()
        retention = adapter.split("fun Settings.applyBackupRetention", 1)[1]
        self.assertIn("keep !in BackupRetention.CHOICES", retention)
        self.assertIn(".putString(context.getString(R.string.settings_key_auto_backup_keep), keep.toString())", retention)
        self.assertNotIn(".delete", retention)
        shared = (FOUNDATION / "MultiChoiceSelectionDialog.kt").read_text().split("fun SingleChoiceSelectionDialog", 1)[1]
        self.assertIn('message: String = ""', shared)
        self.assertIn("if (message.isNotEmpty()) Text(text = message)", shared)

    def test_vault_behavior_and_tag_mode_use_shared_forms_and_typed_values(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        behavior = settings.split("private fun showSharedVaultBehavior", 1)[1].split("private fun showSharedTagBehavior", 1)[0]
        for marker in ("MultiChoiceSelectionComposeView(", "allowEmpty = true", "indices.all { it in 0..2 }",
                       "VaultSession.isCurrent(generation)", "VaultBehaviorSelection(0 in indices, 1 in indices, 2 in indices)"):
            self.assertIn(marker, behavior)
        tags = settings.split("private fun showSharedTagBehavior", 1)[1].split("private fun showSharedBackupFiles", 1)[0]
        for marker in ("SingleChoiceSelectionComposeView(", "Constants.TagFunctionality.values().firstOrNull",
                       "values.any { it == null }", "VaultSession.isCurrent(generation)", "applyTagBehavior(this, requireNotNull(values[index]))"):
            self.assertIn(marker, tags)
        adapter = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/AndroidPreferencesPort.kt").read_text()
        self.assertIn("mode: Constants.TagFunctionality", adapter)
        for key in ("minimize_on_copy", "focus_search_on_start", "hide_global_timeout"):
            self.assertIn("R.string.settings_key_" + key, adapter)

    def test_openpgp_overview_uses_shared_engine_form_and_native_key_adapters(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        form = settings.split("private fun showSharedOpenPgpMode", 1)[1].split("private fun showSharedVaultBehavior", 1)[0]
        for marker in ("SingleChoiceSelectionComposeView(", "OpenPgpMode.preferenceValue(it) == raw",
                       "modes.any { it == null }", "VaultSession.isCurrent(generation)",
                       "applyOpenPgpMode(this, requireNotNull(modes[index]))"):
            self.assertIn(marker, form)
        self.assertIn('"pgpRecipients" -> if (settings!!.getOpenPGPMode() == OpenPgpMode.KEY_FILE) requestOpenPgpKeyFiles(true)', settings)
        self.assertIn('"pgpSecret" -> if (settings!!.getOpenPGPMode() == OpenPgpMode.KEY_FILE) requestOpenPgpKeyFiles(false)', settings)
        self.assertIn('if (key == "pgpProvider" && settings!!.getOpenPGPMode() != OpenPgpMode.PROVIDER) return', settings)
        adapter = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/AndroidPreferencesPort.kt").read_text().split("fun Settings.applyOpenPgpMode", 1)[1]
        self.assertIn("mode: OpenPgpMode", adapter)
        self.assertIn("OpenPgpMode.preferenceValue(mode)", adapter)
        self.assertNotIn(".remove(", adapter)

    def test_provider_options_have_common_form_and_identity_guarded_save(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        form = settings.split("private fun showSharedOpenPgpProviderOptions", 1)[1].split("private fun showSharedOpenPgpMode", 1)[0]
        for marker in ("OpenPgpProviderOptionsComposeView(", "VaultSession.isCurrent(generation)",
                       "settings!!.getOpenPGPProvider() == provider", "OpenPgpMode.PROVIDER",
                       "applyOpenPgpProviderOptions(this, recipients, verify)"):
            self.assertIn(marker, form)
        common = (FOUNDATION / "OpenPgpProviderOptionsDialog.kt").read_text()
        self.assertIn("remember(inputKey)", common)
        self.assertIn("PortableInputBounds.MAX_TEXT_CHARS", common)
        self.assertIn("Checkbox(checked = verify", common)
        host = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt").read_text().split("class OpenPgpProviderOptionsComposeView(", 1)[1]
        self.assertIn("consumed || dismissals > 0", host)
        adapter = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/AndroidPreferencesPort.kt").read_text().split("fun Settings.applyOpenPgpProviderOptions", 1)[1]
        self.assertIn("recipients.length > InputPolicy.MAX_TEXT_CHARS", adapter)
        self.assertIn(".putBoolean(context.getString(R.string.settings_key_openpgp_verify), verify)", adapter)

    def test_auto_backup_mode_keeps_off_available_and_rechecks_readiness(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        form = settings.split("private fun showSharedAutoBackupMode", 1)[1].split("private fun showSharedOpenPgpProviderOptions", 1)[0]
        self.assertIn("SingleChoiceSelectionComposeView(", form)
        self.assertIn("ready || values[it] == Constants.AutoBackup.OFF", form)
        self.assertIn("VaultSession.isCurrent(generation)", form)
        adapter = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/AndroidPreferencesPort.kt").read_text().split("fun Settings.applyAutoBackupMode", 1)[1]
        self.assertIn("mode != Constants.AutoBackup.OFF && BackupHelper.autoBackupType(context) != Constants.BackupType.ENCRYPTED", adapter)
        common = (FOUNDATION / "MultiChoiceSelectionDialog.kt").read_text().split("fun SingleChoiceSelectionDialog", 1)[1]
        self.assertIn("enabledIndices == null || selected in enabledIndices", common)
        host = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt").read_text().split("class SingleChoiceSelectionComposeView(", 1)[1]
        self.assertIn("permittedIndices != null && index !in permittedIndices", host)

    def test_policy_checklists_only_configure_allowlisted_future_actions(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        form = settings.split("private fun showSharedPolicyChecklist", 1)[1].split("private fun showSharedAutoBackupMode", 1)[0]
        for marker in ("MultiChoiceSelectionComposeView(", "values.toSet() != policy.allowed", "allowEmpty = true",
                       "VaultSession.isCurrent(generation)", "NotificationHelper.requestNotificationPermission(it)",
                       "else if (!panic && chosen.isNotEmpty())"):
            self.assertIn(marker, form)
        self.assertNotIn("wipeDatabase", form)
        self.assertNotIn("sendBroadcast", form)
        adapter = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/AndroidPreferencesPort.kt").read_text().split("fun Settings.applyPolicyChecklist", 1)[1]
        self.assertIn("values.any { it !in policy.allowed }", adapter)
        self.assertIn(".putStringSet", adapter)
        self.assertNotIn(".clear()", adapter)
        strings = (ROOT / "app/src/main/res/values/strings_settings.xml").read_text()
        self.assertIn("Saving does not run them now", strings)

    def test_setup_encryption_and_platform_backup_steps_use_common_compose(self):
        intro = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/IntroScreenActivity.kt").read_text()
        encryption = intro.split("open class EncryptionFragment", 1)[1].split("open class IntroFinishedFragment", 1)[0]
        self.assertIn("SetupChoiceComposeView(", encryption)
        self.assertIn("renderSharedStep()", encryption)
        self.assertIn("sharedStep?.close()", encryption)
        self.assertIn("spinner.setSelection(index)", encryption)
        self.assertIn("STATE_ENCRYPTION_SELECTION", encryption)
        sync = intro.split("open class AndroidSyncFragment", 1)[1].split("open class AuthenticationFragment", 1)[0]
        self.assertIn("SetupChoiceComposeView(", sync)
        self.assertIn("emptyList(), -1", sync)
        self.assertIn("toggle.isEnabled = false", sync)
        shared = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/SharedSetupChoiceScreen.kt").read_text()
        self.assertIn("fun SharedSetupChoiceScreen(", shared)
        self.assertNotIn("android.", shared)

    def test_setup_completion_uses_shared_body_and_releases_cancelled_restore(self):
        import xml.etree.ElementTree as ET
        intro = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/IntroScreenActivity.kt").read_text()
        completion = intro.split("open class IntroFinishedFragment", 1)[1].split("open class AndroidSyncFragment", 1)[0]
        for marker in ("SetupCompletionComposeView(", "publishCompletion()", "sharedCompletion?.close()",
                       "canGoForward || completedSetupRestored", "progress?.visibility == View.VISIBLE || activeTask != null"):
            self.assertIn(marker, completion)
        self.assertIn("R.string.intro_restore_cancelled_choose", intro)
        self.assertIn("R.string.intro_restore_cancelled_unchanged", intro)
        layout = ET.parse(ROOT / "app/src/main/res/layout/component_intro_finished.xml").getroot()
        self.assertEqual("FrameLayout", layout.tag)
        self.assertEqual("gone", layout[0].get("{http://schemas.android.com/apk/res/android}visibility"))
        common = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/SharedSetupChoiceScreen.kt").read_text()
        self.assertIn("fun SharedSetupCompletionScreen(", common)
        self.assertIn("if (!completed)", common)
        host = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt").read_text().split("class SetupCompletionComposeView(", 1)[1]
        self.assertIn("if (closed || busy || completed) return", host)
        self.assertLess(host.index("busy = true", host.index("fun selectAction")), host.index("if (restore) onRestore()"))

    def test_onboarding_credentials_are_shared_non_saved_and_disposed(self):
        import xml.etree.ElementTree as ET
        intro = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/IntroScreenActivity.kt").read_text()
        auth = intro.split("open class AuthenticationFragment", 1)[1]
        for marker in ("SetupCredentialsComposeView(", "sharedCredentials?.close()", "sharedCredentials?.clearCredentials()",
                       "publishCredentials()", "passwordInput = null; passwordConfirm = null"):
            self.assertIn(marker, auth)
        state = auth.split("override fun onSaveInstanceState", 1)[1].split("override fun onCreateView", 1)[0]
        self.assertNotIn("getPassword()", state)
        self.assertNotIn("putString(\"password", state)
        common = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/SharedSetupCredentialsScreen.kt").read_text()
        self.assertIn("fun SharedSetupCredentialsScreen(", common)
        self.assertIn("KeyboardType.Password", common)
        self.assertNotIn("rememberSaveable", common)
        layout = ET.parse(ROOT / "app/src/main/res/layout/component_intro_authentication.xml").getroot()
        self.assertEqual("gone", layout[0][0].get("{http://schemas.android.com/apk/res/android}visibility"))
        host = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt").read_text().split("class SetupCredentialsComposeView(", 1)[1]
        self.assertIn("renderedEpoch == epoch", host)
        self.assertIn("epoch by mutableStateOf(0)", host)
        self.assertIn('password = ""; confirmation = ""', host)

    def test_authentication_has_shared_layout_with_the_existing_native_input_adapter(self):
        import xml.etree.ElementTree as ET
        auth = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/AuthenticateActivity.kt").read_text()
        for marker in ("AuthenticationComposeView(", "legacy.removeView(field)", "publishAuthentication()",
                       "sharedAuthentication?.close()", "result.getOrNull()?.fill(0)",
                       "!authForeground || authTaskRunning || biometricPending || hardwarePending"):
            self.assertIn(marker, auth)
        for marker in ("input.isSaveEnabled = false", "setAutoFillTextListener(autoFillTextListener)",
                       "IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS", "IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS"):
            self.assertIn(marker, auth)
        common = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/SharedAuthenticationScreen.kt").read_text()
        self.assertIn("credentialInput: @Composable () -> Unit", common)
        self.assertNotIn("password: String", common)
        host = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt").read_text().split("class AuthenticationComposeView(", 1)[1]
        self.assertIn("AndroidView(factory = { field })", host)
        self.assertIn("closed || !foreground || busy", host)
        layout = ET.parse(ROOT / "app/src/main/res/layout/content_authenticate.xml").getroot()
        self.assertEqual("FrameLayout", layout.tag)
        self.assertEqual("gone", layout[0].get("{http://schemas.android.com/apk/res/android}visibility"))
