"""SSH key entries also carry the connection details a user needs to log in.

Host name, port, user name and password are stored beside the key material, inside the encrypted
vault and its encrypted backups. The password is a secret: it is never logged, never on a concealed
card face, and only copied or revealed on an explicit request. These guards pin the three surfaces
(core persistence, the Android editor, the shared desktop UI) that must agree on that.
"""
import re
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "app/src/main"
DESKTOP = ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop"
CORE = ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp"
ANDROID = "{http://schemas.android.com/apk/res/android}"


def read(path):
    return Path(path).read_text(encoding="utf-8")


class CoreSshConnectionTest(unittest.TestCase):
    def test_the_value_type_is_compiled_by_the_sandbox(self):
        run = read(ROOT / "scripts/kotlin-sandbox/run.sh")
        self.assertIn("Utilities/SshConnection.kt", run)
        source = read(CORE / "Utilities/SshConnection.kt")
        # Bounds live in one place and are shared by every editor.
        for constant in ("MIN_PORT = 1", "MAX_PORT = 65535", "DEFAULT_PORT = 22"):
            self.assertIn(constant, source)
        # The password must never leak through a generated toString().
        self.assertIn('override fun toString(): String = "SshConnection.Details(redacted)"', source)
        self.assertNotIn("data class Details", source)

    def test_the_json_keys_are_ssh_only_and_written_last(self):
        json_source = read(CORE / "Database/EntryJson.kt")
        for key in ("ssh_host", "ssh_port", "ssh_username", "ssh_password"):
            self.assertIn(key, json_source)
        # Only SSH key documents carry them, on both the read and the write path.
        self.assertIn("val sshConnection = if (kind == OtpKind.SSH_KEY) sshConnection(json) else null",
                      json_source)
        self.assertIn("if (kind == OtpKind.SSH_KEY && sshConnection != null)", json_source)
        # A port outside the range is rejected rather than silently replaced by the default.
        self.assertIn("hasPort", json_source)
        # Nothing is written for an entry without connection details, so legacy bytes are stable.
        for guard in ("if (sshConnection.host.isNotEmpty())",
                      "if (sshConnection.port != 0)",
                      "if (sshConnection.username.isNotEmpty())",
                      "if (sshConnection.password.isNotEmpty())"):
            self.assertIn(guard, json_source)
        # Written after every pre-existing field, which keeps old documents byte-identical.
        self.assertGreater(json_source.index("JSON_SSH_HOST, sshConnection.host"),
                           json_source.index("JSON_DETAILS_VISIBLE, true"))

    def test_entries_keep_the_connection_beside_the_keys(self):
        entry = read(CORE / "Database/Entry.kt")
        self.assertIn("fun getSshConnection()", entry)
        self.assertIn("fun setSshConnection(", entry)
        self.assertIn("require(isSshKey())", entry)
        # Equality ignores the password but notices a different target.
        self.assertIn("sshIdentity()", entry)

    def test_the_password_is_never_logged(self):
        # A static message may name the concept; a log call may never interpolate the value.
        leak = re.compile(r"\$\{[^}]*(?:[Pp]assword|getSshConnection\()[^}]*\}|\.password\b")
        for path in list((ROOT / "core/src/main").rglob("*.kt")) + \
                list((ROOT / "app/src/main").rglob("*.kt")) + \
                list((ROOT / "desktop/src/main").rglob("*.kt")):
            for line in read(path).splitlines():
                if re.search(r"\b(?:Log|println|printStackTrace)", line) and leak.search(line):
                    with self.subTest(file=str(path.relative_to(ROOT)), line=line.strip()):
                        self.fail("a log call may interpolate the SSH password")


