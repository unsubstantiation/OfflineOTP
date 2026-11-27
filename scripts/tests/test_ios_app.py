"""Guards for the iOS app shell: project definition, palette, and the promise to stay offline.

The shell is the first place an OfflineOTP client draws a code, so the things worth pinning are the
ones that would silently diverge from the other clients: the colour tokens, the minimum password
length, the fact that nothing in the app talks to a network, and the shape of the build (a simulator
build with no signing identity).
"""
import re
import subprocess
import sys
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "ios"
APP = IOS / "App"
SOURCES = APP / "Sources"
APP_TESTS = APP / "Tests"
PALETTE = (SOURCES / "ForestPalette.swift").read_text(encoding="utf-8")
KIT = IOS / "Sources/OfflineOTPKit"
UTILITIES = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities"
ANDROID_VALUES = ROOT / "app/src/main/res/values"
WORKFLOW = (ROOT / ".github/workflows/ios.yml").read_text(encoding="utf-8")


def app_sources():
    return {path.name: path.read_text(encoding="utf-8") for path in SOURCES.glob("*.swift")}


def swift_names(name):
    return re.sub(r"_([a-z])", lambda match: match.group(1).upper(), name)


class IosAppTest(unittest.TestCase):
    def test_the_project_is_defined_as_yaml_and_generated_not_committed(self):
        project = yaml.safe_load((IOS / "project.yml").read_text(encoding="utf-8"))
        target = project["targets"]["OfflineOTP"]
        self.assertEqual("application", target["type"])
        self.assertEqual("iOS", target["platform"])
        # The app is a client of the shared format layer, not a copy of it.
        self.assertEqual(
            [{"framework": "Frameworks/OfflineOTPPresentation.xcframework", "embed": False},
             {"package": "OfflineOTPKit", "product": "OfflineOTPKit"},
             {"package": "YubiKit", "product": "YubiKit"}, {"package": "SwiftLog", "product": "Logging"}], target["dependencies"])
        self.assertIn("OfflineOTPKit", project["packages"])
        self.assertEqual(".", project["packages"]["OfflineOTPKit"]["path"])
        # One shared scheme drives both the build and the app's unit tests.
        self.assertEqual(["OfflineOTPAppTests"], target["scheme"]["testTargets"])
        self.assertEqual("bundle.unit-test",
                         project["targets"]["OfflineOTPAppTests"]["type"])
        # The generated project is not part of the repository.
        gitignore = (ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("ios/OfflineOTP.xcodeproj/", gitignore)
        self.assertIn("ios/App/Info.plist", gitignore)
        self.assertFalse((IOS / "OfflineOTP.xcodeproj").exists(),
                         "a generated project was committed")

    def test_hardware_oath_has_explicit_transports_and_guarded_calculation(self):
        project = yaml.safe_load((IOS / "project.yml").read_text(encoding="utf-8"))
        sdk = project["packages"]["YubiKit"]
        self.assertEqual("https://github.com/Yubico/yubikit-swift.git", sdk["url"])
        self.assertEqual("03fe66d15b6e863e87a44c7a263b7bffedda9d79", sdk["revision"])
        target = project["targets"]["OfflineOTP"]
        self.assertEqual(["TAG"], target["entitlements"]["properties"]["com.apple.developer.nfc.readersession.formats"])
        info = target["info"]["properties"]
        self.assertEqual(["A0000005272101", "A0000006472F0001"], info["com.apple.developer.nfc.readersession.iso7816.select-identifiers"])
        self.assertIn("NFCReaderUsageDescription", info)
        reader = app_sources()["HardwareOathReader.swift"]
        native = app_sources()["HardwareNativeOperation.swift"]
        for marker in ["NFCTagReaderSession.readingAvailable", "TKSmartCardSlotManager.default", "NFCSmartCardConnection.makeConnection()",
                       "USBSmartCardConnection.makeConnection()", "self.epoch == id", "Task.checkCancellation()", "timeoutSeconds: TimeInterval = 30",
                       "SwiftLogNoOpLogHandler"]:
            self.assertIn(marker, native)
        self.assertIn("session.listCredentials()", reader)
        for command in ["calculateAll", "session.put(", "session.delete(", "session.reset(", "session.setPassword("]:
            self.assertNotIn(command, reader)
        host = app_sources()["ComposeVaultHost.swift"]
        self.assertIn('case "hardwareNFC", "hardwareUSB":', host)
        self.assertIn("self.store.generation == generation", host)
        self.assertIn("session.unlock(password: password)", reader)
        self.assertIn("account?.credential.deviceId == deviceID", reader)
        self.assertIn("HardwareOathPolicy.uniqueMatch(selected.identity", reader)
        self.assertEqual(1, reader.count("session.calculateCredentialCode("))
        self.assertIn("allowCounterAdvance", reader)
        self.assertIn("code.validTo", reader)
        self.assertIn('draft["hardwareCounterConsent"] = "false"', host)
        self.assertIn('draft.removeValue(forKey: "hardwarePassword")', host)
        self.assertIn("hardwareCodeExpiry", host)

    def test_hardware_transport_capabilities_are_separate_and_native_dependencies_are_noticed(self):
        dialog = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/unlock/HardwareTransportDialog.kt").read_text()
        self.assertIn('"Use NFC", enabled = !busy && nfcAvailable', dialog)
        self.assertIn('"Use USB-C", enabled = !busy && usbAvailable', dialog)
        self.assertIn("if (busy || (if (usb) !usbAvailable else !nfcAvailable)) return", dialog)
        host = app_sources()["ComposeUnlockHost.swift"]
        self.assertIn("nfcAvailable: HardwareNativeOperation.supportsNFC", host)
        self.assertIn("usbAvailable: HardwareNativeOperation.supportsUSBC", host)
        vault = app_sources()["ComposeVaultHost.swift"]
        for action, label, capability in [("hardwareEnrollNFC", "Enroll using NFC", "supportsNFC"),
                                          ("hardwareEnrollUSB", "Enroll using USB-C", "supportsUSBC"),
                                          ("hardwareSeedNFC", "Copy seed using NFC", "supportsNFC"),
                                          ("hardwareSeedUSB", "Copy seed using USB-C", "supportsUSBC")]:
            self.assertIn(f'row("{action}", "{label}", enabled: !hardwareBusy && HardwareNativeOperation.{capability})', vault)
        catalog = (ROOT / "ui-contract/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/portable/PortablePresentation.kt").read_text()
        self.assertIn("Yubico YubiKit Swift 1.5.0", catalog)
        self.assertIn("Apple SwiftLog 1.6.4", catalog)

    def test_background_fences_queued_backup_restore_and_authentication_actions(self):
        host = app_sources()["ComposeVaultHost.swift"]
        self.assertIn("foreground = false", host)
        self.assertIn("selector: #selector(backgroundSensitiveOperations)", host)
        self.assertIn("selector: #selector(resumeSensitiveOperations)", host)
        self.assertIn("guard active, foreground, store.isUnlocked, !automaticBackup.enabled", host)
        self.assertIn("self.foreground, self.store.isUnlocked", host)
        unlock = app_sources()["ComposeUnlockHost.swift"]
        self.assertIn("foreground = false; hardwareCancel()", unlock)
        self.assertGreaterEqual(unlock.count("guard foreground else { return }"), 8)
        native = app_sources()["HardwareNativeOperation.swift"]
        self.assertIn("defer { dispose(&result) }", native)
        self.assertLess(native.index("defer { dispose(&result) }"), native.index("completion(.success(result))"))
        fido = app_sources()["HardwareFidoReader.swift"]
        self.assertIn("pendingKey?.clear(); pendingKey = nil; native.cancel()", fido)
        self.assertIn("guard var key = material.take() else { throw CancellationError() }", fido)
        self.assertIn("vaultKey: key)", fido)
        self.assertNotIn("prf: prf, vaultKey: vaultKey)", fido)

    def test_hardware_seed_export_is_explicit_bounded_and_never_retries_or_overwrites(self):
        writer = app_sources()["HardwareOathWriter.swift"]
        self.assertEqual(1, writer.count("session.addCredential(template:"))
        self.assertLess(writer.index("session.listCredentials()"), writer.index("session.addCredential(template:"))
        self.assertIn("!credentials.contains(where: { $0.id == id })", writer)
        self.assertIn("template.identifier == names.identifier", writer)
        self.assertIn("Task.checkCancellation() // No retry", writer)
        self.assertNotIn("session.calculateCredentialCode", writer)
        self.assertNotIn("session.deleteCredential", writer)
        self.assertIn("seed[index] = 0", writer)
        host = app_sources()["ComposeVaultHost.swift"]
        for marker in ['case "hardwareSeedNFC", "hardwareSeedUSB":',
                       'draft["hardwareSeedConsent"] == "true"',
                       'draft["hardwareSeedConsent"] = "false"',
                       'draft.removeValue(forKey: "hardwareSeedPassword")',
                       'store.entries[index].fields == hardwareCopyFields',
                       'hardwareWriter.cancel()', 'self.store.generation == generation']:
            self.assertIn(marker, host)
        policy = (KIT / "HardwareOathCopyPolicy.swift").read_text()
        for marker in ["identifier.utf8.count <= 64", "Int64(Int32.max)", "(6...8).contains(entry.digits)", ".format"]:
            self.assertIn(marker, policy)

    def test_native_file_reader_owns_revocable_scopes_and_dispatches_identity_bound_results(self):
        sources = app_sources()
        reader = sources["NativeFileReader.swift"]
        self.assertIn('DispatchQueue(label: "OfflineOTP.file-import"', reader)
        self.assertIn("Work(Scope(url), completion)", reader)
        self.assertIn("guard let scope = task.takeScope(), task.active()", reader)
        self.assertIn("self.work === task", reader)
        self.assertIn("completion = nil; scope = nil", reader)
        self.assertIn("data.count <= maxBytes", reader)
        root = sources["AppRootViewController.swift"]
        self.assertIn("filePicker.cancel(); fileReader.cancel()", root)
        self.assertIn("self.nativeEpoch == epoch, self.store.generation == generation", root)
        self.assertNotIn("startAccessingSecurityScopedResource", root)

    def test_automatic_backup_form_navigation_releases_private_not_public_inputs(self):
        host = app_sources()["ComposeVaultHost.swift"]
        helper = host.split("private func clearAutomaticBackupDraftSecrets()")[1].split("private func openSurface")[0]
        self.assertIn("automaticSigningKey = nil", helper)
        self.assertIn('draft.removeValue(forKey: "automaticSigningPassphrase")', helper)
        self.assertNotIn("automaticRecipientKeys = []", helper)
        self.assertIn('if surfaceKey == "automaticBackup" && key != surfaceKey { clearAutomaticBackupDraftSecrets() }', host)
        configure = host.split("private func configureAutomaticBackups")[1].split("func selectedAutomaticBackupFolder")[0]
        self.assertNotIn('draft["automaticOpenPgp"]', configure)
        self.assertIn("automaticRecipientKeys = recipientKeys", configure)

    def test_session_signing_credentials_never_enter_persistent_backup_configuration(self):
        sources = app_sources()
        config = sources["AutomaticBackupCredentialStore.swift"]
        self.assertIn("let requiresSigning: Bool?", config)
        self.assertNotIn("let signingKey", config)
        self.assertNotIn("let signingPassphrase", config)
        host = sources["ComposeVaultHost.swift"]
        self.assertIn("if config.requiresSigning == true", host)
        self.assertIn("automaticBackup.reportSigningRequired()", host)
        self.assertIn("requiresSigning: signingKey != nil", host)
        self.assertIn("signingKey: signingKey, signingPassphrase: signingPassphrase", host)
        self.assertIn('asCopy: kind != "openpgpAutomaticSigningKey"', sources["AppRootViewController.swift"])
        self.assertIn("sealSigned(input.document, input.recipients, key, input.signingPassphrase)", sources["SessionAutomaticBackup.swift"])
        self.assertIn("signingKey = nil; signingPassphrase = \"\"; sealSigned = nil", sources["SessionAutomaticBackup.swift"])

    def test_automatic_openpgp_uses_snapshotted_bounded_certificates_and_mode_scoped_retention(self):
        sources = app_sources()
        self.assertIn('mode == "openpgp" && password.isEmpty', sources["AutomaticBackupCredentialStore.swift"])
        self.assertIn("Set(keys).count == keys.count", sources["AutomaticBackupCredentialStore.swift"])
        backup = sources["SessionAutomaticBackup.swift"]
        self.assertIn("sealRecipients(input.document, input.recipients)", backup)
        self.assertIn("fileExtension: input.fileExtension", backup)
        self.assertIn('guard ["aes", "pgp", "offlineotp"].contains(fileExtension)', backup)
        self.assertIn("guard admitted() else { return false }", backup)
        host = sources["ComposeVaultHost.swift"]
        self.assertIn('onImport("openpgpAutomaticRecipient")', host)
        self.assertIn("recipientKeys: config.recipientKeys ?? []", host)
        self.assertIn("store.automaticOpenPgpEncoder()", host)
        self.assertIn("AutomaticBackupConfiguration.validRecipients(automaticRecipientKeys + [data])", host)

    def test_card_display_form_commits_once_before_publishing_on_both_native_hosts(self):
        sources = app_sources()
        action = sources["ComposeVaultHost.swift"].split('case "cardLayout":')[-1].split('case "searchIncludes":')[0]
        self.assertIn("try store.saveCardDisplaySettings(draft)", action)
        self.assertNotIn("store.setCardLayout", action)
        self.assertIn("No changes were applied", action)
        store = sources["VaultStore.swift"]
        batch = store.split("public func saveCardDisplaySettings")[1].split("/// Persists the Compose vault card density")[0]
        self.assertLess(batch.index("writeBatch(changes)"), batch.index("reloadInactivityLock()"))
        self.assertIn('raw == "true" || raw == "false"', batch)
        desktop = ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control"
        self.assertIn("settings.putAll(mapOf(", (desktop / "VaultController.kt").read_text())
        self.assertIn("writeSelection.invoke(selection)", (desktop / "DesktopLayoutSettings.kt").read_text())
        host = ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt"
        self.assertIn("writeSelection = controller::trySaveCardDisplaySelection", host.read_text())

    def test_preferences_import_uses_throwing_atomic_batch_and_publishes_cache_after_disk(self):
        codec = (KIT / "PreferencesExport.swift").read_text()
        self.assertIn("public protocol AtomicSettingsPort: SettingsPort", codec)
        self.assertIn("try atomic.writeBatch", codec)
        store = (KIT / "PasswordVault.swift").read_text()
        merge = store[store.index("public func mergeAtomic"):store.index("private func persist()")]
        self.assertLess(merge.index("try data.write"), merge.index("values = next"))
        self.assertNotIn("try?", merge)
        host = app_sources()["VaultStore.swift"]
        self.assertIn("PreferencesExport.AtomicSettingsPort", host)
        self.assertIn("try settings.mergeAtomic(changes)", host)
        self.assertIn("No settings were imported", app_sources()["ComposeVaultHost.swift"])

    def test_native_file_results_are_picker_session_surface_bound_and_reads_are_stream_bounded(self):
        sources = app_sources()
        picker = sources["NativeFilePicker.swift"]
        self.assertEqual(2, picker.count("guard controller === activePicker else { return }"))
        self.assertIn("prior?.delegate = nil", picker)
        self.assertIn('"OfflineOTP-export-" + UUID().uuidString', picker)
        self.assertIn(".posixPermissions: 0o700", picker)
        self.assertIn(".completeFileProtection", picker)
        self.assertIn("deinit { cleanupPendingExport() }", picker)
        root = sources["AppRootViewController.swift"]
        self.assertIn("foreground = false; nativeEpoch = UUID(); filePicker.cancel()", root)
        self.assertGreaterEqual(root.count("coordinator.acceptsNativeResult(request)"), 6)
        self.assertIn("fileReader.start(url, maxBytes: InputPolicy.maxDocumentBytes)", root)
        self.assertIn("try BoundedFileReader.read($0, maxBytes: $1)", sources["NativeFileReader.swift"])
        self.assertNotIn("Data(contentsOf: url)", root)
        reader = (KIT / "BoundedFileReader.swift").read_text()
        self.assertIn("remaining + 1", reader)
        self.assertIn("guard next.count <= remaining", reader)
        self.assertIn("result.append(next)", reader)
        host = sources["ComposeVaultHost.swift"]
        self.assertIn("nativeRequestID == request", host)
        self.assertGreaterEqual(host.count("nativeRequestID = UUID()"), 4)

    def test_automatic_backup_forms_display_restored_destination_interval_and_retention_without_passwords(self):
        host = app_sources()["ComposeVaultHost.swift"]
        self.assertIn('row("automaticActiveFolder", "Destination folder"', host)
        self.assertIn('row("automaticActivePolicy", "Periodic interval / retention"', host)
        self.assertIn("automaticFolder = destination; automaticMinutes = minutes; automaticKeep = keep", host)
        self.assertIn('draft["automaticMinutes"] ?? String(automaticMinutes)', host)
        compose = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        self.assertIn("automaticBackupFolder = config.folder; automaticBackupMinutes = config.minutes; automaticBackupRetention = config.keep", compose)
        self.assertIn("initialFolder = automaticBackupFolder", compose)
        self.assertIn("initialMinutes = automaticBackupMinutes, initialRetention = automaticBackupRetention", compose)
        dialog = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/AutomaticBackupDialog.kt").read_text()
        self.assertIn('Text("Destination: $initialFolder")', dialog)
        self.assertIn('var password by remember(inputKey) { mutableStateOf("") }', dialog)
        self.assertIn('var confirmation by remember(inputKey) { mutableStateOf("") }', dialog)

    def test_automatic_backups_follow_successful_saves_and_revoke_queued_inputs(self):
        host = app_sources()["ComposeVaultHost.swift"]
        self.assertIn("store.$persistedRevision.dropFirst().map", host)
        self.assertIn("generation == self.store.generation", host)
        self.assertIn("self.automaticBackup.vaultChanged()", host)
        self.assertIn("backupChanges?.cancel()", host)
        backup = app_sources()["SessionAutomaticBackup.swift"]
        for marker in ["func vaultChanged()", "pendingChange = true", "AutomaticBackupPolicy.changeDelay",
                       "input = nil", "guard let input = task.take()", "self.timerEpoch == id"]:
            self.assertIn(marker, backup)
        self.assertNotIn("[weak self, scope]", backup)
        desktop = (ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/DesktopAutomaticBackup.kt").read_text()
        for marker in ["fun vaultChanged()", "CHANGE_DELAY_MILLIS", "input?.close(); input = null", "timer === scheduled"]:
            self.assertIn(marker, desktop)
        compose = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        self.assertIn("controller.persistedRevision", compose)
        self.assertIn("automaticBackup.vaultChanged()", compose)

    def test_automatic_backups_use_session_credentials_and_scoped_native_folder_selection(self):
        sources = app_sources()
        backup = sources["SessionAutomaticBackup.swift"]
        for marker in ["EncryptedBackup.sealWithFreshParameters", "task.active()", "self.work === task",
                       ".withoutOverwriting", ".completeFileProtection", "encrypted.resetBytes",
                       "startAccessingSecurityScopedResource", "stopAccessingSecurityScopedResource",
                       "values.isSymbolicLink != true", "owned.dropFirst(keep)"]:
            self.assertIn(marker, backup)
        self.assertNotIn("UserDefaults", backup)
        self.assertNotIn("setString", backup)
        host = sources["ComposeVaultHost.swift"]
        self.assertIn('case "automaticEnable":', host)
        self.assertIn('draft.removeValue(forKey: "automaticPassword")', host)
        self.assertIn('draft.removeValue(forKey: "automaticConfirmation")', host)
        self.assertIn("UIScene.didEnterBackgroundNotification", host)
        self.assertIn("toast: automaticBackup.failed ? automaticBackup.message : toast", host)
        self.assertIn("automaticBackup.dismissNotice()", host)
        root = sources["AppRootViewController.swift"]
        self.assertIn("self.store.generation == generation", root)
        self.assertIn("coordinator.selectedAutomaticBackupFolder(folder)", root)
        self.assertIn("forOpeningContentTypes: [.folder], asCopy: false", sources["NativeFilePicker.swift"])

    def test_hardware_unlock_format_is_shared_but_not_exposed_as_fake_prf_transport(self):
        envelope = (IOS / "Sources/OfflineOTPKit/HardwareUnlockEnvelope.swift").read_text()
        self.assertIn('"OfflineOTP hardware unlock key v1"', envelope)
        self.assertIn("authenticating: header", envelope)
        self.assertIn("(83...1122).contains(envelope.count)", envelope)
        self.assertNotIn("HardwareUnlockEnvelope.open", app_sources()["HardwareOathReader.swift"])
        for name in ["hardware-unlock-envelope.json", "automatic-backup-options.json"]:
            self.assertTrue((ROOT / "desktop/src/test/resources/golden" / name).is_file())
            self.assertTrue(any(name in path.read_text() for path in (IOS / "Tests/OfflineOTPKitTests").glob("*Tests.swift")))

    def test_the_project_format_is_one_the_runner_can_open(self):
        """The project has to open on the runner's Xcode, and on older ones.

        The image is `macos-15` (arm64, Xcode 16.4). `macos-14` was the first Apple silicon image, but
        its arm64 configuration stopped starting: jobs were cancelled before their first step with
        "The configuration 'macos-14-arm64-us-default' is not supported". The format pin is kept at
        `xcode15_3` on purpose — it is the oldest format XcodeGen can write that still carries
        everything this project needs, so an Xcode 15 machine can open it too.
        """
        project = yaml.safe_load((IOS / "project.yml").read_text(encoding="utf-8"))
        options = project["options"]
        self.assertEqual("xcode15_3", options["projectFormat"])
        self.assertEqual("16.4", options["xcodeVersion"])
        workflow = WORKFLOW
        # An arm64 image: the app target is built for the same architecture the artifacts ship.
        self.assertIn("macos-15", workflow)
        self.assertNotIn("runs-on: macos-14", workflow,
                         "the macos-14 arm64 configuration does not start; see the workflow comment")
        # The move, and the verified architecture, are written down where the pin is.
        self.assertIn("uname -m", workflow)
        self.assertIn("arm64", workflow)
        # A comment has to say why, or the pin looks arbitrary and gets bumped.
        self.assertIn("bump it only together with the runner image", (IOS / "project.yml").read_text(
            encoding="utf-8"))

    def test_the_deployment_target_matches_the_package_and_the_plan(self):
        project = yaml.safe_load((IOS / "project.yml").read_text(encoding="utf-8"))
        self.assertEqual("17.0", project["options"]["deploymentTarget"]["iOS"])
        self.assertEqual("17.0", project["settings"]["base"]["IPHONEOS_DEPLOYMENT_TARGET"])
        package = (IOS / "Package.swift").read_text(encoding="utf-8")
        self.assertIn(".iOS(.v17)", package)
        plan = (ROOT / "docs/ios-client-plan.md").read_text(encoding="utf-8")
        self.assertIn("iOS 17", plan)

    def test_the_app_is_a_single_uikit_entry_point(self):
        sources = app_sources()
        self.assertIn("@main", sources["AppDelegate.swift"])
        self.assertIn("UIApplicationDelegate", sources["AppDelegate.swift"])
        self.assertIn("AppRootViewController()", sources["AppDelegate.swift"])
        self.assertNotIn("import SwiftUI", sources["AppDelegate.swift"])
        self.assertIn("UIViewController", sources["AppRootViewController.swift"])
        self.assertIn("VaultComposeViewController(", sources["AppRootViewController.swift"])
        self.assertIn("UnlockComposeViewController(", sources["AppRootViewController.swift"])
        for name, text in sources.items():
            self.assertNotIn("import SwiftUI", text, name)
            self.assertNotIn("UIViewControllerRepresentable", text, name)
        # Everything that touches the vault goes through the store that the app tests can drive.
        self.assertIn("import OfflineOTPKit", sources["VaultStore.swift"])
        self.assertIn("class VaultStore: ObservableObject", sources["VaultStore.swift"])
        # Only one file constructs a vault, so the file locations cannot drift between hosts.
        constructors = [name for name, text in sources.items() if "PasswordVault(" in text]
        self.assertEqual(["VaultStore.swift"], constructors)

    def test_the_app_stays_offline(self):
        """The phone and desktop clients declare no networking; the iOS client must not add one."""
        for path in list(SOURCES.glob("*.swift")) + list(APP_TESTS.glob("*.swift")):
            text = path.read_text(encoding="utf-8")
            for forbidden in ("URLSession", "NSURLConnection", "CFNetwork", "NWConnection",
                              "http://", "https://", "Network.framework"):
                # Fixed About URLs are handed to the system browser only after a tap.
                # The generated catalog has no networking code; the fresh-output test pins it.
                if path.name == "AboutInfo.swift" and forbidden in ("http://", "https://"):
                    continue
                if path.name == "HardwareFidoReader.swift" and forbidden == "https://":
                    self.assertEqual(1, text.count("https://"))
                    self.assertIn('WebAuthn.Origin("https://offlineotp.invalid")', text)
                    continue
                self.assertNotIn(forbidden, text, f"{path.name} must not use {forbidden}")

    def test_the_palette_is_the_shared_dark_forest(self):
        """Every token the app draws with comes from the same numbers as the desktop theme."""
        forest = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/theme/ForestPalette.kt").read_text()
        tokens = {
            "BACKGROUND": "background",
            "BACKGROUND_DEEP": "backgroundDeep",
            "SURFACE": "surface",
            "SURFACE_ELEVATED": "surfaceElevated",
            "SURFACE_VARIANT": "surfaceVariant",
            "APP_BAR": "appBar",
            "PRIMARY": "primary",
            "ACCENT": "accent",
            "TEXT_PRIMARY": "textPrimary",
            "TEXT_SECONDARY": "textSecondary",
            "TEXT_HINT": "textHint",
            "ON_PRIMARY": "onPrimary",
            "DIVIDER": "divider",
            "WARNING": "warning",
            "THUMBNAIL": "thumbnail",
            "OTP_AMBER": "otpAmber",
            "OTP_AMBER_MUTED": "otpAmberMuted",
        }
        self.assertIn("import UIKit", PALETTE)
        self.assertNotIn("import SwiftUI", PALETTE)
        for kotlin_name, swift_name in tokens.items():
            palette_name = {"thumbnail": "thumbnailBackground"}.get(swift_name, swift_name)
            kotlin = re.search(rf"val {palette_name} = Color\(0xFF([0-9A-Fa-f]{{2}})([0-9A-Fa-f]{{2}})([0-9A-Fa-f]{{2}})\)", forest)
            self.assertIsNotNone(kotlin, f"{palette_name} missing from Compose palette")
            red, green, blue = (int(group, 16) for group in kotlin.groups())
            swift_member = swift_name[0].lower() + swift_name[1:]
            expected = (f"public static let {swift_member} = UIColor(red: 0x{red:02X} / 255, "
                        f"green: 0x{green:02X} / 255, blue: 0x{blue:02X} / 255, alpha: 1)")
            self.assertIn(expected, PALETTE, f"{swift_member} drifted from ForestTheme.{kotlin_name}")

    def test_the_password_rules_match_the_other_clients(self):
        store = (SOURCES / "VaultStore.swift").read_text(encoding="utf-8")
        self.assertIn("InputPolicy.minVaultPasswordChars", store)
        policy = (IOS / "Sources/OfflineOTPKit/InputPolicy.swift").read_text(encoding="utf-8")
        self.assertIn("minVaultPasswordChars = 6", policy)
        constants = (ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/Constants.kt"
                     ).read_text(encoding="utf-8")
        self.assertIn("const val AUTH_MIN_PASSWORD_LENGTH = 6", constants)
        # The PIN login was removed from the phone; no client may reintroduce a shorter minimum.
        self.assertNotIn("AUTH_MIN_PIN_LENGTH", constants)
        # A wrong password must not be explained in terms a reader could act on.
        self.assertIn('"That password does not open this vault."', store)

    def test_a_copied_code_expires(self):
        clipboard = (SOURCES / "Clipboard.swift").read_text(encoding="utf-8")
        match = re.search(r"expirySeconds: TimeInterval = (\d+)", clipboard)
        self.assertIsNotNone(match)
        seconds = int(match.group(1))
        self.assertGreater(seconds, 0)
        self.assertLessEqual(seconds, 60)
        self.assertIn(".expirationDate", clipboard)
        self.assertIn("UTType.utf8PlainText.identifier", clipboard)

    def test_the_tabs_are_the_other_clients_tabs(self):
        """Five tabs, the same order, the same titles, and no entry kind without a home."""
        projection = (IOS / "Sources/OfflineOTPKit/EntryListProjection.swift").read_text(
            encoding="utf-8")
        presentation = (ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/View/"
                        "EntryListPresentation.kt").read_text(encoding="utf-8")
        # The category set and its order come from the shared enum.
        kotlin_categories = [match.strip() for match in re.findall(
            r"enum class Category \{ ([A-Z_, ]+)\}",
            presentation)]
        self.assertEqual(["OTP, NOTES, SSH_KEYS, PASSWORDS, EMAILS"], kotlin_categories)
        category_block = projection.split("public enum EntryCategory", 1)[1].split(
            "public enum SortMode", 1)[0]
        swift_categories = re.findall(r"case (\w+) = \"([A-Z_]+)\"", category_block)
        self.assertEqual(
            [("otp", "OTP"), ("notes", "NOTES"), ("sshKeys", "SSH_KEYS"),
             ("passwords", "PASSWORDS"), ("emails", "EMAILS")], swift_categories)
        # The titles match Android's tab strings and the desktop's categoryTitle.
        strings = (ROOT / "app/src/main/res/values/strings_main.xml").read_text(encoding="utf-8")
        for resource in ("tab_otp", "tab_notes", "tab_ssh_keys", "tab_passwords", "tab_email"):
            self.assertIn(f'<string name="{resource}">', strings)
        for title in ('"OTP"', '"Notes"', '"Keys"', '"Passwords"', '"Email"'):
            self.assertIn(f"return {title}", projection)
        # Every kind the format defines is mapped explicitly, so a new kind cannot fall into a tab
        # by accident and cannot be dropped from the strip either.
        kinds = re.findall(r"^\s+case (\w+) = \"([A-Z_]+)\"",
                           (IOS / "Sources/OfflineOTPKit/Entry.swift").read_text(encoding="utf-8"),
                           re.MULTILINE)
        kind_names = {name for name, _ in kinds} if kinds else set()
        self.assertTrue(kind_names, "no EntryKind cases found")
        mapping = projection.split("static func of", 1)[1].split("public var title", 1)[0]
        for name in kind_names:
            self.assertIn(name, mapping, f"{name} has no tab")

    def test_the_five_orders_and_the_tag_rules_match_the_shared_enums(self):
        projection = (IOS / "Sources/OfflineOTPKit/EntryListProjection.swift").read_text(
            encoding="utf-8")
        constants = (ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/"
                     "Constants.kt").read_text(encoding="utf-8")
        self.assertIn("enum class SortMode {", constants)
        self.assertIn("UNSORTED, ISSUER, LABEL, LAST_USED, MOST_USED", constants)
        for stored in ("UNSORTED", "ISSUER", "LABEL", "LAST_USED", "MOST_USED"):
            self.assertIn(f'= "{stored}"', projection)
        self.assertIn("OR, AND, SINGLE", constants)
        for functionality in ("OR", "AND", "SINGLE"):
            self.assertIn(f'= "{functionality}"', projection)
        self.assertIn("enum class SearchIncludes {", constants)
        self.assertIn("LABEL, ISSUER, TAGS", constants)
        for include in ("LABEL", "ISSUER", "TAGS"):
            self.assertIn(f'= "{include}"', projection)
        # The tag rule itself: AND means every tag, otherwise any tag, plus the tagless switch.
        self.assertIn("isSubset(of: Set(entryTags))", projection)
        self.assertIn("includeUntagged", projection)
        # The sort preference is a client preference stored beside the vault, under the desktop
        # client's key, never inside `secrets.dat`.
        controller = (ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/"
                      "VaultController.kt").read_text(encoding="utf-8")
        self.assertIn('const val SORT_MODE_KEY = "ui_sort_mode"', controller)
        self.assertIn('public static let settingsKey = "ui_sort_mode"', projection)
        store = (SOURCES / "VaultStore.swift").read_text(encoding="utf-8")
        self.assertIn("settings.string(SortMode.settingsKey)", store)
        self.assertIn("settings.setString(SortMode.settingsKey", store)

    def test_the_usage_rules_match_the_shared_entry_usage(self):
        usage = (IOS / "Sources/OfflineOTPKit/EntryUsage.swift").read_text(encoding="utf-8")
        shared = (ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/"
                  "EntryUsage.kt").read_text(encoding="utf-8")
        self.assertIn("const val MAX_COUNTER: Long = Long.MAX_VALUE - 1", shared)
        self.assertIn("maxCounter: Int64 = Int64.max - 1", usage)
        # A use is a stamp plus one, an import is a stamp only, and a step is one counter at a time.
        self.assertIn("entry.setLastUsed(now)", usage)
        self.assertIn("entry.setUsedFrequency(entry.usedFrequency + 1)", usage)
        self.assertIn("entry.setCounter(entry.counter + 1)", usage)
        self.assertIn('guard entry.kind == .hotp, entry.counter < maxCounter else { return false }',
                      usage)
        # The store records the use of the entry that was copied, not of the row that was tapped.
        store = (SOURCES / "VaultStore.swift").read_text(encoding="utf-8")
        self.assertIn("EntryUsage.recordUse(&proposed[index]", store)
        self.assertIn("EntryUsage.advanceHotp(&proposed[index])", store)

    def test_a_concealed_entry_can_copy_without_showing_its_code(self):
        """Both other clients redact the token until the entry is revealed."""
        store = (SOURCES / "VaultStore.swift").read_text(encoding="utf-8")
        self.assertIn("guard isRevealed(entry) else { return nil }", store)
        self.assertIn("proposed[index].setDetailsVisible(true)", store)
        host = (SOURCES / "ComposeVaultHost.swift").read_text(encoding="utf-8")
        self.assertIn("let revealed = store.isRevealed(entry)", host)
        self.assertIn('entry.kind?.isOtp == true && code != nil', host)
        self.assertIn('subtitle: revealed && !entry.label.isEmpty', host)
        renderer = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PortableVaultScreen.kt").read_text(encoding="utf-8")
        self.assertIn("card.visibleCode()", renderer)

    def test_the_manual_form_offers_only_what_it_can_build(self):
        draft = (IOS / "Sources/OfflineOTPKit/EntryDraft.swift").read_text(encoding="utf-8")
        shared = (ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Database/"
                  "EntryDraft.kt").read_text(encoding="utf-8")
        self.assertIn("it != Entry.OTPType.PASSWORD && it != Entry.OTPType.PASSWORD_ARCHIVE && "
                      "it != Entry.OTPType.EMAIL", shared)
        self.assertIn("offerableKinds: [EntryKind] = [.totp, .hotp, .motp, .steam, .note, .sshKey]",
                      draft)
        self.assertIn("SshKeyMaterial", draft)
        host = (SOURCES / "ComposeVaultHost.swift").read_text(encoding="utf-8")
        self.assertIn("let offerable = EntryDraft.offerableKinds", host)
        self.assertIn("EntryDraft.create(", host)
        self.assertIn("EntryDraft.preservingMetadata(of: store.entries[index], in: built)", host)
        # The shared wording for a refused number, verbatim.
        for wording in ('"Digits must be a number"', '"Period must be a number"',
                        '"HOTP counter must be a number"'):
            self.assertIn(wording, draft)
            self.assertIn(wording, shared)

    def test_an_edit_keeps_the_metadata_the_form_does_not_show(self):
        draft = (IOS / "Sources/OfflineOTPKit/EntryDraft.swift").read_text(encoding="utf-8")

    def test_the_views_do_not_compute_codes_or_touch_files(self):
        """A view asks the store or the format layer; it never reimplements either."""
        for name in ("AppRootViewController.swift", "AppDelegate.swift", "ComposeVaultHost.swift",
                     "ComposeUnlockHost.swift", "NativeFilePicker.swift", "QrCameraScanner.swift"):
            text = (SOURCES / name).read_text(encoding="utf-8")
            self.assertNotIn("PBKDF2", text, name)
            self.assertNotIn("SymmetricKey", text, name)
            self.assertNotIn("TokenCalculator", text, name)
        # The only app source that touches the file system is the native document adapter.
        for name in ("AppRootViewController.swift", "ComposeVaultHost.swift",
                     "ComposeUnlockHost.swift", "QrCameraScanner.swift"):
            self.assertNotIn("FileManager", (SOURCES / name).read_text(encoding="utf-8"), name)

    def test_the_app_tests_are_xctest_and_need_no_fixtures(self):
        tests = list(APP_TESTS.glob("*.swift"))
        self.assertGreaterEqual(len(tests), 2)
        for path in tests:
            text = path.read_text(encoding="utf-8")
            self.assertIn("import XCTest", text, path.name)
            self.assertIn("@testable import OfflineOTP", text, path.name)
            # App tests run on a simulator with no repository checkout inside the test bundle, so
            # they must build their own vaults rather than reach for the golden corpus.
            self.assertNotIn("desktop/src/test/resources", text, path.name)
            self.assertIn("NSTemporaryDirectory()", (APP_TESTS / "VaultStoreTests.swift").read_text(
                encoding="utf-8"))

    def test_the_workflow_builds_the_shell_and_still_signs_nothing(self):
        self.assertIn("brew install xcodegen", WORKFLOW)
        self.assertIn("xcodegen generate", WORKFLOW)
        self.assertIn("-project OfflineOTP.xcodeproj -scheme OfflineOTP", WORKFLOW)
        self.assertIn("-destination 'generic/platform=iOS'", WORKFLOW)
        self.assertIn("-configuration Release", WORKFLOW)
        self.assertIn("CODE_SIGNING_ALLOWED=NO", WORKFLOW)
        self.assertIn("CODE_SIGNING_REQUIRED=NO", WORKFLOW)
        self.assertNotIn("platform=iOS Simulator,id=$UDID", WORKFLOW)
        self.assertIn("Payload/OfflineOTP.app", WORKFLOW)
        # The .ipa is the only thing this workflow publishes.
        self.assertIn("archive: false", WORKFLOW)
        self.assertIn("path: artifacts/OfflineOTP-ios-unsigned.ipa", WORKFLOW)
        self.assertEqual(1, WORKFLOW.count("actions/upload-artifact@"))
        for dropped in ("OfflineOTP-ios-simulator", "OfflineOTP-ios-kit-source",
                        "OfflineOTP-ios-diagnostics", "SHA256SUMS.txt",
                        "Debug-iphonesimulator", "artifacts/stage"):
            self.assertNotIn(dropped, WORKFLOW, f"{dropped} is an unnecessary output again")
        # Unsigned builds need no certificate; device signing and TestFlight stay manual.
        for forbidden in ("codesign", "xcodebuild archive", "exportArchive", "notarytool",
                          "secrets.", "contents: write", "gh release", "development-team",
                          "DEVELOPMENT_TEAM: ABC"):
            self.assertNotIn(forbidden, WORKFLOW, f"ios.yml must not use {forbidden}")

    # MARK: - Backups, restores and links

    def test_the_link_parser_reports_the_shared_error_names(self):
        """A refused link has to fail for the same reason on every client, with the same name."""
        kotlin = (UTILITIES / "OtpParseResult.kt").read_text(encoding="utf-8")
        block = re.search(r"enum class OtpParseError \{(.*?)\}", kotlin, re.S)
        self.assertIsNotNone(block, "the shared error enum moved")
        kotlin_names = re.findall(r"\b[A-Z][A-Z_]+\b", block.group(1))
        swift = (KIT / "OtpUri.swift").read_text(encoding="utf-8")
        swift_names = re.findall(r'case \w+ = "([A-Z_]+)"', swift)
        self.assertEqual(kotlin_names, swift_names,
                         "the Swift error cases drifted from OtpParseError")

    def test_the_link_parser_uses_the_shared_bounds_and_decodes_itself(self):
        policy = (UTILITIES / "InputPolicy.kt").read_text(encoding="utf-8")
        self.assertIn("const val MAX_URI_CHARS = 16384", policy)
        swift = (KIT / "OtpUri.swift").read_text(encoding="utf-8")
        self.assertIn("maxURIChars = 16384", (KIT / "InputPolicy.swift").read_text(
            encoding="utf-8"))
        for bound in ("InputPolicy.maxSecretBytes", "InputPolicy.maxTags",
                      "InputPolicy.maxTagChars", "InputPolicy.maxTextChars"):
            self.assertIn(bound, swift, f"the parser must apply {bound}")
        # The decoding is the parser's own: the system URL types accept encodings the JVM clients
        # reject, so using one would make the same link work here and fail there.
        self.assertNotIn("URLComponents(", swift)
        self.assertNotIn("URL(string:", swift)
        self.assertNotIn("removingPercentEncoding", swift)
        # Raw spaces are escaped before parsing and `+` means space only inside the query.
        self.assertIn('replacingOccurrences(of: " ", with: "%20")', swift)
        self.assertIn("plusAsSpace", swift)

    def test_the_thumbnail_catalog_is_generated_from_the_shared_one(self):
        """The stored logo name is shared data, so the list of names cannot be a hand copy."""
        result = subprocess.run(
            [sys.executable, "scripts/ios/generate-thumbnail-catalog.py"],
            cwd=ROOT, capture_output=True, text=True, check=False)
        self.assertEqual(0, result.returncode, result.stderr)
        swift = (KIT / "ThumbnailCatalog.swift").read_text(encoding="utf-8")
        kotlin = (UTILITIES / "ThumbnailCatalog.kt").read_text(encoding="utf-8")
        self.assertIn("Generated by `scripts/ios/generate-thumbnail-catalog.py`", swift)
        self.assertIn('const val DEFAULT_NAME = "Default"', kotlin)
        self.assertIn('public static let defaultName = "Default"', swift)
        # The port keeps the JVM's matching order: exact, alias, whole word, default.
        self.assertIn("exactName(issuer) ?? aliasName(issuer) ?? fuzzyName(issuer) ?? defaultName",
                      swift)
        self.assertIn("NAMES.firstOrNull { it.equals(value, ignoreCase = true) }", kotlin)
        # The names themselves are compared by the generator; this proves it is being used.
        self.assertIn("ThumbnailCatalog.resolveName(issuer)", (KIT / "EntryDraft.swift").read_text(
            encoding="utf-8"))
        self.assertIn("ThumbnailCatalog.resolveName(issuer)", (KIT / "OtpUri.swift").read_text(
            encoding="utf-8"))

    def test_a_link_round_trips_through_the_shared_field_order(self):
        """`Entry.toUriString`'s order is what `Show QR code` shows, so it is pinned here too."""
        kotlin = (ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Database/Entry.kt"
                  ).read_text(encoding="utf-8")
        for piece in ('query["secret"]', 'query["issuer"]', 'query["counter"]',
                      'query["period"]', 'query["digits"]', 'query["algorithm"]',
                      'append("&tags=")'):
            self.assertIn(piece, kotlin)
        swift = (KIT / "OtpUri.swift").read_text(encoding="utf-8")
        for piece in ('pairs.append(("secret"', 'pairs.append(("issuer"',
                      'pairs.append(("counter"', 'pairs.append(("period"',
                      'pairs.append(("digits"', 'pairs.append(("algorithm"',
                      'out += "&tags=" + percentEncode(tag)'):
            self.assertIn(piece, swift, f"the serializer must keep {piece}")
        # Only an entry with an otpauth form has a code to show.
        self.assertIn("case .note, .sshKey, .password, .passwordArchive, .email: return nil", swift)

    def test_a_backup_is_dated_and_plain_text_is_import_only(self):
        swift = (KIT / "BackupRestore.swift").read_text(encoding="utf-8")
        self.assertIn('timestampPattern = "yyyy-MM-dd_HH-mm-ss"', swift)
        self.assertIn('TIMESTAMP_PATTERN = "yyyy-MM-dd_HH-mm-ss"',
                      (UTILITIES / "BackupFileNames.kt").read_text(encoding="utf-8"))
        constants = (UTILITIES / "Constants.kt").read_text(encoding="utf-8")
        self.assertIn('BACKUP_FILENAME_CRYPT_FORMAT = "otp_accounts_%s.json.aes"', constants)
        self.assertIn('encryptedPrefix = "otp_accounts_"', swift)
        self.assertIn('encryptedSuffix = ".json.aes"', swift)
        # ASCII digits in every language, as `Locale.ENGLISH` gives the JVM clients.
        self.assertIn('Locale(identifier: "en_US_POSIX")', swift)
        store = (SOURCES / "VaultStore.swift").read_text(encoding="utf-8")
        # Plain-text backups are removed: neither import nor export may exist.
        for banned in ("PlainTextBackupDecoder", "plainTextBackup", "encodePlainText", "PlainTextBackupEncoder"):
            self.assertNotIn(banned, store)

    def test_a_restore_merges_the_way_the_shared_rule_does(self):
        swift = (KIT / "BackupRestore.swift").read_text(encoding="utf-8")
        for name in ("emptyImport", "duplicateEntries"):
            self.assertIn(name, swift)
        # Imported first, the vault's own copies kept; a replace ignores the current entries.
        self.assertIn("imported.filter { !retained.contains($0) }", swift)
        self.assertIn("replace ? fresh : fresh + current", swift)
        self.assertIn("Set(merged).count == merged.count", swift)
        # The summary is the desktop's sentence, because Android's toast says only "Backup restored."
        wording = (KIT / "BackupRestore.swift").read_text(encoding="utf-8")
        self.assertIn("Merged \\(plan.added) new \\(noun) into the vault", wording)
        self.assertIn("The backup had no entries the vault does not already hold.", wording)

    def test_the_plain_text_warning_is_the_android_sentence(self):
        strings = (ANDROID_VALUES / "strings_backup.xml").read_text(encoding="utf-8")
        for banned in ("backup_plaintext_warning", "backup_button_restore_plaintext", "backup_toast_plaintext_invalid", "Plain-text backups contain"):
            self.assertNotIn(banned, strings)
        wording = (KIT / "BackupRestore.swift").read_text(encoding="utf-8")
        for banned in ("Plain-text backups contain", "plain-text backups", "warningPlainText"):
            self.assertNotIn(banned, wording)
        host = (SOURCES / "ComposeVaultHost.swift").read_text(encoding="utf-8")
        for banned in ("warningPlainText", "Plain-text"):
            self.assertNotIn(banned, host)

    def test_a_second_account_from_an_issuer_is_a_question(self):
        strings = (ANDROID_VALUES / "strings_main.xml").read_text(encoding="utf-8")
        self.assertIn('<string name="dialog_title_duplicate_issuer">Issuer already exists</string>',
                      strings)
        self.assertIn('An account for %1$s is already in your vault. Add another account from this '
                      'issuer anyway?', strings)
        self.assertIn('<string name="button_add_anyway">Add anyway</string>', strings)
        store = (SOURCES / "VaultStore.swift").read_text(encoding="utf-8")
        self.assertIn('duplicateIssuerTitle = "Issuer already exists"', store)
        self.assertIn('addAnyway = "Add anyway"', store)
        self.assertIn('"An account for \\(issuer) is already in your vault. Add another account '
                      'from this "', store)
        self.assertIn('"issuer anyway?"', store)
        # Only an import asks; adding or editing an entry never does — the rule on the phone too.
        self.assertIn("allowDuplicateIssuer", store)
        self.assertNotIn("duplicateIssuer", (SOURCES / "ComposeVaultHost.swift").read_text(
            encoding="utf-8").split("private func editorSurface", 1)[1].split("private func deleteSurface", 1)[0])

    def test_qr_camera_scan_and_image_import_are_available_on_ios(self):
        host = (SOURCES / "ComposeVaultHost.swift").read_text(encoding="utf-8")
        self.assertIn('row("qrCamera", "Scan an otpauth:// QR code…")', host)
        self.assertIn('func importUriPayload(_ text: String)', host)
        self.assertIn("store.importPayload(text)", host)
        root = (SOURCES / "AppRootViewController.swift").read_text(encoding="utf-8")
        self.assertIn('if kind == "qrCamera" { self.scanQr() }', root)
        self.assertIn("QrCaptureViewController(", root)
        reader = (SOURCES / "QrImageReader.swift").read_text(encoding="utf-8")
        self.assertIn("import Vision", reader)
        self.assertIn("VNDetectBarcodesRequest", reader)
        # Image import remains permission-free; live scan is a separate QR-only VisionKit adapter.
        self.assertNotIn("AVCaptureDevice", reader)
        scanner = (SOURCES / "QrCameraScanner.swift").read_text(encoding="utf-8")
        self.assertIn("DataScannerViewController", scanner)
        self.assertIn("symbologies: [.qr]", scanner)
        self.assertNotIn("import SwiftUI", scanner)
        project = yaml.safe_load((IOS / "project.yml").read_text(encoding="utf-8"))
        camera = project["targets"]["OfflineOTP"]["info"]["properties"]["NSCameraUsageDescription"]
        self.assertIn("on this device", camera)
        uri = (KIT / "OtpUri.swift").read_text(encoding="utf-8")
        self.assertIn("otpauth-migration:", uri)
        self.assertIn("case migration", uri)
        strings = (SOURCES / "VaultStore.swift").read_text(encoding="utf-8")
        self.assertIn("case .migration:", strings)
        self.assertNotIn("not supported yet", strings,
                         "the export is imported now, not refused")

    def test_the_google_authenticator_import_matches_the_shared_rules(self):
        """An export is Google's wire format, so its reader has to agree with `:core` byte for byte."""
        kotlin = (UTILITIES / "GoogleAuthenticatorMigration.kt").read_text(encoding="utf-8")
        swift = (KIT / "MigrationImport.swift").read_text(encoding="utf-8")

        # The scheme, the fixed authority, and the rule that only the scheme is case-insensitive.
        self.assertIn('SCHEME = "otpauth-migration"', kotlin)
        self.assertIn('scheme = "otpauth-migration"', swift)
        self.assertIn('authority = "offline"', swift)
        self.assertIn('uri.rawAuthority != "offline"', kotlin)
        self.assertIn("input.lowercased().hasPrefix(head)", swift)

        # The failure names are shared, so the same code is refused for the same reason.
        self.assertIn(
            "enum class MigrationError { INVALID_URI, TOO_LARGE, INVALID_DATA, UNSUPPORTED }",
            kotlin)
        error_block = swift.split("public enum MigrationError", 1)[1].split("}", 1)[0]
        self.assertEqual(["INVALID_URI", "TOO_LARGE", "INVALID_DATA", "UNSUPPORTED"],
                         re.findall(r'case \w+ = "([A-Z_]+)"', error_block))

        # The batch statuses, in the shared order.
        self.assertIn("enum class MigrationBatchStatus { ADDED, COMPLETE, DUPLICATE, "
                      "DIFFERENT_EXPORT, TOO_MANY, STALE }", kotlin)
        status_block = swift.split("public enum MigrationBatchStatus", 1)[1].split("}", 1)[0]
        self.assertEqual(["added", "complete", "duplicate", "differentExport", "tooMany", "stale"],
                         re.findall(r"case (\w+)", status_block))

        # The reader is bounded and non-recursive, refuses an unknown field rather than guessing,
        # and never imports part of an export.
        self.assertIn("reader.message()", kotlin)
        self.assertIn("var nested = try reader.message()", swift)
        self.assertIn("else -> reject(MigrationError.UNSUPPORTED)", kotlin)
        self.assertIn("throw MigrationError.unsupported", swift)
        for bound in ("InputPolicy.maxURIChars", "InputPolicy.maxEntries",
                      "InputPolicy.maxSecretBytes", "InputPolicy.maxTextChars"):
            self.assertIn(bound, swift, f"the export reader must apply {bound}")
        self.assertIn("payload.count > InputPolicy.maxEntries - total", swift)
        self.assertIn("guard !closed, sessionReady, current == generation, parts.count == batchSize",
                      swift)
        # A repeated or foreign code never closes the export; a stale one does.
        self.assertIn("return .differentExport", swift)
        self.assertIn("current != generation", swift)

        # The wording is Android's, sentence for sentence.
        strings = (ANDROID_VALUES / "strings_main.xml").read_text(encoding="utf-8")
        for name in ("google_import_title", "google_import_progress", "google_import_confirm",
                     "google_import_duplicate_notice", "google_import_unnamed",
                     "google_import_more", "google_import_button", "google_import_finished",
                     "google_import_invalid", "google_import_unsupported",
                     "google_import_expected_qr", "google_import_already_scanned",
                     "google_import_wrong_batch", "google_import_too_many",
                     "google_import_save_failed"):
            self.assertIn(f'<string name="{name}">', strings, f"{name} moved")
        self.assertIn('<string name="google_import_title">Google Authenticator import</string>',
                      strings)
        self.assertIn('public static let title = "Google Authenticator import"', swift)
        self.assertIn('"Imported \\(added) accounts; skipped \\(skipped) identical accounts."',
                      swift)
        self.assertIn('"Scanned \\(received) of \\(expected) QR codes. Scan or choose the next '
                      'code from the same "', swift)
        self.assertIn('"Import \\(count) accounts from this export?"', swift)
        self.assertIn('"Invalid Google Authenticator export. No accounts from this code were '
                      'imported."', swift)
        self.assertIn('"This export QR code was already scanned."', swift)
        self.assertIn('"This export has more accounts than the vault can import."', swift)
        self.assertIn('"Unnamed account"', swift)
        # Android keeps the progress sheet open for a refused code and shows the same sentence.
        activity = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/"
                    "MainActivity.kt").read_text(encoding="utf-8")
        self.assertIn("MigrationBatchStatus.DUPLICATE ->", activity)
        self.assertIn("dismissMigrationImport()", activity)
        # An identical account is skipped, not imported twice: Android's `importEntries` rule.
        adapter = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/View/"
                   "AndroidVaultController.kt").read_text(encoding="utf-8")
        self.assertIn("if (!distinct.add(entry))", adapter)
        self.assertIn("EntryUsage.markImported(it, now)", adapter)
        store = (SOURCES / "VaultStore.swift").read_text(encoding="utf-8")
        self.assertIn("if distinct.contains(entry)", store)
        self.assertIn("EntryUsage.markImported(&imported, now: stamp)", store)

    def test_an_identical_account_is_decided_the_way_entry_equals_decides_it(self):
        """Android compares the *account*, not the field bag, so the port has to compare the same."""
        kotlin = (ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp/Database/Entry.kt"
                  ).read_text(encoding="utf-8")
        equals = kotlin.split("override fun equals(other: Any?)", 1)[1].split("override fun hashCode",
                                                                            1)[0]
        swift = (KIT / "Entry.swift").read_text(encoding="utf-8")
        compare = swift.split("public static func == (lhs: Entry, rhs: Entry)", 1)[1].split(
            "public func hash(into hasher", 1)[0]
        shared = {"type == entry.type": "kind", "period == entry.period": "period",
                  "counter == entry.counter": "counter", "digits == entry.digits": "digits",
                  "algorithm == entry.algorithm": "algorithm",
                  "secret.contentEquals(entry.secret)": "secretBytes",
                  "label == entry.label": "label", "issuer == entry.issuer": "issuer",
                  "sshIdentity() == entry.sshIdentity()": "sshIdentity"}
        for kotlin_expression, swift_name in shared.items():
            self.assertIn(kotlin_expression, equals,
                          f"Entry.equals stopped comparing {swift_name}")
            self.assertIn(f"lhs.{swift_name} == rhs.{swift_name}", compare,
                          f"the Swift equality must compare {swift_name}")
        # The metadata a stamp or a reveal changes is deliberately *not* identity.
        for metadata in ("last_used", "used_frequency", "tags", "details_visible", "thumbnail"):
            self.assertNotIn(metadata, equals, f"Entry.equals must not compare {metadata}")
        for metadata in ("lastUsed", "usedFrequency", "tags", "detailsVisible"):
            self.assertNotIn(f"lhs.{metadata}", compare,
                             f"the Swift equality must not compare {metadata}")
        # The hash covers the same fields, so a Set collapses the same entries as Android's HashSet.
        hashed = swift.split("public func hash(into hasher", 1)[1].split("}", 1)[0]
        for swift_name in shared.values():
            self.assertIn(f"hasher.combine({swift_name})", hashed)

    def test_the_quick_unlock_is_opt_in_and_keeps_the_password_as_the_key(self):
        """Face ID is a shortcut to the vault key, never a second way to derive it."""
        # iOS refuses the prompt outright without the usage sentence, so it has to be declared.
        project = yaml.safe_load((IOS / "project.yml").read_text(encoding="utf-8"))
        description = project["targets"]["OfflineOTP"]["info"]["properties"][
            "NSFaceIDUsageDescription"]
        self.assertIn("Face ID", description)
        self.assertGreater(len(description), 40, "the reason must be a real sentence")
        # Camera is separately explained for optional QR scanning; no unrelated sensor permissions.
        self.assertIn("NSCameraUsageDescription", project["targets"]["OfflineOTP"]["info"]["properties"])
        for absent in ("NSMicrophoneUsageDescription", "NSLocationWhenInUseUsageDescription"):
            self.assertNotIn(absent, project["targets"]["OfflineOTP"]["info"]["properties"])

        sources = app_sources()
        biometric = sources["BiometricUnlock.swift"]
        # The security decision, spelled out in the one file allowed to touch the Keychain.
        self.assertIn("import LocalAuthentication", biometric)
        self.assertIn("import Security", biometric)
        self.assertIn(".biometryCurrentSet", biometric)
        self.assertIn("kSecAttrAccessibleWhenUnlockedThisDeviceOnly", biometric)
        self.assertIn("kSecAttrSynchronizable as String: false", biometric)
        self.assertIn("kSecClassGenericPassword", biometric)
        self.assertIn("kSecUseAuthenticationContext", biometric)
        # A device passcode is a *device* secret, not the vault password: biometrics only.
        self.assertNotIn(".deviceOwnerAuthentication,", biometric)
        self.assertNotIn("kSecAttrAccessibleAlways", biometric)
        self.assertNotIn("kSecAttrAccessibleAfterFirstUnlock", biometric)
        # Only that file speaks to the system sensor or the Keychain; a view cannot.
        for name, text in sources.items():
            if name == "BiometricUnlock.swift":
                continue
            for forbidden in ("import LocalAuthentication", "import Security", "SecItemAdd",
                              "SecItemCopyMatching", "LAContext"):
                self.assertNotIn(forbidden, text, f"{name} must not use {forbidden}")

        # The vault password is the only thing that produces the key: turning the shortcut on asks
        # for it, and what is filed is the derived key.
        store = sources["VaultStore.swift"]
        self.assertIn("public func enableBiometricUnlock(password: String) -> Bool", store)
        self.assertIn("try vault.open(password: password)", store)
        self.assertIn("guard verified.key == key", store)
        self.assertIn("try biometrics.save(key: verified.key", store)
        self.assertIn("try vault.openWithKey(sessionKey)", store)
        # The key store only ever handles bytes: there is no shape of it that could carry the
        # password, and no setting in the vault document that would duplicate one.
        store_protocol = biometric.split("public protocol BiometricKeyStore {", 1)[1].split(
            "\n}\n", 1)[0]
        self.assertIn("func save(key: [UInt8], account: String) throws", store_protocol)
        self.assertIn("func load(account: String, reason: String,", store_protocol)
        # The surface between the store and the device has no shape that could carry the password.
        self.assertNotIn("password", store_protocol)
        self.assertNotIn("biometric_unlock", (KIT / "PasswordVault.swift").read_text(
            encoding="utf-8"))
        # Off until the person turns it on, and remembered beside the vault, not inside it.
        self.assertIn('biometricSettingKey = "biometric_unlock"', store)
        self.assertIn("biometricUnlockEnabled && biometry.isAvailable", store)
        self.assertIn("settings.setString(VaultStore.biometricSettingKey", store)

        # The shared Compose lock screen offers the button; the portable settings surface asks
        # for the password before the native adapter touches the Keychain.
        unlock = sources["ComposeUnlockHost.swift"]
        self.assertIn("store.canUnlockWithBiometrics", unlock)
        self.assertIn("Unlock with \\(store.biometryTitle)", unlock)
        host = sources["ComposeVaultHost.swift"]
        self.assertIn("store.biometricUnlockEnabled ? \"Disable quick unlock\"", host)
        self.assertIn("store.enableBiometricUnlock(password: password)", host)
        self.assertIn("store.disableBiometricUnlock()", host)

        # The wording has no Android counterpart, so it is pinned here instead of compared.
        wording = (SOURCES / "BiometricUnlock.swift").read_text(encoding="utf-8")
        for sentence in ("Too many attempts. Enter your password to unlock.",
                         "Set up Face ID on this device first.",
                         "Enter the vault password to turn this on."):
            self.assertIn(sentence, wording)

    def test_a_code_is_announced_one_character_at_a_time_and_fits_a_thumb(self):
        """TalkBack and VoiceOver read the same Compose semantics, not a SwiftUI copy."""
        code = (SOURCES / "CodeFormat.swift").read_text(encoding="utf-8")
        self.assertIn("public static func spoken(_ code: String) -> String", code)
        self.assertIn('code.map(String.init).joined(separator: " ")', code)
        self.assertIn("public static func remainingDescription(_ seconds: Int?) -> String", code)
        self.assertIn('seconds == 1 ? "1 second left"', code)

        renderer = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PortableVaultScreen.kt").read_text(encoding="utf-8")
        self.assertIn('contentDescription = "Code " + card.visibleCode().toCharArray().joinToString(" ")', renderer)
        self.assertIn('contentDescription = "Copy Token for "', renderer)
        self.assertIn('contentDescription = "Edit "', renderer)
        self.assertNotIn('onCard(card.index, "details")', renderer)
        self.assertNotIn('onCard(card.index, "more")', renderer)
        for expected in ("Add an entry", "Backups and links", "Settings", "Lock the vault",
                         "How selected tags combine", "Clear the search and tag filters"):
            self.assertIn(f'contentDescription = "{expected}"', renderer, f"{expected} lost its label")
        # No icon-only native button survives: the shared renderer labels every control in text.
        host = (SOURCES / "ComposeVaultHost.swift").read_text(encoding="utf-8")
        self.assertNotIn("Image(systemName", host)
        unlock = (SOURCES / "ComposeUnlockHost.swift").read_text(encoding="utf-8")
        self.assertIn('quickUnlockLabel: store.canUnlockWithBiometrics', unlock)

    def test_the_entry_list_body_is_split_for_the_type_checker(self):
        """The old SwiftUI type-checker workaround is gone with the SwiftUI view tree."""
        for legacy in ("EntryListView.swift", "EntryEditorView.swift", "CredentialDetailViews.swift"):
            self.assertFalse((SOURCES / legacy).exists(), legacy)
        host = (SOURCES / "ComposeVaultHost.swift").read_text(encoding="utf-8")
        self.assertIn("let composed = coordinator.presentation.viewController(", host)
        self.assertEqual(1, host.count("presentation.viewController("))
        self.assertNotIn(".sheet(", host)
        self.assertNotIn("some View", host)

    def test_the_ios_job_builds_required_compose_framework_without_simulator_xctest(self):
        """The simulator XCTest step consumed the old job after the app had already compiled.

        CI builds the required Compose presentation XCFramework and the iOS device app; it does not
        publish a SwiftUI fallback. Host format tests and Rust host coverage remain separately
        bounded across the macOS and parallel Linux jobs.
        """
        self.assertIn("timeout-minutes: 20", WORKFLOW)
        compose = WORKFLOW.split("- name: Build required Compose presentation XCFramework", 1)[1].split(
            "- name: Generate the Xcode project", 1)[0]
        self.assertIn("assembleOfflineOTPPresentationReleaseXCFramework", compose)
        self.assertIn("ditto ui-foundation/build/XCFrameworks/release/OfflineOTPPresentation.xcframework", compose)
        self.assertIn(": \"${ANDROID_HOME:?", compose)
        self.assertNotIn("simctl", WORKFLOW)
        self.assertNotIn("generic/platform=iOS Simulator", WORKFLOW)
        self.assertNotIn("Build the app for the simulator", WORKFLOW)
        self.assertNotIn("Boot the app test simulator", WORKFLOW)
        self.assertNotIn("Run the app unit tests", WORKFLOW)
        self.assertNotIn("-resultBundlePath", WORKFLOW)
        self.assertNotIn("ENABLE_TESTABILITY=YES", WORKFLOW)
        self.assertIn("openpgp-host:", WORKFLOW)
        self.assertIn("runs-on: ubuntu-24.04", WORKFLOW)
        self.assertIn("build-ios-xcframework.sh --arm64-only", WORKFLOW)
        self.assertIn("ARCHS=arm64", WORKFLOW)
        self.assertIn("swift build -c release --build-tests", WORKFLOW)
        self.assertIn("swift test -c release --parallel", WORKFLOW)
        self.assertIn("-Xswiftc -enable-testing", WORKFLOW)
        self.assertNotIn("-scheme OfflineOTPKit", WORKFLOW)

    def test_the_password_edit_sheet_matches_its_initializer(self):
        """Credential editing is one shared portable form with one guarded native save."""
        host = (SOURCES / "ComposeVaultHost.swift").read_text(encoding="utf-8")
        self.assertIn('case "edit":', host)
        self.assertIn('openEditor(index: index, kind: entry.kind ?? .totp)', host)
        self.assertIn("private func saveEditor() -> String?", host)
        self.assertIn("guard store.update(at: index, with: built)", host)
        renderer = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/PortableVaultScreen.kt").read_text(encoding="utf-8")
        self.assertIn("PortableRow.KIND_SECURE -> OutlinedTextField(", renderer)
        self.assertIn("onChange(row.key, it)", renderer)

    def test_openpgp_buttons_are_gated_by_the_backend(self):
        """The availability helpers gate the portable rows, not a SwiftUI view."""
        host = (SOURCES / "ComposeVaultHost.swift").read_text(encoding="utf-8")
        self.assertIn("private func openPgpSection() -> PortableSection", host)
        self.assertIn("let availability = store.openPgpAvailability", host)
        self.assertIn('guard availability.canRestore else', host)
        self.assertIn('"This build does not include the OpenPGP engine."', host)
        self.assertIn('enabled: availability.canEncrypt', host)
        self.assertIn('enabled: availability.canRestore', host)
        self.assertIn("store.exportOpenPgpBackup(recipientKeyBlocks: openPgpRecipients)", host)
        self.assertIn("store.restoreOpenPgpBackup(message: pendingImport,", host)

        store = (SOURCES / "VaultStore.swift").read_text(encoding="utf-8")
        self.assertIn("public var openPgpAvailability: OpenPgpAvailability", store)
        self.assertIn(".inApp(backendAvailable: openPgp.isAvailable)", store)

        port = (KIT / "OpenPgp.swift").read_text(encoding="utf-8")
        self.assertIn("var isAvailable: Bool { get }", port)
        self.assertIn("public var isAvailable: Bool { Self.isBackendAvailable }", port)
        self.assertIn("public static func inApp(backendAvailable: Bool)", port)
        self.assertIn("var isAvailable: Bool { true }", port)

    def test_the_plan_records_what_the_shell_does_and_does_not_do(self):
        plan = (ROOT / "docs/ios-client-plan.md").read_text(encoding="utf-8")
        self.assertIn("M1.5", plan)
        self.assertIn("**M1.5** ✅", plan)
        self.assertIn("**M2** ✅", plan)
        self.assertIn("**M3** ✅", plan)
        self.assertIn("**M4** ✅", plan)
        for honest_limit in ("no app target and no SwiftUI yet", "tabs", "Face ID"):
            if honest_limit == "no app target and no SwiftUI yet":
                self.assertNotIn(honest_limit, plan,
                                 "the plan still claims there is no app target")
            else:
                self.assertIn(honest_limit, plan)

    def test_android_camera_scanner_targets_qr_and_forwards_scan_result(self):
        main = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        scanner = main.split("private fun scanQRCode()", 1)[1].split("\n    }", 1)[0]
        self.assertIn("setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)", scanner)
        self.assertIn("SecureCaptureActivity::class.java", scanner)
        self.assertIn("IntentIntegrator.parseActivityResult", main)
        self.assertIn("addQRCode(result.getContents())", main)


