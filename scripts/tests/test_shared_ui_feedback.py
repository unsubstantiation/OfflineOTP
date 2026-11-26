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
        self.assertIn("SharedVaultToolGroups(groups, message, onSelectTool = onSelectTool)", tools)
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
        self.assertIn('"pgpProvider" -> showSharedPgpProvider()', settings)
        provider = settings.split("private fun showSharedPgpProvider()", 1)[1].split("private var credentialsTask", 1)[0]
        self.assertIn("settings!!.getOpenPGPMode() != OpenPgpMode.PROVIDER", provider)
        self.assertIn("installedPgpProviders().any", provider)
        adapter = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/AndroidPreferencesPort.kt").read_text().split("fun Settings.applyOpenPgpMode", 1)[1].split("fun Settings.applyOpenPgpProviderOptions", 1)[0]
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

    def test_backup_password_source_requires_consent_and_atomic_legacy_removal(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        source = settings.split("private fun showSharedBackupPasswordSource", 1)[1].split("private fun showSharedPolicyChecklist", 1)[0]
        for marker in ("SingleChoiceSelectionComposeView(", "DeleteConfirmationComposeView(",
                       "settings!!.hasLegacyBackupPassword(this)", "LoginBackupPolicy.usesLoginPassword(this)",
                       "applyBackupPasswordSource(this, useLogin = true, removeLegacy = true)"):
            self.assertIn(marker, source)
        self.assertEqual(2, source.count("VaultSession.isCurrent(generation)"))
        self.assertEqual(2, source.count("val owned = settingsConfirmationPrompt === host"))
        adapter = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/AndroidPreferencesPort.kt").read_text().split("fun Settings.applyBackupPasswordSource", 1)[1].split("fun Settings.applySeparateBackupPassword", 1)[0]
        self.assertIn("useLogin && hasLegacyBackupPassword(context) && !removeLegacy", adapter)
        self.assertIn("useLogin && removeLegacy", adapter)
        self.assertEqual(1, adapter.count(".commit()"))
        self.assertNotIn("settings_key_backup_password_enc", adapter)

    def test_separate_backup_password_uses_shared_shell_and_protected_input(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        form = settings.split("private fun showSharedSeparateBackupPassword", 1)[1].split("private fun showSharedBackupPasswordSource", 1)[0]
        for marker in ("ConfirmedSecretPreferenceComposeView(", "ConfirmedPasswordTransformationHelper.setup",
                       "password.isSaveEnabled = false", "IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS",
                       "IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS", "VaultSession.isCurrent(generation)",
                       "applySeparateBackupPassword(this, value)", "host.setOnDismissListener", "releaseInput()"):
            self.assertIn(marker, form)
        self.assertNotIn("getBackupPasswordEnc()", form)
        adapter = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/AndroidPreferencesPort.kt").read_text().split("fun Settings.applySeparateBackupPassword", 1)[1]
        self.assertIn('if (value.isEmpty()) "" else', adapter)
        self.assertIn("EncryptionHelper.encrypt(pair.public, plain)", adapter)
        self.assertIn("finally { plain.fill(0) }", adapter)
        self.assertIn(".commit()", adapter)
        self.assertNotIn("settings_key_auto_backup_use_login", adapter)
        shared = (FOUNDATION / "ConfirmedSecretPreferenceDialog.kt").read_text()
        self.assertIn("credentialInput: @Composable () -> Unit", shared)
        self.assertNotIn("password: String", shared)

    def test_keystore_reset_has_one_guarded_shared_confirmation_for_both_routes(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        form = settings.split("private fun showSharedKeyStoreReset", 1)[1].split("private fun showSharedSeparateBackupPassword", 1)[0]
        for marker in ("DeleteConfirmationComposeView(", "settings!!.getSpecialFeatures() != true",
                       "settingsConfirmationPrompt === host", "VaultSession.isCurrent(generation)",
                       "settings!!.getEncryption() == encryption", "R.string.settings_dialog_msg_clear_keystore_password",
                       "R.string.settings_dialog_msg_clear_keystore_keystore", "clearKeyStore()"):
            self.assertIn(marker, form)
        native = settings.split("val clearKeyStore = findPreference", 1)[1].split("// Theme mode", 1)[0]
        self.assertIn("activity.showSharedKeyStoreReset()", native)
        self.assertNotIn("activity.clearKeyStore()", native)
        special = settings.split("private fun showSharedSpecialFeatures", 1)[1].split("private fun showSharedKeyStoreReset", 1)[0]
        self.assertIn("SingleChoiceSelectionComposeView(", special)
        self.assertIn("R.string.about_dialog_special_features", special)
        self.assertIn("applySpecialFeatures(this, index == 1)", special)

    def test_biometric_overview_enrollment_rechecks_ownership_at_persistence(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        self.assertIn('PortableToolItem("biometricUnlock"', settings)
        selection = settings.split("private fun showSharedBiometricSelection", 1)[1].split("private fun promptBiometricEnrollment", 1)[0]
        self.assertIn("SingleChoiceSelectionComposeView(", selection)
        self.assertIn("BiometricKeyStore.availability(this)", selection)
        enrollment = settings.split("private fun promptBiometricEnrollment", 1)[1].split("private fun verifyVaultPassword", 1)[0]
        for marker in ("host.biometricPrompt === view", "host.biometricEnrollmentRevision == revision",
                       "VaultSession.isCurrent(generation)", "permitted = { owned() }", "if (!owned()) return@save",
                       "vaultKey.fill(0)", "!owned() || pending"):
            self.assertIn(marker, enrollment)
        store = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/BiometricKeyStore.kt").read_text()
        guarded = store.split("vaultKey: ByteArray, permitted: () -> Boolean", 1)[1].split("fun load(", 1)[0]
        self.assertGreaterEqual(guarded.count("if (!permitted())"), 3)
        self.assertLess(guarded.rindex("if (!permitted())"), guarded.index(".putString(PREF_ENCRYPTED_KEY"))

    def test_visible_android_shell_and_remaining_routes_are_common(self):
        base = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities"
        main = (base / "MainActivity.kt").read_text()
        controller = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/View/AndroidVaultController.kt").read_text()
        self.assertIn("showControls = true", controller)
        for view in ("main_app_bar", "speedDialScroll", "speedDialOverlay", "speedDial", "fab_main_layout"):
            self.assertIn(f"findViewById<View>(R.id.{view})?.visibility = View.GONE", main)
        self.assertIn("setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)", main)
        actions = main.split("view.render(published.state, VaultActions(", 1)[1].split("fun showEntryCategory", 1)[0]
        for callback in ("onOpenSettings", "onHardwareOath", "onLock", "onQueryChange", "onSelectCategory",
                         "onSelectSort", "onToggleTag", "onToggleUntagged", "onClearFilters"):
            self.assertIn(callback + " =", actions)
        self.assertIn(".guarded { VaultSession.isCurrent(generation)", actions)
        self.assertNotIn("AlertDialog.Builder", main)
        settings = (base / "SettingsActivity.kt").read_text()
        self.assertNotIn('PortableToolItem("advanced"', settings)
        for key in ("authentication", "encryption", "pgpProvider", "pgpSigningKey"):
            self.assertIn(f'PortableToolItem("{key}"', settings)
        self.assertIn("pendingMigrationImport !== pending", main)
        about = (base / "AboutActivity.kt").read_text()
        self.assertIn("AboutScreenComposeView(", about)
        for field in ("artifactVersion", "licenseContent", "website"):
            self.assertIn(field, about)
        self.assertNotIn("LibsBuilder", about)

    def test_setup_shell_and_scanner_controls_are_common_with_native_adapters(self):
        intro = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/IntroScreenActivity.kt").read_text()
        self.assertIn("SetupShellComposeView(", intro)
        self.assertIn(".fragment(WelcomeFragment())", intro)
        self.assertNotIn("SimpleSlide.Builder()", intro)
        shell = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/SharedSetupShell.kt").read_text()
        self.assertIn("content: @Composable () -> Unit", shell)
        scanner = (ROOT / "ios/App/Sources/QrCameraScanner.swift").read_text()
        self.assertIn("controls.viewController(actions: self)", scanner)
        self.assertNotIn("UIButton", scanner)
        self.assertIn("scanner?.stopScanning()", scanner)

    def test_busy_shared_pages_disable_controls_as_well_as_guarding_hosts(self):
        groups = (FOUNDATION / "SharedVaultToolsDialog.kt").read_text()
        body = groups.split("fun SharedVaultToolGroups(", 1)[1]
        self.assertIn("enabled: Boolean = true", body)
        self.assertIn("enabled = enabled && item.enabled", body)
        self.assertIn(".clickable(enabled && item.enabled)", body)
        self.assertIn("if (enabled && item.enabled) onSelectTool(item.key)", body)
        for page in ("SharedBackupScreen.kt", "SharedHardwareKeysScreen.kt"):
            self.assertIn("enabled = !busy", (FOUNDATION / page).read_text())

    def test_setup_shell_busy_and_completion_state_reaches_buttons_and_pager(self):
        intro = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/IntroScreenActivity.kt").read_text()
        self.assertIn("sharedShell?.render(position, busy, setupFinished)", intro)
        self.assertIn("(hostContext as? IntroScreenActivity)?.publishSetupNavigation()", intro)
        self.assertIn("override fun canGoBackward(): Boolean = !navigationBusy() && !canGoForward && !completedSetupRestored", intro)
        shell = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/SharedSetupShell.kt").read_text()
        self.assertIn("enabled = step > 0 && !busy && !completed", shell)
        self.assertIn("enabled = !busy && (step < count - 1 || completed)", shell)
        bridge = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt").read_text().split("class SetupShellComposeView(", 1)[1]
        self.assertIn("if (closed || busy || direction !in setOf(-1, 1)) return", bridge)
        self.assertIn("step == count - 1 && !completed", bridge)

    def test_settings_policy_and_encryption_feedback_use_shared_banner(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        self.assertNotIn("Toast.makeText(this, R.string.settings_save_failed", settings)
        self.assertNotIn("Snackbar.make(snackbarAnchor(), R.string.settings_toast_encryption_", settings)
        self.assertIn("val revision = page.showNotice(message)", settings)
        self.assertIn("Runnable { page.dismissNotice(revision) }", settings)
        for marker in ("override fun onVaultLocked() {", "override fun onDestroy() {"):
            self.assertIn("clearSettingsFeedback()", settings.split(marker, 1)[1].split("\n    }", 1)[0])
        common = (FOUNDATION / "SharedVaultScreen.kt").read_text()
        self.assertIn("SharedFeedbackBanner(toast, onDismissToast)", common)
        self.assertIn("liveRegion = LiveRegionMode.Polite", common)
        bridge = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/host/ComposeDialogViews.kt").read_text()
        self.assertIn("revision == noticeRevision", bridge)
        self.assertIn("SharedFeedbackPage(notice", bridge)

    def test_settings_export_feedback_and_import_authority_are_host_guarded(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        export = settings.split("private fun startPreferencesExport(", 1)[1].split("private fun confirmPreferencesImport(", 1)[0]
        self.assertNotIn("Toast.makeText", export)
        self.assertIn("showSettingsFeedback", export)
        self.assertIn("VaultSession.isCurrent(generation)", export)
        self.assertIn("pending.zero()", export)
        confirm = settings.split("private fun confirmPreferencesImport(", 1)[1].split("private fun previewPreferencesImport(", 1)[0]
        self.assertLess(confirm.index("val owned = settingsConfirmationPrompt === dialogRef"), confirm.index("bytes.copyOf()"))
        self.assertIn("VaultSession.isCurrent(generation)", confirm)
        for marker in ("override fun onVaultLocked() {", "override fun onDestroy() {"):
            self.assertIn("clearPreferencesExport()", settings.split(marker, 1)[1].split("\n    }", 1)[0])

    def test_biometric_enrollment_feedback_is_shared_and_pending_is_visible(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        enrollment = settings.split("private fun promptBiometricEnrollment(", 1)[1].split("private fun verifyVaultPassword(", 1)[0]
        self.assertNotIn("Toast.makeText", enrollment)
        self.assertIn("view.updateOperation(true)", enrollment)
        self.assertIn("view.updateOperation(false, msg)", enrollment)
        self.assertIn("host.showSettingsFeedback(R.string.settings_toast_biometric_turned_on)", enrollment)
        form = (FOUNDATION / "BiometricEnableDialog.kt").read_text()
        self.assertIn("enabled = !busy", form)
        self.assertIn("operationError ?: error", form)
        self.assertLess(form.index('password = ""', form.index("val submitted")), form.index("onConfirm(submitted)"))

    def test_import_completion_handoff_contains_only_metadata_and_checks_session(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        save = settings.split("private fun saveImportFeedback(", 1)[1].split("private fun restoreImportFeedback(", 1)[0]
        self.assertNotIn("putString", save)
        self.assertNotIn("putByteArray", save)
        self.assertIn("VaultSession.isCurrent(feedback.generation)", save)
        restore = settings.split("private fun restoreImportFeedback(", 1)[1].split("private companion object", 1)[0]
        self.assertIn("VaultSession.isCurrent(generation)", restore)
        self.assertIn("showSettingsFeedback(success + detail)", restore)
        self.assertIn("importFeedback = null", restore)
        imports = settings.split("private fun confirmPreferencesImport(", 1)[1].split("private fun completeSettingsDocumentPicker(", 1)[0]
        self.assertNotIn("Toast.makeText", imports)

    def test_active_settings_feedback_has_no_native_toast_or_snackbar(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        active = settings.split("open class SettingsFragment", 1)[0]
        self.assertNotIn("Toast.makeText", active)
        self.assertNotIn("Snackbar.make", active)
        validation = active.split("private fun confirmPreferencesImport(", 1)[1].split("private fun applyPreferencesImport(", 1)[0]
        self.assertEqual(validation.count("val preview = previewPreferencesImport(bytes)"), 1)
        self.assertNotIn("isReadablePreferences", validation)
        self.assertIn("WrongPlatformException", validation)
        self.assertIn("R.string.preferences_import_wrong_platform else R.string.preferences_import_failed", validation)

    def test_settings_compatibility_callbacks_reuse_shared_feedback_and_live_guards(self):
        settings = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/SettingsActivity.kt").read_text()
        self.assertNotIn("Toast.makeText", settings)
        self.assertNotIn("Snackbar.make", settings)
        login = settings.split("loginBackup?.setOnPreferenceChangeListener", 1)[1].split("useAutoBackup =", 1)[0]
        self.assertIn("activity.confirmLegacyBackupPasswordRemoval()", login)
        self.assertNotIn("sharedPref.edit().remove", login)
        self.assertIn("activity.overviewSessionReady()", login)
        biometric = settings.split("biometricPref.setOnPreferenceChangeListener", 1)[1].split("if (sharedPref.contains", 1)[0]
        self.assertIn("val passwordVaultNow =", biometric)
        self.assertIn("if (!passwordVaultNow)", biometric)
        self.assertIn("activity.overviewSessionReady()", biometric)

    def test_backup_picker_failures_use_session_guarded_shared_feedback(self):
        backup = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/BackupActivity.kt").read_text()
        self.assertNotIn("Toast.makeText(this, R.string.backup_toast_file_selection_failed", backup)
        self.assertNotIn("Toast.makeText(this, R.string.backup_toast_no_location", backup)
        helper = backup.split("private fun showBackupFeedback(", 1)[1].split("private fun notifyBackupState(", 1)[0]
        self.assertIn("if (!canOperate()) return", helper)
        self.assertIn("Runnable { page.dismissNotice(revision) }", helper)
        for marker in ("override fun onVaultLocked() {", "override fun onDestroy() {"):
            self.assertIn("clearBackupFeedback()", backup.split(marker, 1)[1].split("\n    }", 1)[0])

    def test_backup_nonclosing_failures_and_provider_errors_use_shared_banner(self):
        backup = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/BackupActivity.kt").read_text()
        self.assertIn("showBackupFeedback(R.string.backup_toast_openpgp_not_verified)", backup)
        self.assertNotIn("Toast.makeText(this, R.string.backup_toast_openpgp_not_verified", backup)
        self.assertIn("showBackupFeedback(failureMessage)", backup)
        self.assertIn('completedBackupNotice = "export_success"', backup)
        self.assertNotIn("notifyBackupState(successMessage, result.payload)", backup)
        provider = backup.split("OpenPgpApi.RESULT_CODE_ERROR ->", 1)[1].split("else -> notifyOpenPgpFailure", 1)[0]
        self.assertNotIn("Toast.makeText", provider)
        self.assertIn("showBackupFeedback(", provider)

    def test_vault_picker_feedback_uses_live_frame_and_shared_banner(self):
        main = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        self.assertNotIn("Toast.makeText(this, R.string.backup_toast_file_selection_failed", main)
        helper = main.split("private fun showVaultFeedback(", 1)[1].split("override fun onVaultLocked", 1)[0]
        self.assertIn("adapter?.encryptionKey !== key", helper)
        self.assertIn("VaultSession.isCurrent(composeVaultGeneration)", helper)
        self.assertIn("page.dismissNotice(revision)", helper)
        bridge = (ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui/vault/VaultComposeView.kt").read_text()
        self.assertIn("SharedFeedbackPage(notice", bridge)
        self.assertIn('notice = ""', bridge.split("fun clear()", 1)[1])

    def test_backup_success_result_handoff_is_allowlisted_and_session_bound(self):
        backup = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/BackupActivity.kt").read_text()
        finish = backup.split("fun finishWithResult()", 1)[1].split("override fun onSupportNavigateUp", 1)[0]
        self.assertIn('data.putExtra("backup_feedback_kind", it)', finish)
        self.assertIn("if (canOperate())", finish)
        self.assertNotIn("payload", finish)
        main = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        handoff = main.split("private fun acceptBackupFeedback(", 1)[1].split("private val feedbackHandler", 1)[0]
        self.assertIn("VaultSession.isCurrent(generation)", handoff)
        self.assertIn('"export_success" -> R.string.backup_toast_export_success', handoff)
        self.assertIn("else -> return", handoff)
        self.assertIn("composeVaultGeneration != pending.first", handoff)
        self.assertLess(handoff.index("pendingBackupNotice = null\n        showVaultFeedback"), handoff.index("showVaultFeedback(pending.second)"))

    def test_vault_export_results_use_original_generation_and_shared_feedback(self):
        main = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        self.assertIn("if (!VaultSession.isCurrent(expectedGeneration)) return", main)
        for prefix in ("email_export", "password_export", "thunderbird_export", "linscp_export", "password_export_csv"):
            self.assertIn(f"showVaultFeedback(if (saved) R.string.{prefix}_success else R.string.{prefix}_failed, generation)", main)
            self.assertNotIn(f"Toast.makeText(this, if (saved) R.string.{prefix}_success", main)

    def test_import_completion_notices_and_email_retry_keep_original_session(self):
        main = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        for kind in ("email", "password", "thunderbird", "linscp"):
            self.assertIn(f"showVaultFeedback(R.string.{kind}_import_success, generation)", main)
            self.assertNotIn(f"Toast.makeText(this, R.string.{kind}_import_success", main)
        email = main.split("private fun importEmailDocument(", 1)[1].split("private fun saveEmailExport(", 1)[0]
        self.assertIn("emailImportPrompt !== pending.host", email)
        self.assertIn("emailImportBytes !== bytes", email)
        self.assertIn("current.encryptionKey !== VaultSession.getKey()", email)
        self.assertIn("importEmailBytes(bytes, password, generation)", email)
        self.assertIn("thunderbirdImportPrompt !== pending.host || thunderbirdImportPreview !== incoming", main)

    def test_migration_completion_and_no_data_notices_use_shared_feedback(self):
        main = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        confirmation = main.split("private fun showMigrationConfirmation(", 1)[1].split("\n    private fun ", 1)[0]
        self.assertNotIn("Toast.makeText", confirmation)
        self.assertIn("showVaultFeedback(R.string.google_import_save_failed, epoch)", confirmation)
        self.assertIn("outcome.added, outcome.skipped), epoch)", confirmation)
        for prefix in ("email", "thunderbird", "linscp"):
            self.assertIn(f"showVaultFeedback(R.string.{prefix}_export_empty)", main)
            self.assertNotIn(f"Toast.makeText(this, R.string.{prefix}_export_empty", main)

    def test_google_batch_progress_warnings_are_in_shared_modal_message(self):
        main = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        batch = main.split("private fun addMigrationQRCode(", 1)[1].split("private fun migrationPreview(", 1)[0]
        self.assertNotIn("Toast.makeText", batch)
        for name in ("already_scanned", "wrong_batch", "scan_failed"):
            self.assertIn(f"showMigrationProgress(R.string.google_import_{name})", batch)
        self.assertIn('feedbackId?.let { "\\n\\n" + getString(it) }', batch)
        self.assertIn("reportFailure = false", batch)
        self.assertIn("showMigrationProgress(R.string.backup_toast_file_selection_failed)", batch)

    def test_standalone_qr_and_batch_wrong_format_feedback_use_visible_shared_surface(self):
        main = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        qr = main.split("private fun addQRCode(", 1)[1].split("private fun openUriImportDialog(", 1)[0]
        self.assertNotIn("Toast.makeText", qr)
        self.assertIn("showMigrationProgress(R.string.google_import_expected_qr)", qr)
        self.assertIn("showVaultFeedback(R.string.toast_invalid_qr_code, epoch)", qr)
        uri = main.split("private fun openUriImportDialog(", 1)[1].split("\n    private fun ", 1)[0]
        self.assertNotIn("Toast.makeText", uri)
        self.assertIn("reportFailure = !collecting", uri)
        save = main.split("private fun saveImportedEntry(", 1)[1].split("private inner class", 1)[0]
        self.assertIn("showVaultFeedback(failureMessage, epoch)", save)

    def test_image_decoder_reports_failures_to_visible_shared_host(self):
        decoder = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/ScanQRCodeFromFile.kt").read_text()
        reporting = decoder.split("fun scanQRImageReporting(", 1)[1]
        self.assertNotIn("Toast.makeText", reporting)
        self.assertIn("onFailure(R.string.toast_file_load_error)", reporting)
        self.assertIn("onFailure(message)", reporting)
        main = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        self.assertIn("scanQRImageReporting(this, intent.data) { scanFailure = it }", main)
        self.assertIn("showMigrationProgress(scanFailure)", main)
        self.assertIn("showVaultFeedback(it, scanGeneration)", main)

    def test_export_preparation_feedback_and_selector_ownership(self):
        main = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        for method, owner in (("chooseEmailExport", "emailExportSelectionDialog"), ("chooseThunderbirdExport", "thunderbirdExportSelectionDialog")):
            section = main.split(f"private fun {method}(", 1)[1].split("\n    private ", 1)[0]
            self.assertIn(f"val owned = {owner} === host", section)
            self.assertIn("if (!owned) return@MultiChoiceSelectionComposeView", section)
            self.assertNotIn("Toast.makeText", section)
            self.assertIn(", selectionGeneration)", section)
        linscp = main.split("private fun prepareLinScpExport(", 1)[1].split("private fun saveLinScpExport(", 1)[0]
        self.assertNotIn("Toast.makeText", linscp)

    def test_remaining_transfer_read_and_csv_preparation_notices_are_shared(self):
        main = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        for marker in ("fun requestPasswordCsvExport(", "private fun importEmailDocument(", "private fun openPasswordImportDialog(", "private fun importThunderbirdDocument(", "private fun importLinScpDocument("):
            section = main.split(marker, 1)[1].split("\n    private fun ", 1)[0]
            self.assertNotIn("Toast.makeText", section)
        self.assertIn("showVaultFeedback(R.string.backup_timestamp_save_failed)", main)
        self.assertIn("showVaultFeedback(R.string.password_export_csv_failed, generation)", main)
        self.assertIn("Toast.makeText(getBaseContext(), R.string.toast_auth_failed_fatal", main)

    def test_about_feedback_uses_shared_page_and_cleans_up(self):
        about = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/AboutActivity.kt").read_text()
        self.assertNotIn("Toast.makeText", about)
        self.assertIn("Runnable { host.dismissNotice(revision) }", about)
        destruction = about.split("override fun onDestroy()", 1)[1]
        self.assertIn("clearFeedback()", destruction)
        self.assertIn("page?.close()", destruction)

    def test_recoverable_biometric_authentication_feedback_is_shared(self):
        auth = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/AuthenticateActivity.kt").read_text()
        callback = auth.split("private fun triggerBiometricUnlock(", 1)[1].split("override fun cancelTaskOnScreenOff", 1)[0]
        self.assertNotIn("Toast.makeText", callback)
        self.assertIn("sharedAuthentication?.showFeedback(error.message(this))", callback)
        form = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/SharedAuthenticationScreen.kt").read_text()
        self.assertIn("feedback: String? = null", form)
        self.assertIn("liveRegion = LiveRegionMode.Polite", form)

    def test_authentication_native_retry_paths_clear_shared_feedback(self):
        auth = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/AuthenticateActivity.kt").read_text()
        for marker, end in (("private fun startAuthTask(", "override fun onTaskFailure"), ("private fun triggerBiometricUnlock(", "override fun cancelTaskOnScreenOff")):
            section = auth.split(marker, 1)[1].split(end, 1)[0]
            self.assertIn("sharedAuthentication?.clearFeedback()", section)
        hardware = auth.split("hardware.setOnClickListener", 1)[1].split("biometricButton =", 1)[0]
        self.assertIn("sharedAuthentication?.clearFeedback()", hardware)

    def test_hardware_operation_feedback_uses_shared_status_with_live_session(self):
        hardware = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/HardwareKeyActivity.kt").read_text()
        self.assertNotIn("Toast.makeText", hardware)
        helper = hardware.split("private fun showHardwareStatus(", 1)[1].split("private fun showComposeDialog", 1)[0]
        self.assertIn("if (!sessionReady() || !::status.isInitialized) return", helper)
        self.assertIn("status.setText(message)", helper)
        self.assertIn("publishHardwarePage()", helper)
        for name in ("copy_done", "enroll_done", "no_candidates", "enroll_password_only"):
            self.assertIn(f"showHardwareStatus(R.string.hardware_{name})", hardware)