class AndroidSshConnectionEditorTest(unittest.TestCase):
    def setUp(self):
        self.layout = ET.parse(APP / "res/layout/dialog_manual_entry.xml").getroot()
        self.dialog = read(APP / "kotlin/org/shadowice/flocke/andotp/Dialogs/ManualEntryDialog.kt")
        self.strings = read(APP / "res/values/strings_main.xml")

    def test_the_editor_has_a_row_for_each_field(self):
        for name in ("host", "port", "username", "password"):
            row = self.layout.find(f".//*[@{ANDROID}id='@+id/manual_layout_ssh_{name}']")
            field = self.layout.find(f".//*[@{ANDROID}id='@+id/manual_ssh_{name}']")
            with self.subTest(field=name):
                self.assertIsNotNone(row, f"missing the {name} row")
                self.assertIsNotNone(field, f"missing the {name} input")
                self.assertEqual("gone", row.get(ANDROID + "visibility"))

    def test_the_rows_follow_the_ssh_type_and_are_wiped_on_dismiss(self):
        self.assertIn("sshConnectionRows.forEach { it.visibility = if (ssh) View.VISIBLE else View.GONE }",
                      self.dialog)
        for name in ("host", "port", "username", "password"):
            self.assertIn(f'ssh{name.capitalize()}Input.setText("")', self.dialog)
        self.assertIn("sshHostInput.addTextChangedListener(watcher)", self.dialog)
        self.assertIn("sshPasswordInput.isSaveEnabled = false", self.dialog)

    def test_a_bad_port_is_reported_with_our_own_wording(self):
        self.assertIn("R.string.error_invalid_ssh_port", self.dialog)
        self.assertNotIn("sshPortInput.setError(error.message)", self.dialog)
        self.assertIn('name="error_invalid_ssh_port"', self.strings)
        self.assertIn('name="hint_ssh_port"', self.strings)

    def test_the_copy_menu_offers_the_password_only_when_it_is_stored(self):
        adapter = read(APP / "kotlin/org/shadowice/flocke/andotp/View/EntriesCardAdapter.kt")
        self.assertIn("if (!connection?.password.isNullOrEmpty())", adapter)
        self.assertIn("SshField.PASSWORD -> actual.getSshConnection()?.password.orEmpty()", adapter)


class DesktopSshConnectionTest(unittest.TestCase):
    def setUp(self):
        self.dialog = read(DESKTOP / "ui/EntryDialog.kt")
        self.editor = read(DESKTOP / "control/EntryEditor.kt")
        self.window = read(DESKTOP / "ui/MainWindow.kt")
        self.cards = read(DESKTOP / "ui/EntryCards.kt")

    def test_the_edit_dialog_collects_all_four_fields(self):
        self.assertIn("JPasswordField(", self.dialog)
        for field in ("sshHost", "sshPort", "sshUsername", "sshPassword"):
            self.assertIn(f"val {field} =", self.dialog)
        self.assertIn("SshConnection.of(sshHost.text, SshConnection.port(sshPort.text)", self.dialog)
        self.assertIn("Entry.sshKey(publicKeyText, secret", self.dialog)
        # The rows are hidden for every other type, and the password is wiped on close.
        self.assertIn(".forEach { it.isVisible = ssh }", self.dialog)
        self.assertIn('sshPassword.text = ""', self.dialog)

    def test_the_password_is_copied_only_after_a_reveal(self):
        self.assertIn("fun copySshPassword(entry: Entry): TokenCopyResult {", self.editor)
        self.assertIn("if (!entry.areDetailsVisible() || !entry.isSshKey()) return TokenCopyResult.Unavailable",
                      self.editor)
        self.assertIn('if (!entry.getSshConnection()?.password.isNullOrEmpty()) choices.add("Password")',
                      self.window)

    def test_the_card_shows_the_target_but_never_the_password(self):
        self.assertIn("entry.getSshConnection()?.summary()?.let { parts.add(it) }", self.cards)
        # summary() is host/user/port only; the password is never formatted into it.
        connection = read(CORE / "Utilities/SshConnection.kt")
        summary = connection[connection.index("fun summary()"):]
        summary = summary[:summary.index("}")]
        self.assertNotIn("password", summary)


if __name__ == "__main__":
    unittest.main()
