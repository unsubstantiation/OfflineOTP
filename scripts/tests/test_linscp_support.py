"""Static guards for bidirectional LinSCP SSH & session support across Android, desktop, and iOS."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "core/src/main/kotlin/com/fairuniversal/offlineotp"
APP = ROOT / "app/src/main"
DESKTOP = ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop"
IOS = ROOT / "ios"


class LinScpSupportTest(unittest.TestCase):
    def test_core_linscp_codec_supports_lscpvlt2_backup_bundle_and_sessions_ini(self):
        constants = (CORE / "Utilities/Constants.kt").read_text(encoding="utf-8")
        self.assertIn("INTENT_MAIN_LINSCP_IMPORT = 111", constants)
        self.assertIn("INTENT_MAIN_LINSCP_EXPORT = 112", constants)

        codec = (CORE / "Utilities/LinScpCredentials.kt").read_text(encoding="utf-8")
        for token in (
            'FILE_MAGIC_TEXT = "LSCPVLT2"',
            "ARGON2_MEMORY_KIB = 64 * 1024",
            "ARGON2_ITERATIONS = 3",
            "ARGON2_PARALLELISM = 1",
            'SESSION_JSON_COLUMN = "linscp_session_json"',
            'SSH_KEY_LABEL_COLUMN = "linscp_ssh_key_label"',
            'TUNNEL_KEY_LABEL_COLUMN = "linscp_tunnel_key_label"',
            'LSCP1_PREFIX = "lscp1$"',
            "encryptWithAad",
            "decryptWithAad",
            "fun openVault(",
            "fun exportVault(",
            "fun decryptLscp1Password(",
            "fun formatPasswordDetails(",
        ):
            self.assertIn(token, codec)

        backup_codec = (CORE / "Utilities/EncryptedBackupCodec.kt").read_text(encoding="utf-8")
        self.assertIn("LinScpCredentials.isEncryptedVault(data)", backup_codec)

    def test_android_linscp_import_and_export_are_session_guarded_and_wipe_secrets(self):
        main = (APP / "kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text(encoding="utf-8")
        for token in (
            "chooseLinScpImport()",
            "chooseLinScpExport()",
            "Constants.INTENT_MAIN_LINSCP_IMPORT",
            "Constants.INTENT_MAIN_LINSCP_EXPORT",
            "LinScpCredentials.importBytes(",
            "LinScpCredentials.exportVault(",
            "dispose = { bytes.fill(0) }",
            "linScpExportPending?.fill(0)",
            "isSaveEnabled = false",
        ):
            self.assertIn(token, main)

    def test_desktop_and_ios_expose_bidirectional_linscp_interchange(self):


        ios_vault = (IOS / "Sources/OfflineOTPKit/LinScpVault.swift").read_text(encoding="utf-8")
        self.assertIn('magicText = "LSCPVLT2"', ios_vault)
        self.assertIn("argon2Parallelism = 1", ios_vault)
        self.assertIn("authenticating: Data(magic)", ios_vault)
        self.assertIn("decryptLscp1Password", ios_vault)

        ios_backup_view = (IOS / "App/Sources/ComposeVaultHost.swift").read_text(encoding="utf-8")
        self.assertIn('title: "Import LinSCP Key/Sessions"', ios_backup_view)
        self.assertIn('title: "Export LinSCP Key/Sessions"', ios_backup_view)

    def test_plain_export_is_explicit_and_both_imports_show_a_selection(self):
        form = (ROOT / "ui-foundation/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui/vault/LinScpExportDialog.kt").read_text()
        self.assertIn("mutableStateOf(false)", form)
        self.assertIn("Exported secrets will be accessible by software on your device", form)
        self.assertIn("selected.isNotEmpty()", form)
        self.assertNotIn("rememberSaveable", form)
        ios = (IOS / "App/Sources/ComposeVaultHost.swift").read_text()
        self.assertIn('draft["linScpPlain"] == "true"', ios)
        self.assertIn("store.generation == epoch", ios)
        self.assertIn("$0.fields == candidate.fields", ios)
        self.assertIn("store.importLinScpSelection(chosen)", ios)
        self.assertIn("Warning: Exported secrets will be accessible by software on your device.", ios)
        main = (APP / "kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        self.assertIn("composeVaultView?.clearLinScpImport()", main)
        self.assertIn("view.showLinScpImport(request, labels, dispose)", main)
        self.assertIn("view.ownsLinScpImport(request)", main)
        self.assertIn("incoming.forEach { it.destroy() }; incoming.clear()", main)
        self.assertIn("view.showLinScpExport(request, labels)", main)
        self.assertIn("view.ownsLinScpExport(request)", main)
        self.assertNotIn("promptLinScpExportPassword", main)
        self.assertIn("LinScpCredentials.exportPlain(chosen)", main)
        codec = (CORE / "Utilities/LinScpCredentials.kt").read_text()
        self.assertIn("fun exportPlain", codec)
        self.assertIn("importPlainDocument(trimmed)", codec)
        self.assertIn('if (plainText) ".json" else ".json.aes"', codec)


if __name__ == "__main__":
    unittest.main()
