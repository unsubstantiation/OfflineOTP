"""Source-level guards for the experimental hardware-key integration.

These checks do not emulate CTAP/OATH or Android lifecycle and do not establish interoperability.
"""
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main"
KOTLIN = APP / "kotlin/com/fairuniversal/offlineotp"
CORE_KOTLIN = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def source(path):
    return (KOTLIN / path).read_text(encoding="utf-8")


def core_source(path):
    return (CORE_KOTLIN / path).read_text(encoding="utf-8")


class HardwareKeyWiringTest(unittest.TestCase):
    def test_manifest_is_private_and_transports_are_optional(self):
        root = ET.parse(APP / "AndroidManifest.xml").getroot()
        permissions = {p.get(ANDROID + "name") for p in root.findall("uses-permission")}
        self.assertIn("android.permission.NFC", permissions)
        self.assertNotIn("android.permission.INTERNET", permissions)
        features = {f.get(ANDROID + "name"): f.get(ANDROID + "required") for f in root.findall("uses-feature")}
        self.assertEqual("false", features["android.hardware.nfc"])
        self.assertEqual("false", features["android.hardware.usb.host"])
        activities = {a.get(ANDROID + "name"): a for a in root.find("application").findall("activity")}
        for name in (".Activities.HardwareKeyActivity", ".Activities.HardwareUnlockActivity"):
            self.assertEqual("false", activities[name].get(ANDROID + "exported"))
            self.assertFalse(activities[name].findall("intent-filter"))

    def test_pinned_sdk_and_main_unlock_only(self):
        gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
        # The Android transport stays on 3.1.0 (minCompileSdk 36); see the comment in app/build.gradle.
        self.assertIn("com.yubico.yubikit:android:3.1.0", gradle)
        for module in ("oath", "fido"):
            self.assertIn(f"com.yubico.yubikit:{module}:3.2.1", gradle)
        main = source("Activities/MainActivity.kt")
        settings = source("Activities/SettingsActivity.kt")
        auth = source("Activities/AuthenticateActivity.kt")
        self.assertIn("challenge.putExtra(Constants.EXTRA_AUTH_ALLOW_HARDWARE, true)", main)
        self.assertNotIn("EXTRA_AUTH_ALLOW_HARDWARE", settings)
        self.assertIn("newEncryption.isNullOrEmpty() && authMethod == AuthMethod.PASSWORD", auth)
        self.assertIn("settings?.getEncryption() == Constants.EncryptionType.PASSWORD", auth)
        self.assertIn("hardwarePending && resultCode == RESULT_OK && VaultSession.isCurrent(hardwareGeneration)", auth)
        self.assertIn("finishWithResult(true, null, hardwareGeneration)", auth)

    def test_unwrap_is_verified_and_secrets_do_not_cross_intents(self):
        unlock = source("Activities/HardwareUnlockActivity.kt")
        self.assertIn("HardwareUnlockEnvelope.open(envelope, prf)", unlock)
        self.assertIn("DatabaseHelper.readVault(this, SecretKeySpec(raw, \"AES\"))", unlock)
        self.assertIn("VaultSession.offerDerivedKey(raw, generation)", unlock)
        self.assertIn("current.contentEquals(envelope)", unlock)
        self.assertIn("raw?.fill(0)", unlock)
        self.assertNotIn("putExtra(", unlock)
        self.assertIn('"userVerification" to "required"', core_source("Utilities/HardwareFidoClient.kt"))
        self.assertIn('"prf" to', core_source("Utilities/HardwareFidoClient.kt"))

    def test_irreversible_commands_check_permission_and_never_overwrite(self):
        oath = core_source("Utilities/HardwareOathClient.kt")
        self.assertIn("session.credentials.any { it.id.contentEquals(data.id) }", oath)
        self.assertIn('if (!permitted()) throw IllegalStateException("Hardware operation was canceled")', oath)
        self.assertLess(oath.index("if (!permitted())", oath.index("fun copyToKey")), oath.index("session.putCredential"))
        calculate = oath[oath.index("fun calculate"):]
        self.assertLess(calculate.index("if (!permitted())"), calculate.index("session.calculateCode"))
        self.assertIn("data.destroy()", oath)
        self.assertIn("seed.fill(0)", oath)
        deletion = oath[oath.index("fun deleteFromKey"):oath.index("fun canCopy")]
        for check in ("require(confirmed)", "session.deviceId == deviceId", "it.id.contentEquals(credential.id)",
                      "it.oathType == credential.oathType", "it.issuer == credential.issuer",
                      "it.accountName == credential.accountName", "require(matches.size == 1)"):
            self.assertIn(check, deletion)
        self.assertLess(deletion.index("if (!permitted())"), deletion.index("session.deleteCredential"))
        self.assertNotIn("calculateCode", deletion)

    def test_android_deletion_uses_shared_confirmation_and_retires_stale_accounts(self):
        screen = source("Activities/HardwareKeyActivity.kt")
        deletion = screen[screen.index("private fun confirmAccountDeletion"):screen.index("private fun confirmHotp")]
        self.assertIn("HardwareOathDeleteComposeView", deletion)
        self.assertNotIn("String(pin)", deletion)
        self.assertIn("revision != listRevision || !sessionReady()", deletion)
        self.assertLess(deletion.index("renderAccounts(HardwareOathClient.Accounts(deviceId, emptyList()))"),
                        deletion.index("HardwareOathClient.deleteFromKey"))
        self.assertIn("interruptedMessage = R.string.hardware_delete_uncertain", deletion)

    def test_rename_collision_identity_and_cancellation_checks_precede_one_command(self):
        oath = core_source("Utilities/HardwareOathClient.kt")
        rename = oath[oath.index("fun renameOnKey"):oath.index("fun canCopy")]
        for check in ("require(confirmed)", "session.deviceId == deviceId", "it.id.contentEquals(credential.id)",
                      "it.period == credential.period", "require(matches.size == 1)",
                      "credentials.none { it.id.contentEquals(newId) }"):
            self.assertIn(check, rename)
            self.assertLess(rename.index(check), rename.index("session.renameCredential"))
        self.assertLess(rename.index("if (!permitted())"), rename.index("session.renameCredential"))
        self.assertEqual(1, rename.count("session.renameCredential"))
        self.assertNotIn("calculateCode", rename)
        screen = source("Activities/HardwareKeyActivity.kt")
        native = screen[screen.index("private fun confirmAccountRename"):screen.index("private fun confirmAccountDeletion")]
        self.assertIn("HardwareOathRenameComposeView", native)
        self.assertIn("val ownsPrompt = composeDialog === view", native)
        self.assertIn("!ownsPrompt || revision != listRevision", native)
        self.assertIn("revision != listRevision || !sessionReady()", native)
        self.assertIn("interruptedMessage = R.string.hardware_rename_uncertain", native)
        ui = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/HardwareOathDialog.kt").read_text()
        self.assertIn('confirmation == "RENAME"', ui)
        self.assertNotIn("rememberSaveable", ui)
        self.assertIn('if (accepting && !busy && name.isNotEmpty() && confirmation == "RENAME")', ui)
        self.assertIn('if (accepting && !busy && confirmation == "DELETE")', ui)

    def test_uncertain_hotp_and_key_writes_are_not_misrepresented(self):
        screen = source("Activities/HardwareKeyActivity.kt")
        self.assertIn("HardwareOathClient.Account(it.credential, null, 0)", screen)
        self.assertIn("interruptedMessage = if (hotp) R.string.hardware_hotp_uncertain", screen)
        self.assertIn("interruptedMessage = R.string.hardware_copy_uncertain", screen)
        self.assertIn("account.usableAt(clock())", screen)
        self.assertIn("Character.getType(it) == Character.FORMAT.toInt()", screen)
        adapter = core_source("Utilities/HardwareFidoClient.kt")
        self.assertIn("toMap(SerializationType.CBOR)", adapter)
        layout = ET.parse(APP / "res/layout/activity_hardware_unlock.xml").getroot()
        self.assertEqual("FrameLayout", layout.tag)

    def test_lifecycle_and_reset_revoke_the_shortcut(self):
        key_screen = source("Activities/HardwareKeyActivity.kt")
        unlock = source("Activities/HardwareUnlockActivity.kt")
        discovery = source("Utilities/HardwareKeyDiscovery.kt")
        self.assertIn("override fun onStop()", key_screen)
        self.assertIn("override fun onStop()", unlock)
        self.assertIn("state.cancel()", key_screen)
        self.assertIn("state.cancel()", unlock)
        self.assertIn("discard(value as T)", discovery)
        self.assertIn("op.releaseOnce()", discovery)
        settings = source("Utilities/Settings.kt")
        self.assertIn("HardwareUnlockStore.clearOnVaultChange(editor)", settings)
        database = source("Utilities/DatabaseHelper.kt")
        reset = database[database.index("fun wipeDatabase("):]
        self.assertLess(reset.index("HardwareUnlockStore.clearAfterReset"), reset.index("FileHelper.delete"))

    def test_documentation_names_limits_and_password_fallback(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        for text in ("alternative", "not a mandatory second factor", "Nitrokey Pro", "physical-key", "remain pending"):
            self.assertIn(text, readme)


if __name__ == "__main__":
    unittest.main()
