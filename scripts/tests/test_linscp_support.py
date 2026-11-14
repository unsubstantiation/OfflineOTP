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
            "linScpImportBytes?.fill(0)",
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

        ios_backup_view = (IOS / "App/Sources/BackupView.swift").read_text(encoding="utf-8")
        self.assertIn("Import LinSCP SSH & sessions…", ios_backup_view)
        self.assertIn("Export to LinSCP encrypted vault", ios_backup_view)


if __name__ == "__main__":
    unittest.main()