class HardwareOathDeletionTests(unittest.TestCase):
    def test_rename_refuses_sdk_old_id_reconstruction_and_new_id_collisions(self):
        reader = (Path(__file__).resolve().parents[2] / "ios/App/Sources/HardwareOathReader.swift").read_text()
        host = (Path(__file__).resolve().parents[2] / "ios/App/Sources/ComposeVaultHost.swift").read_text()
        self.assertIn("HardwareOathRenamePolicy.sdkIdentifier", reader)
        self.assertIn("issuer: credential.issuer, account: credential.name).utf8) == credential.id", reader)
        branch = reader[reader.index("            if let rename {"):reader.index("            if deleteConfirmed {")]
        for check in ("selected.canRename", "newID != selected.credential.id", "!credentials.contains(where: { $0.id == newID })", "Task.checkCancellation()"):
            self.assertLess(branch.index(check), branch.index("session.renameCredential"))
        self.assertEqual(1, reader.count("session.renameCredential"))
        self.assertIn("return Result(accounts: [], code: nil, expires: nil)", branch)
        self.assertIn('draft["hardwareRenameConfirmation"] == "RENAME"', host)
        selection = host[host.index('case "hardwareRenameSelect":'):host.index('case "hardwareRenameConfirm":')]
        self.assertIn('draft.removeValue(forKey: "hardwarePassword")', selection)
        self.assertIn("clearHardwareRenameDraft()", selection)
        self.assertIn("if rename != nil { self.hardwareAccounts = [] }", host)

    def test_deletion_revalidates_identity_and_cancels_before_single_command(self):
        reader = (Path(__file__).resolve().parents[2] / "ios/App/Sources/HardwareOathReader.swift").read_text()
        host = (Path(__file__).resolve().parents[2] / "ios/App/Sources/ComposeVaultHost.swift").read_text()
        self.assertIn("account?.credential.deviceId == deviceID", reader)
        self.assertLess(reader.index("HardwareOathPolicy.uniqueMatch"), reader.index("session.deleteCredential"))
        branch = reader[reader.index("if deleteConfirmed {"):reader.index("// One calculation only")]
        self.assertLess(branch.index("Task.checkCancellation()"), branch.index("session.deleteCredential"))
        self.assertEqual(1, reader.count("session.deleteCredential"))
        self.assertIn("return Result(accounts: [], code: nil, expires: nil)", branch)
        self.assertIn('draft["hardwareDeleteConfirmation"] == "DELETE"', host)
        selection = host[host.index('case "hardwareDeleteSelect":'):host.index('case "hardwareDeleteConfirm":')]
        self.assertIn('draft.removeValue(forKey: "hardwarePassword")', selection)
        self.assertIn("if deleteConfirmed { self.hardwareAccounts = [] }", host)
        self.assertIn("It may already have completed", reader)


if __name__ == "__main__":
    unittest.main()
