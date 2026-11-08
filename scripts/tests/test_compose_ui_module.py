"""Guards for the shared Compose Multiplatform UI: the ``:ui-model`` frame layer and ``:ui`` widgets.

The frame layer is the contract between the vault and every renderer, so its boundary is what keeps
the UI shareable. Two kinds of check live here:

* source checks, because a template string, an import or a Gradle line can reintroduce a dependency
  that the compile gates only catch later; and
* wording parity against the Android resources, because the Compose screen, the Swing window and the
  phone must label the same tab the same way, and the Android values are the localised source of
  truth.
"""
import re
import unittest
from pathlib import Path
from xml.etree import ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MODEL_MAIN = ROOT / "ui-model/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui"
MODEL_TEST = ROOT / "ui-model/src/desktopTest/kotlin/com/fairuniversal/offlineotp/ui"
UI_MAIN = ROOT / "ui/src/commonMain/kotlin/com/fairuniversal/offlineotp/ui"
UI_ANDROID = ROOT / "ui/src/androidMain/kotlin/com/fairuniversal/offlineotp/ui"
COMPOSE_HOST = ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose"
SANDBOX = ROOT / "scripts/kotlin-sandbox"
COMPOSE_STUBS = SANDBOX / "stubs-compose"
ANDROID_STRINGS = ROOT / "app/src/main/res/values/strings_main.xml"

# Frame/model files: no Compose, no platform, testable on a plain JVM.
FRAME_FILES = {
    "vault/TokenDisplay.kt",
    "vault/VaultCardState.kt",
    "vault/VaultUiState.kt",
    "vault/VaultStrings.kt",
    "vault/VaultActions.kt",
    "vault/VaultProjection.kt",
    "vault/VaultCards.kt",
}

HOST_PACKAGES = (
    "com.fairuniversal.offlineotp.desktop",
    "com.fairuniversal.offlineotp.Activities",
    "com.fairuniversal.offlineotp.Tasks",
    "com.fairuniversal.offlineotp.Fragments",
    "com.fairuniversal.offlineotp.Dialogs",
    "com.fairuniversal.offlineotp.Preferences",
)


def kotlin_files(directory):
    return sorted(directory.rglob("*.kt"))


def imports(path):
    found = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if stripped.startswith("import "):
            found.append((number, stripped[len("import "):].split()[0].strip(".")))
    return found


def android_strings():
    root = ET.parse(ANDROID_STRINGS).getroot()
    return {element.get("name"): "".join(element.itertext()) for element in root.findall("string")}


def kotlin_label_map(constant_function):
    """The `case -> "label"` pairs inside one `when` in VaultStrings.kt."""
    source = (MODEL_MAIN / "vault/VaultStrings.kt").read_text(encoding="utf-8")
    start = source.index(f"fun {constant_function}(")
    body = source[start:]
    body = body[:body.index("\n    }")]
    return {key: label for key, label in re.findall(r"(\w+)\s*->\s*\"([^\"]*)\"", body)}


