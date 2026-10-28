"""Guards for the desktop Swing client module ``:desktop``.

The desktop client is a pure-JVM leaf application that reuses the Android-free ``:core``. It must
never import ``android.*``/``androidx.*`` and it adds no third-party UI or domain library of its
own: Swing/AWT are JDK ``java.desktop`` modules and every domain type is reached through ``:core``.
These checks mirror the stub-free ``compile-desktop.sh`` gate so a regression is caught even
without running the Kotlin compiler.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DESKTOP_MAIN = ROOT / "desktop/src/main/kotlin"
SANDBOX = ROOT / "scripts/kotlin-sandbox"


class DesktopModuleBoundaryTest(unittest.TestCase):

    def test_desktop_sources_never_import_android(self):
        """The desktop JVM has no Android SDK; an android import cannot resolve there."""
        offenders = []
        for path in DESKTOP_MAIN.rglob("*.kt"):
            for idx, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                stripped = line.strip()
                if stripped.startswith("import "):
                    imported = stripped[len("import "):].split()[0].strip(".")
                    if imported == "android" or imported.startswith(("android.", "androidx.")):
                        offenders.append(f"{path.relative_to(ROOT)}:{idx} {imported}")
        self.assertEqual([], offenders, "desktop sources must not import android.* or androidx.*")

    def test_desktop_uses_only_jdk_and_the_project_core(self):
        """No direct third-party dependency: domain logic comes from :core, the UI from java.desktop."""
        offenders = []
        for path in DESKTOP_MAIN.rglob("*.kt"):
            for idx, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                stripped = line.strip()
                if not stripped.startswith("import "):
                    continue
                imported = stripped[len("import "):].split()[0].strip(".")
                if imported.startswith(("kotlin", "java", "javax")):
                    continue
                if imported.startswith("org.shadowice.flocke.andotp"):
                    continue
                offenders.append(f"{path.relative_to(ROOT)}:{idx} {imported}")
        self.assertEqual([], offenders, "desktop may only use the JDK and the project's own packages")

    def test_gradle_wiring_present_and_depends_on_core(self):
        settings = (ROOT / "settings.gradle").read_text(encoding="utf-8")
        build = (ROOT / "desktop/build.gradle").read_text(encoding="utf-8")
        self.assertIn('include ":desktop"', settings)
        self.assertIn("org.jetbrains.kotlin.jvm", build)
        self.assertIn("apply plugin: 'application'", build)
        self.assertIn("project(':core')", build)
        # No dynamic versions: the stdlib is pinned with strictly, matching app/core.
        self.assertIn("strictly '2.4.21'", build)

    def test_swing_shell_is_separated_from_headless_model(self):
        """Heavyweight frame construction lives outside the headless-tested table model package."""
        model = DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/model/EntryTableModel.kt"
        window = DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/ui/MainWindow.kt"
        self.assertTrue(model.is_file())
        self.assertTrue(window.is_file())
        # The headless model must not instantiate a heavyweight peer.
        model_text = model.read_text(encoding="utf-8")
        self.assertNotIn("JFrame", model_text)
        self.assertIn("AbstractTableModel", model_text)
        # A real entry point exists for the application plugin's mainClass.
        main = DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/Main.kt"
        self.assertIn("fun main()", main.read_text(encoding="utf-8"))

    def test_compile_gate_exists_and_runs_headless(self):
        gate = SANDBOX / "compile-desktop.sh"
        self.assertTrue(gate.is_file())
        text = gate.read_text(encoding="utf-8")
        self.assertIn("java.awt.headless=true", text)
        # The gate compiles core before desktop and never puts an android stub on the classpath.
        self.assertNotIn("stubs/android", text)


if __name__ == "__main__":
    unittest.main()
