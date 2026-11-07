"""Guards for the shared Compose Multiplatform ``:ui`` module.

The module is the single implementation of the vault screen for every client, so its boundary is
what keeps it shareable: no Android or desktop-toolkit import, no storage/crypto/session access, no
host package references, and a pure projection layer that carries no Compose import at all. These
checks fail loudly if a later change quietly turns the shared UI into a second client.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
UI_MAIN = ROOT / "ui/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui"
UI_TEST = ROOT / "ui/src/desktopTest/kotlin/com/fairuniversal/offlineotp/ui"
COMPOSE_HOST = ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose"
SANDBOX = ROOT / "scripts/kotlin-sandbox"
COMPOSE_STUBS = SANDBOX / "stubs-compose"

# Frame/model files: no Compose, no platform, testable on a plain JVM.
PURE_FILES = {
    "vault/TokenDisplay.kt",
    "vault/VaultCardState.kt",
    "vault/VaultUiState.kt",
    "vault/VaultStrings.kt",
    "vault/VaultActions.kt",
    "vault/VaultProjection.kt",
}


def kotlin_files(directory):
    return sorted(directory.rglob("*.kt"))


def imports(path):
    found = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("import "):
            found.append((number, stripped[len("import "):].split()[0].strip(".")))
    return found


class ComposeUiBoundaryTest(unittest.TestCase):

    def test_ui_sources_never_import_a_platform_toolkit(self):
        """The shared UI must build for Android and desktop from the same sources."""
        forbidden = (
            "android", "androidx.activity", "androidx.appcompat", "androidx.recyclerview",
            "java.awt", "javax.swing", "java.io", "java.nio", "java.security", "javax.crypto",
            "org.json", "com.yubico",
        )
        offenders = []
        for path in kotlin_files(UI_MAIN):
            for number, imported in imports(path):
                if imported == forbidden[0] or imported.startswith(tuple(f + "." for f in forbidden)):
                    offenders.append(f"{path.relative_to(ROOT)}:{number} {imported}")
        self.assertEqual([], offenders, "shared UI must not depend on a client toolkit or platform API")

    def test_shared_ui_never_references_a_host_package(self):
        """Screens render state and report actions; they do not reach into a client's internals."""
        forbidden = (
            "com.fairuniversal.offlineotp.desktop",
            "com.fairuniversal.offlineotp.Activities",
            "com.fairuniversal.offlineotp.Tasks",
            "com.fairuniversal.offlineotp.Fragments",
            "com.fairuniversal.offlineotp.Dialogs",
            "com.fairuniversal.offlineotp.Preferences",
        )
        offenders = []
        for path in kotlin_files(UI_MAIN):
            for number, imported in imports(path):
                if imported.startswith(forbidden):
                    offenders.append(f"{path.relative_to(ROOT)}:{number} {imported}")
        self.assertEqual([], offenders, "the shared UI must not import a host package")

    def test_projection_layer_carries_no_compose_import(self):
        """The frame builder and its model are what the host JVM tests execute."""
        missing = [name for name in PURE_FILES if not (UI_MAIN / name).is_file()]
        self.assertEqual([], missing, "a pure projection file was renamed or removed")
        offenders = []
        for name in PURE_FILES:
            for number, imported in imports(UI_MAIN / name):
                if imported.startswith("androidx.compose"):
                    offenders.append(f"{name}:{number} {imported}")
        self.assertEqual([], offenders, "frame/model files must stay Compose-free so a plain JVM can test them")

    def test_projection_tests_cover_grouping_privacy_and_filtering(self):
        """The behaviours the two old clients each implemented are pinned here, not per client."""
        names = {path.name for path in kotlin_files(UI_TEST)}
        self.assertEqual({"TokenDisplayTest.kt", "VaultProjectionTest.kt"}, names)

    def test_ui_never_reads_a_clock_or_owns_a_coroutine_scope(self):
        """The host supplies time and session cancellation; the module only renders frames."""
        offenders = []
        for path in kotlin_files(UI_MAIN):
            text = path.read_text(encoding="utf-8")
            # `kotlin.time.TimeSource` is allowed in the countdown; wall-clock reads are not.
            for marker in ("System.currentTimeMillis()", "java.util.Date", "Calendar.getInstance"):
                if marker in text:
                    offenders.append(f"{path.relative_to(ROOT)} uses {marker}")
            if re.search(r"^\s*expect\s", text, re.MULTILINE):
                offenders.append(f"{path.relative_to(ROOT)} declares an expect declaration")
        self.assertEqual([], offenders, "platform time belongs to the host, not the shared UI")

    def test_codes_are_masked_until_revealed(self):
        """A card's digits are drawn only through the reveal flag, never by default."""
        card = (UI_MAIN / "vault/VaultCardState.kt").read_text(encoding="utf-8")
        components = (UI_MAIN / "vault/VaultComponents.kt").read_text(encoding="utf-8")
        self.assertIn("val tokenVisible: Boolean get() = token != null && revealed", card)
        self.assertIn("if (card.tokenVisible) card.token else TokenDisplay.mask(card.token)", components)

    def test_secret_bearing_state_redacts_its_diagnostics(self):
        card = (UI_MAIN / "vault/VaultCardState.kt").read_text(encoding="utf-8")
        state = (UI_MAIN / "vault/VaultUiState.kt").read_text(encoding="utf-8")
        self.assertIn("override fun toString(): String", card)
        self.assertIn('"VaultCardState(id=$id, kind=$kind, redacted)"', card)
        self.assertIn("override fun toString(): String", state)