class FrameLayerBoundaryTest(unittest.TestCase):

    def test_frame_layer_never_imports_a_toolkit_or_platform_api(self):
        """`:ui-model` is the only UI code a non-Compose client may depend on."""
        forbidden = (
            "android", "androidx", "java.awt", "javax.swing", "java.io", "java.nio",
            "java.security", "javax.crypto", "org.json", "com.yubico",
        )
        offenders = []
        for path in kotlin_files(MODEL_MAIN):
            for number, imported in imports(path):
                if imported.split(".")[0] in forbidden or imported.startswith(tuple(f + "." for f in forbidden)):
                    offenders.append(f"{path.relative_to(ROOT)}:{number} {imported}")
        self.assertEqual([], offenders, "the frame layer must stay toolkit-free")

    def test_frame_layer_module_declares_no_compose(self):
        build = (ROOT / "ui-model/build.gradle").read_text(encoding="utf-8")
        self.assertNotIn("org.jetbrains.compose", build)
        self.assertNotIn("org.jetbrains.kotlin.plugin.compose", build)
        self.assertNotIn("compose.", build)
        self.assertIn("project(':core')", build)
        self.assertIn("org.jetbrains.kotlin.multiplatform", build)

    def test_frame_layer_is_the_only_ui_dependency_of_a_non_compose_client(self):
        desktop = (ROOT / "desktop/build.gradle").read_text(encoding="utf-8")
        self.assertIn("project(':ui-model')", desktop)
        self.assertNotIn("project(':ui')", desktop)
        ui = (ROOT / "ui/build.gradle").read_text(encoding="utf-8")
        self.assertIn("api project(':ui-model')", ui)

    def test_pin_dialog_masks_and_does_not_save_input(self):
        dialog = (UI_MAIN / "vault/EntryPinDialog.kt").read_text()
        self.assertIn("remember(entryId)", dialog)
        self.assertNotIn("rememberSaveable", dialog)
        self.assertIn("PasswordVisualTransformation()", dialog)
        self.assertIn("InputPolicy.MAX_TAG_CHARS", dialog)
        self.assertIn('pin = ""\n                onSubmit(submitted)', dialog)
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("vault.submitPin(pinId, pin)", host)
        self.assertIn("pendingPinId = null", host)
        widgets = (UI_MAIN / "vault/VaultComponents.kt").read_text()
        self.assertIn("card.pinRequired && enterPin != null", widgets)

    def test_counter_confirmation_is_shared_and_host_keeps_only_an_id(self):
        dialog = (UI_MAIN / "vault/EntryConfirmationDialog.kt").read_text()
        self.assertIn("AlertDialog(", dialog)
        self.assertIn("onDismissRequest = onDismiss", dialog)
        self.assertNotIn("rememberSaveable", dialog)
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("EntryConfirmationDialog(", host)
        self.assertIn("mutableStateOf<Long?>(null)", host)
        self.assertIn("vault.advanceCounter(pendingId)", host)
        self.assertIn("pendingCounterId = null", host)

    def test_compose_host_delegates_interactions_to_presenter(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        self.assertIn("VaultPresenter(", host)
        self.assertIn("actions = presenter.actions()", host)
        self.assertNotIn("filter.withQuery", host)
        self.assertNotIn("mutableStateOf(emptySet<Long>())", host)

    def test_projection_tests_live_with_the_frame_layer(self):
        names = {path.name for path in kotlin_files(MODEL_TEST)}
        self.assertEqual(
            {"TokenDisplayTest.kt", "VaultProjectionTest.kt", "VaultFilterTest.kt",
             "VaultCardsTest.kt", "VaultPresenterTest.kt"}, names
        )
        # A host that paints one row at a time (the Swing table) asks for a single card; the same
        # per-entry rules must therefore be reachable without building a frame.
        projection = (MODEL_MAIN / "vault/VaultProjection.kt").read_text(encoding="utf-8")
        self.assertIn("VaultCards.of(entry", projection)
        cards = (MODEL_MAIN / "vault/VaultCards.kt").read_text(encoding="utf-8")
        # One row needs nothing but the entry, the clock and the reveal flag: no list, no filter, no
        # frame, and no clock of its own.
        self.assertIn("nowMillis: Long", cards)
        self.assertNotIn("VaultFilter", cards)
        self.assertNotIn("System.currentTimeMillis", cards)
        filter_test = (MODEL_TEST / "vault/VaultFilterTest.kt").read_text(encoding="utf-8")
        # The tag-tap rules that used to live twice: OR/AND accumulate, SINGLE replaces, a second
        # tap on the only SINGLE chip clears it, and the untagged switch is separate.
        for expected in ("withTagToggled", "withUntaggedToggled", "clearedFilters",
                         "singleReplacesTheSelection", "singleClearsWhenTheOnlyChipIsTappedAgain"):
            self.assertIn(expected, filter_test)

    def test_frame_files_are_all_present_and_compose_free(self):
        missing = [name for name in FRAME_FILES if not (MODEL_MAIN / name).is_file()]
        self.assertEqual([], missing, "a frame-layer file was renamed or removed")
        offenders = []
        for name in FRAME_FILES:
            for number, imported in imports(MODEL_MAIN / name):
                if imported.startswith("androidx.compose"):
                    offenders.append(f"{name}:{number} {imported}")
        self.assertEqual([], offenders)

    def test_codes_are_masked_until_revealed(self):
        """A card's digits are drawn only through the reveal flag, never by default."""
        card = (MODEL_MAIN / "vault/VaultCardState.kt").read_text(encoding="utf-8")
        components = (UI_MAIN / "vault/VaultComponents.kt").read_text(encoding="utf-8")
        self.assertIn("val tokenVisible: Boolean get() = token != null && revealed", card)
        self.assertIn("if (card.tokenVisible) token else TokenDisplay.mask(token)", components)

    def test_secret_bearing_state_redacts_its_diagnostics(self):
        card = (MODEL_MAIN / "vault/VaultCardState.kt").read_text(encoding="utf-8")
        state = (MODEL_MAIN / "vault/VaultUiState.kt").read_text(encoding="utf-8")
        self.assertIn('"VaultCardState(id=$id, kind=$kind, redacted)"', card)
        self.assertIn("override fun toString(): String", state)

    def test_neither_module_reads_a_wall_clock(self):
        """The host supplies the clock anchor; the shared code only counts elapsed time."""
        offenders = []
        for directory in (MODEL_MAIN, UI_MAIN, UI_ANDROID):
            for path in kotlin_files(directory):
                text = path.read_text(encoding="utf-8")
                for marker in ("System.currentTimeMillis()", "System.nanoTime()",
                               "java.util.Date", "Calendar.getInstance"):
                    if marker in text:
                        offenders.append(f"{path.relative_to(ROOT)} uses {marker}")
                if re.search(r"^\s*expect\s", text, re.MULTILINE):
                    offenders.append(f"{path.relative_to(ROOT)} declares an expect declaration")
        self.assertEqual([], offenders)


class VaultWordingParityTest(unittest.TestCase):
    """The shared defaults are the Android resources' own words, so no client can relabel a tab."""

    def test_tab_labels_match_the_android_resources(self):
        strings = android_strings()
        labels = kotlin_label_map("categoryTitle")
        expected = {
            "OTP": strings["tab_otp"],
            "NOTES": strings["tab_notes"],
            "SSH_KEYS": strings["tab_ssh_keys"],
            "PASSWORDS": strings["tab_passwords"],
            "EMAILS": strings["tab_email"],
        }
        self.assertEqual(expected, labels)

    def test_sort_labels_match_the_android_resources(self):
        strings = android_strings()
        labels = kotlin_label_map("sortTitle")
        expected = {
            "UNSORTED": strings["menu_sort_none"],
            "ISSUER": strings["menu_sort_issuer"],
            "LABEL": strings["menu_sort_label"],
            "LAST_USED": strings["menu_sort_last_used"],
            "MOST_USED": strings["menu_sort_most_used"],
        }
        self.assertEqual(expected, labels)

    def test_desktop_client_takes_its_labels_from_the_shared_table(self):
        screen = (ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/ui/MainScreen.kt")
        text = screen.read_text(encoding="utf-8")
        self.assertIn("VaultStrings.English.categoryTitle(category)", text)
        self.assertIn("VaultStrings.English.sortTitle(mode)", text)
        # No second label table may reappear in the Swing client.
        self.assertNotIn('"SSH Keys"', text)
        self.assertNotIn('"Most used"', text)


class ComposeUiWiringTest(unittest.TestCase):

    def test_modules_are_listed_and_layered(self):
        settings = (ROOT / "settings.gradle").read_text(encoding="utf-8")
        for module in ('":ui-model"', '":ui"', '":desktop-compose"'):
            self.assertIn(f"include {module}", settings)

        model = (ROOT / "ui-model/build.gradle").read_text(encoding="utf-8")
        self.assertIn("org.jetbrains.kotlin.multiplatform", model)
        self.assertIn("com.android.library", model)
        self.assertIn("project(':core')", model)
        self.assertNotIn("project(':ui')", model)

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
        # The widget module must not depend on either host.
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

    def test_shared_ui_never_references_a_host_package(self):
        offenders = []
        for directory in (MODEL_MAIN, UI_MAIN, UI_ANDROID):
            for path in kotlin_files(directory):
                for number, imported in imports(path):
                    if imported.startswith(HOST_PACKAGES):
                        offenders.append(f"{path.relative_to(ROOT)}:{number} {imported}")
        self.assertEqual([], offenders, "the shared UI must not import a host package")

    def test_ui_widgets_never_import_a_platform_toolkit(self):
        forbidden = ("android", "androidx.activity", "androidx.appcompat", "androidx.recyclerview",
                     "java.awt", "javax.swing", "java.io", "java.nio", "java.security",
                     "javax.crypto", "org.json", "com.yubico")
        offenders = []
        for path in kotlin_files(UI_MAIN):
            for number, imported in imports(path):
                if imported.split(".")[0] == "android" or imported.startswith(
                    tuple(f + "." for f in forbidden)
                ):
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

    def test_android_seam_is_a_thin_view_and_holds_no_session(self):
        """Stage 2 reaches the screen through one ComposeView subclass, not a second client."""
        seam = UI_ANDROID / "vault/VaultComposeView.kt"
        self.assertTrue(seam.is_file(), "missing the Android ComposeView seam")
        text = seam.read_text(encoding="utf-8")
        self.assertIn("class VaultComposeView(", text)
        self.assertIn("ComposeView(context)", text)
        self.assertIn("fun render(next: VaultUiState)", text)
        self.assertIn("fun clear()", text)
        self.assertIn("DisposeOnViewTreeLifecycleDestroyed", text)
        self.assertIn("VaultScreenHost(", text)
        for forbidden in ("SecretKey", "VaultSession", "DesktopVaultState", "PasswordVault",
                          "SharedPreferences", "Settings"):
            self.assertNotIn(forbidden, text)
        self.assertNotIn("VaultEntryCard(", text)
        self.assertNotIn("Column(", text)

    def test_sandbox_gate_compiles_the_frame_layer_without_compose(self):
        gate = SANDBOX / "compile-ui.sh"
        self.assertTrue(gate.is_file(), "missing the :ui sandbox compile gate")
        text = gate.read_text(encoding="utf-8")
        self.assertIn("stubs-compose", text)
        self.assertIn("MODEL_MAIN", text)
        self.assertIn("deliberately NO Compose stubs", text)
        self.assertIn("JvmTestRunnerKt", text)
        # Discovery is by find, so a new source file cannot silently sit outside the gate.
        self.assertIn('find "$MODEL_MAIN" -name', text)
        self.assertIn('find "$UI_MAIN" -name', text)
        # The host is part of the same gate: it is compiled against the compiled :desktop engine
        # and the Compose Desktop stubs, not left to the first real Gradle build.
        self.assertIn("desktop-compose/src/main/kotlin", text)
        self.assertTrue((COMPOSE_STUBS / "desktop").is_dir(), "missing Compose Desktop window stubs")
        # The Android seam is type-checked too, against the app's own Android stubs.
        self.assertIn("ui/src/androidMain/kotlin", text)
        self.assertIn("stubs/android", text)
        self.assertTrue((COMPOSE_STUBS / "android").is_dir(), "missing Compose Android platform stubs")
        self.assertTrue((COMPOSE_STUBS / "README.md").is_file(), "Compose stubs need their own limits note")


if __name__ == "__main__":
    unittest.main()
