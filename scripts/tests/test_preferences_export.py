"""Guards for portable preferences export/import (#648).

The feature is one format read by three clients, so most of what can go wrong is a *policy* mistake
rather than a typo: exporting a Keystore-wrapped password that cannot be unwrapped anywhere else,
naming a security feature in a file the user may share, half-applying a document addressed to
another client, or writing a default for a setting the user never set. Those are pinned in `:core`
(`PreferencesExportTest`, 12 tests), the Swift package (`PreferencesExportTests`), the desktop
round-trip (`PreferencesExportDesktopTest`) and two executed sandbox checks.

This suite pins the parts that only exist as wiring: the settings rows on Android, the picker and
buffer handling, the desktop menu and dialogs, and the iOS section.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities"
APP = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp"
RES = ROOT / "app/src/main/res"
DESKTOP = ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop"
IOS_KIT = ROOT / "ios/Sources/OfflineOTPKit"
IOS_APP = ROOT / "ios/App/Sources"
SANDBOX = ROOT / "scripts/kotlin-sandbox"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class PreferencesFormatTest(unittest.TestCase):
    def test_the_codec_exists_on_every_client(self):
        self.assertTrue((CORE / "PreferencesExport.kt").is_file())
        self.assertTrue((IOS_KIT / "PreferencesExport.swift").is_file())
        self.assertTrue((APP / "Utilities/AndroidPreferencesPort.kt").is_file())
        self.assertTrue((DESKTOP / "storage/DesktopPreferencesPort.kt").is_file())
        self.assertTrue((ROOT / "core/src/test/kotlin/com/fairuniversal/offlineotp/Utilities/"
                         "PreferencesExportTest.kt").is_file())
        self.assertTrue((ROOT / "ios/Tests/OfflineOTPKitTests/PreferencesExportTests.swift").is_file())

    def test_device_bound_material_is_documented_and_never_catalogued(self):
        kotlin = read(CORE / "PreferencesExport.kt")
        # The wrapped backup password, the vault KDF, the SAF grants and the hardware envelope.
        for key in ("pref_backup_password_enc", "pref_auth_salt", "pref_auth_iterations",
                    "pref_backup_location", "pref_hardware_unlock",
                    "pref_openpgp_secret_file", "pref_openpgp_recipient_files",
                    "auth_salt", "auth_credentials"):
            self.assertIn(f'"{key}" to', kotlin)
        # The one movable secret is the backup password, read back through the platform.
        self.assertIn("fun readBackupPassword()", kotlin)
        self.assertIn("SECRET_BACKUP_PASSWORD", kotlin)
        for key in ("secret_openpgp_recipient_files", "secret_openpgp_secret_key"):
            self.assertNotIn(f'"{key}"', kotlin)

    def test_a_document_is_addressed_to_one_client(self):
        kotlin = read(CORE / "PreferencesExport.kt")
        self.assertIn("PLATFORM_ANDROID", kotlin)
        self.assertIn("PLATFORM_DESKTOP", kotlin)
        self.assertIn("class WrongPlatformException", kotlin)
        self.assertIn(".put(\"platform\", platform)", kotlin)
        self.assertIn("sourcePlatform != platform", kotlin)
        swift = read(IOS_KIT / "PreferencesExport.swift")
        self.assertIn("case wrongPlatform(source: String, target: String)", swift)
        self.assertIn("guard source == platform else", swift)
        self.assertIn("func apply(_ bytes: Data", swift)

    def test_the_desktop_catalog_is_its_two_real_settings(self):
        kotlin = read(CORE / "PreferencesExport.kt")
        self.assertIn("val desktopCatalog", kotlin)
        self.assertIn('Setting(UI_SORT_MODE, Kind.STRING)', kotlin)
        self.assertIn('Setting(UI_INACTIVITY_LOCK_SECONDS, Kind.INTEGER)', kotlin)
        # The desktop's stored key names are mirrored, not invented.
        controller = read(DESKTOP / "control/VaultController.kt")
        self.assertIn('const val SORT_MODE_KEY = "ui_sort_mode"', controller)
        # ...and the catalog is the only route into an import: nothing else is applied.
        port = read(DESKTOP / "storage/DesktopPreferencesPort.kt")
        self.assertIn("PreferencesExport.setting(key, PreferencesExport.PLATFORM_DESKTOP)", port)


class PreferencesWiringTest(unittest.TestCase):
    def test_android_settings_screen_offers_export_and_import(self):
        prefs = read(RES / "xml/preferences.xml")
        self.assertIn('android:key="@string/settings_key_preferences_export"', prefs)
        self.assertIn('android:key="@string/settings_key_preferences_import"', prefs)
        settings_xml = read(RES / "values/settings.xml")
        self.assertIn("pref_preferences_export", settings_xml)
        self.assertIn("pref_preferences_import", settings_xml)
        activity = read(APP / "Activities/SettingsActivity.kt")
        # The password question is asked before the picker, and the answer travels with the export.
        self.assertIn("fun confirmPreferencesExport()", activity)
        self.assertIn("preferences_export_with_secrets", activity)
        self.assertIn("preferences_export_without_secrets", activity)
        self.assertIn("startPreferencesExport(includeSecrets = true)", activity)
        self.assertIn("startPreferencesExport(includeSecrets = false)", activity)
        # The serialised bytes are wiped on cancel, after the write, and on failure.
        self.assertIn("preferencesExportPending?.zero()", activity)
        self.assertIn("pending.zero()", activity)
        self.assertIn("Constants.INTENT_SETTINGS_PREFERENCES_EXPORT", activity)
        self.assertIn("Constants.INTENT_SETTINGS_PREFERENCES_IMPORT", activity)

    def test_android_validates_before_asking_and_reports_a_foreign_file(self):
        activity = read(APP / "Activities/SettingsActivity.kt")
        self.assertIn("isReadablePreferences(bytes)", activity)
        # A wrong-platform file says so; it is not reported as corrupt.
        self.assertIn("WrongPlatformException", activity)
        self.assertIn("preferences_import_wrong_platform", activity)
        self.assertIn("preferences_import_skipped", activity)
        # Dismissing the confirm dialog (Back, or a tap outside) must still wipe the read bytes.
        self.assertIn("dialog.setOnDismissListener { bytes.fill(0) }", activity)
        self.assertIn("dialog.setOnDismissListener { passwordInput.setText(\"\") }", activity)

    def test_the_android_port_keeps_the_backup_password_wrapped(self):
        port = read(APP / "Utilities/AndroidPreferencesPort.kt")
        # The secret is written through Settings, never into the preference store as plaintext.
        self.assertIn("settings.setBackupPassword(typed.value)", port)
        self.assertIn("settings.getBackupPasswordEnc()", port)
        self.assertIn("IllegalStateException", port)
        settings = read(APP / "Utilities/Settings.kt")
        self.assertIn("open fun setBackupPassword(value: String): Boolean", settings)

    def test_desktop_menu_and_dialogs(self):
        window = read(DESKTOP / "ui/MainWindow.kt")
        self.assertIn('"Export preferences…"', window)
        self.assertIn('"Import preferences…"', window)
        self.assertIn("PreferencesDialogs.export(frame, controller.preferences)", window)
        dialogs = read(DESKTOP / "ui/PreferencesDialogs.kt")
        self.assertIn("PreferencesExport.PLATFORM_DESKTOP", dialogs)
        self.assertIn("PreferencesExport.MAX_BYTES", dialogs)
        self.assertIn("WrongPlatformException", dialogs)
        # The desktop carries no secret, so it never asks about one.
        self.assertNotIn("includeSecrets = true", dialogs)

    def test_ios_section_and_port(self):
        view = read(IOS_APP / "SettingsView.swift")
        self.assertIn('"Export preferences…"', view)
        self.assertIn('"Import preferences…"', view)
        self.assertIn("PreferencesExport.export(store.preferencesPort)", view)
        # `apply`, not `import`: the latter is a declaration keyword in Swift.
        self.assertIn("PreferencesExport.apply(data, into: store.preferencesPort)", view)
        self.assertIn("struct PreferencesDocument: FileDocument", view)
        self.assertIn("public static func apply(_ bytes: Data", read(IOS_KIT / "PreferencesExport.swift"))
        self.assertIn("store.reloadInactivityLock()", view)
        store = read(IOS_APP / "VaultStore.swift")
        self.assertIn("public var preferencesPort: PreferencesExport.SettingsPort", store)
        # The vault's own material is not readable through the port.
        self.assertIn("case .secret, .deviceBound:", store)
        # The port's write is non-throwing, so a port must not `throw` inside it.
        port_body = store.split("func write(_ key: String, _ value: PreferencesExport.Value) {")[1]
        self.assertNotIn("throw", port_body.split("\n    }\n")[0])

    def test_the_ios_settings_store_can_delete(self):
        # `PasswordVault.changePassword` rolls back by *removing* a setting that was absent before,
        # and `VaultStore.setInactivityLockSeconds(0)` relies on the same call. Without a declared
        # `remove` the kit does not compile, so it is pinned here as well as in the protocol.
        kit = read(IOS_KIT / "PasswordVault.swift")
        self.assertIn("func remove(_ key: String)", kit)
        self.assertIn("public func remove(_ key: String) { }", kit)
        self.assertIn("public func remove(_ key: String) {\n        guard values.removeValue(forKey: key) != nil else { return }", kit)

    def test_the_sandbox_executes_the_ports(self):
        run = read(SANDBOX / "run.sh")
        self.assertIn("Utilities/PreferencesExport.kt", run)
        self.assertIn("Utilities/AndroidPreferencesPort.kt", run)
        checks = read(SANDBOX / "Checks.kt")
        self.assertIn("portable preferences export", checks)


if __name__ == "__main__":
    unittest.main()