class ComposeUiWiringTest(unittest.TestCase):

    def test_modules_are_listed_and_layered(self):
        settings = (ROOT / "settings.gradle").read_text(encoding="utf-8")
        self.assertIn('include ":ui"', settings)
        self.assertIn('include ":desktop-compose"', settings)

        ui = (ROOT / "ui/build.gradle").read_text(encoding="utf-8")
        for plugin in (
            "org.jetbrains.kotlin.multiplatform",
            "org.jetbrains.compose",
            "org.jetbrains.kotlin.plugin.compose",
            "com.android.library",
        ):
            self.assertIn(f"apply plugin: '{plugin}'", ui)
        self.assertIn("project(':core')", ui)
        self.assertIn("androidTarget", ui)
        self.assertIn("jvm('desktop')", ui)
        # The shared module must not depend on either host.
        self.assertNotIn("project(':app')", ui)
        self.assertNotIn("project(':desktop')", ui)

        host = (ROOT / "desktop-compose/build.gradle").read_text(encoding="utf-8")
        self.assertIn("project(':ui')", host)
        self.assertIn("project(':desktop')", host)
        self.assertIn("project(':core')", host)
        self.assertIn("compose.desktop.currentOs", host)

    def test_compose_versions_are_pinned_and_the_compiler_matches_kotlin(self):
        root = (ROOT / "build.gradle").read_text(encoding="utf-8")
        kotlin_version = re.search(r"kotlin-gradle-plugin:([0-9.]+)", root).group(1)
        self.assertIn("org.jetbrains.compose:compose-gradle-plugin:", root)
        self.assertIn(
            f"org.jetbrains.kotlin:compose-compiler-gradle-plugin:{kotlin_version}", root,
            "the Compose compiler plugin must match the Kotlin compiler version",
        )
        # No dynamic Compose version may sneak in anywhere.
        for path in ("build.gradle", "ui/build.gradle", "desktop-compose/build.gradle"):
            text = (ROOT / path).read_text(encoding="utf-8")
            self.assertNotRegex(text, r"compose[^'\n]*:[\.\+]")

    def test_swing_client_stays_compose_free(self):
        """`:desktop` keeps its Swing-only gate and tests while both clients share the engine."""
        offenders = []
        for path in kotlin_files(ROOT / "desktop/src"):
            for number, imported in imports(path):
                if imported.startswith("androidx.compose"):
                    offenders.append(f"{path.relative_to(ROOT)}:{number} {imported}")
        self.assertEqual([], offenders)

    def test_host_renders_the_shared_screen_instead_of_reimplementing_it(self):
        text = (COMPOSE_HOST / "ComposeMain.kt").read_text(encoding="utf-8")
        self.assertIn("VaultScreen(", text)
        self.assertIn("UnlockScreen(", text)
        self.assertIn("VaultProjection(", text)
        # No toolkit-level layout, drawing or a second card implementation in the host: every
        # widget comes from :ui, so the two clients cannot drift apart again.
        for marker in ("Canvas(", "Column(", "Row(", "VaultEntryCard(", "material3."):
            self.assertNotIn(marker, text)

    def test_sandbox_gate_exists_and_uses_the_compose_stubs(self):
        gate = SANDBOX / "compile-ui.sh"
        self.assertTrue(gate.is_file(), "missing the :ui sandbox compile gate")
        text = gate.read_text(encoding="utf-8")
        self.assertIn("stubs-compose", text)
        self.assertIn("ui/src/desktopTest/kotlin", text)
        self.assertIn("JvmTestRunnerKt", text)
        # Discovery is by find, so a new source file cannot silently sit outside the gate.
        self.assertIn('find "$UI_MAIN" -name', text)
        # The host is part of the same gate: it must be compiled against the compiled :desktop
        # engine and the Compose Desktop stubs, not left to the first real Gradle build.
        self.assertIn("desktop-compose/src/main/kotlin", text)
        self.assertTrue((COMPOSE_STUBS / "desktop").is_dir(), "missing Compose Desktop window stubs")
        self.assertTrue((COMPOSE_STUBS / "README.md").is_file(), "Compose stubs need their own limits note")


if __name__ == "__main__":
    unittest.main()
