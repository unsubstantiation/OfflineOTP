"""The desktop builds are portable: their data lives beside the program, not in the user's profile.

Both the Debian and the Windows build use the same Swing client, so one rule has to hold for both:
the vault, its `.bck` copy, the settings and the security-key unlock record go into a `data` folder
inside the program folder. These guards pin that rule, the resolution order that finds the program
folder, and the documented escape hatch for a read-only installation.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DESKTOP = ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop"
STORAGE = DESKTOP / "storage/DesktopStorage.kt"
MAIN = DESKTOP / "Main.kt"


def read(path):
    return Path(path).read_text(encoding="utf-8")


class DesktopPortableVaultTest(unittest.TestCase):
    def setUp(self):
        self.storage = read(STORAGE)
        self.main = read(MAIN)

    def test_nothing_defaults_to_the_users_profile(self):
        for path in (ROOT / "desktop/src/main/kotlin").rglob("*.kt"):
            source = read(path)
            with self.subTest(file=str(path.relative_to(ROOT))):
                self.assertNotIn("user.home", source)
        # The old per-user dot-folder is gone from the live code, not just renamed.
        self.assertNotIn(".offlineotp", self.storage)

    def test_the_data_folder_sits_inside_the_program_folder(self):
        self.assertIn('const val DATA_DIRECTORY = "data"', self.storage)
        self.assertIn("fun defaultDirectory(): File = File(programDirectory(), DATA_DIRECTORY)",
                      self.storage)
        self.assertIn("fun defaultDirectory()", self.storage)
        self.assertIn("val directory = DesktopStorage.defaultDirectory()", self.main)

    def test_the_program_folder_is_resolved_without_the_environment(self):
        # An explicit override first, then jpackage's launcher, then the running jar, then the cwd.
        for marker in ('const val HOME_PROPERTY = "offlineotp.home"',
                       'const val HOME_ENVIRONMENT = "OFFLINEOTP_HOME"',
                       'const val JPACKAGE_LAUNCHER_PROPERTY = "jpackage.app-path"',
                       "System.getenv(HOME_ENVIRONMENT)",
                       "System.getProperty(JPACKAGE_LAUNCHER_PROPERTY)",
                       'System.getProperty("user.dir")',
                       'endsWith(".jar", ignoreCase = true)',
                       'jarDirectory.name == "lib"'):
            self.assertIn(marker, self.storage)
        # jpackage puts the launcher in <app>/bin/, so the program folder is one level up.
        self.assertIn('if (parent.name == "bin" && parent.parentFile != null) return parent.parentFile',
                      self.storage)

    def test_an_unwritable_folder_is_reported_with_the_override(self):
        self.assertIn("The vault directory is not writable", self.storage)
        self.assertIn("HOME_PROPERTY", self.storage)
        # The startup path shows the message instead of a stack trace and exits non-zero.
        self.assertIn("could not open its vault directory", self.main)
        self.assertIn("System.exit(1)", self.main)

    def test_the_layout_is_documented(self):
        readme = read(ROOT / "README.md")
        self.assertIn("inside the program folder", readme)
        self.assertIn("-Dofflineotp.home", readme)
        self.assertNotIn("directory is `~/.offlineotp`", readme)
        packaging = read(ROOT / "scripts/packaging/README.md")
        self.assertIn("## Portable data folder", packaging)
        self.assertIn("data/secrets.dat", packaging)
        changelog = read(ROOT / "CHANGELOG.md")
        self.assertIn("fully portable", changelog)
        # A local `gradle run` must not leave a vault in the working tree.
        self.assertIn("desktop/data/", read(ROOT / ".gitignore"))

    def test_the_sandbox_compiles_the_resolver_and_its_tests(self):
        script = read(ROOT / "scripts/kotlin-sandbox/compile-desktop.sh")
        # compile-desktop.sh globs the whole module, main and test, so both land in the gate.
        self.assertIn('DESKTOP_TEST="$ROOT/desktop/src/test/kotlin"', script)
        self.assertIn('find "$DESKTOP_MAIN" "$DESKTOP_TEST"', script)
        self.assertTrue((DESKTOP / "storage/DesktopStorage.kt").is_file())
        self.assertTrue((ROOT / "desktop/src/test/kotlin/org/shadowice/flocke/andotp/desktop"
                         "/storage/DesktopPortableLocationsTest.kt").is_file())


if __name__ == "__main__":
    unittest.main()
