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
    "vault/VaultPresenter.kt",
    "vault/VaultDialogs.kt",
    "vault/VaultFrameHost.kt",
    "vault/VaultClock.kt",
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

    def test_delete_action_requires_host_support_and_confirmation(self):
        widgets = (UI_MAIN / "vault/VaultComponents.kt").read_text()
        self.assertIn("val deleteEntry = actions.onDelete", widgets)
        self.assertIn("if (card.detailsVisible && deleteEntry != null)", widgets)
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        wording = (MODEL_MAIN / "vault/VaultDialogs.kt").read_text()
        self.assertIn('deleteTitle: String = "Delete account?"', wording)
        self.assertIn("vault.deleteEntry(request.entryId)", host)
        self.assertIn("controller.save(vault.key, remaining)", host)
        self.assertIn("presenter.entryRemoved(request.entryId)", host)
        self.assertIn("dialogs.close()", host)

    def test_pin_dialog_masks_and_does_not_save_input(self):
        dialog = (UI_MAIN / "vault/EntryPinDialog.kt").read_text()
        self.assertIn("remember(inputKey)", dialog)
        self.assertNotIn("rememberSaveable", dialog)
        self.assertIn("PasswordVisualTransformation()", dialog)
        self.assertIn("InputPolicy.MAX_TAG_CHARS", dialog)
        self.assertIn('pin = ""\n                onSubmit(submitted)', dialog)
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("vault.submitPin(request.entryId, pin)", host)
        self.assertIn("dialogs.close()", host)
        widgets = (UI_MAIN / "vault/VaultComponents.kt").read_text()
        self.assertIn("card.pinRequired && enterPin != null", widgets)

    def test_counter_confirmation_is_shared_and_host_keeps_only_an_id(self):
        dialog = (UI_MAIN / "vault/EntryConfirmationDialog.kt").read_text()
        self.assertIn("AlertDialog(", dialog)
        self.assertIn("onDismissRequest = onDismiss", dialog)
        self.assertNotIn("rememberSaveable", dialog)
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("VaultEntryDialog(", host)
        self.assertIn("VaultDialogs { revision++ }", host)
        self.assertIn("vault.advanceCounter(request.entryId)", host)
        self.assertIn("dialogs.close()", host)

    def test_dialog_routing_and_failure_wording_live_in_shared_ui(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        # PIN/counter/delete routing remains shared; copy failure notices are host outcomes.
        routed = host.split("    val request = dialogs.pending", 1)[1]
        self.assertNotIn("EntryPinDialog(", routed)
        self.assertNotIn("EntryConfirmationDialog(", routed)
        self.assertNotIn("could not be saved", routed)
        self.assertEqual(2, host.count("dialogs.isCurrent(request)"))
        renderer = (UI_MAIN / "vault/VaultEntryDialog.kt").read_text()
        for kind in ("PIN", "COUNTER", "DELETE"):
            self.assertIn("VaultDialogKind." + kind, renderer)
        self.assertIn("inputKey = request", renderer)
        self.assertIn("strings: VaultDialogStrings", renderer)

    def test_token_loop_resamples_host_wall_clock(self):
        countdown = (UI_MAIN / "vault/Countdown.kt").read_text()
        self.assertIn("LaunchedEffect(clock)", countdown)
        self.assertIn("val sample = clock.sample()", countdown)
        self.assertNotIn("TimeSource.Monotonic", countdown)
        self.assertNotIn("anchorMillis", countdown)
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("VaultClock(System::currentTimeMillis)", host)
        self.assertIn("rememberNowMillis(clock)", host)

    def test_host_gate_prefers_fresh_shared_classes(self):
        script = (SANDBOX / "compile-ui.sh").read_text()
        self.assertIn('$OUT/core:$OUT/model:$OUT/ui:$DESKTOP_OUT/desktop', script)

    def test_compose_host_delegates_interactions_to_presenter(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        self.assertIn("VaultPresenter(", host)
        self.assertIn("actions = presenter.actions(copyEntry =", host)
        self.assertNotIn("filter.withQuery", host)
        self.assertNotIn("mutableStateOf(emptySet<Long>())", host)

    def test_email_creation_is_optional_ephemeral_and_deadline_guarded(self):
        dialog = (UI_MAIN / "vault/EmailEntryDialog.kt").read_text()
        manual = (UI_MAIN / "vault/ManualEntryDialog.kt").read_text()
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        self.assertIn("remember(inputKey)", dialog)
        self.assertNotIn("rememberSaveable", dialog)
        self.assertIn("input = input.withoutSecrets(); onSubmit(submitted)", dialog)
        self.assertIn("PasswordVisualTransformation()", dialog)
        self.assertIn("onEmailSubmit: ((EmailEntryInput) -> Unit)? = null", manual)
        self.assertIn("input = ManualEntryInput(); email = true", manual)
        self.assertIn("lifetime.checkActive() && creation.submitEmail(addRequest, input, lifetime::checkActive)", host)

    def test_plaintext_email_export_warns_and_requires_current_confirmation(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        owner = (ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/DesktopEmailExport.kt").read_text()
        self.assertIn("Export plaintext secrets", host)
        self.assertIn("including hidden or filtered-out accounts", host)
        self.assertIn("partial plaintext file may remain", host)
        self.assertIn("emailExporter.confirm(request, lifetime::checkActive)", host)
        self.assertGreaterEqual(host.count("emailExporter.close()"), 2)
        self.assertIn("request.status != Status.CONFIRM", owner)
        self.assertIn("ExclusiveExportFile::writeNew", owner)
        self.assertIn("finally { data.fill(0) }", owner)

    def test_email_credential_editor_is_optional_masked_and_live_guarded(self):
        dialog = (UI_MAIN / "vault/EmailPasswordDialog.kt").read_text()
        details = (UI_MAIN / "vault/EmailDetailsDialog.kt").read_text()
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        self.assertIn("onEditPasswords: (() -> Unit)? = null", details)
        self.assertIn("remember(inputKey)", dialog)
        self.assertNotIn("rememberSaveable", dialog)
        self.assertIn("PasswordVisualTransformation()", dialog)
        self.assertIn("input = input.withoutSecrets(); onSubmit(submitted)", dialog)
        self.assertIn("lifetime.checkActive() && editing.submitEmailPasswords", host)
        self.assertIn("emailDetails.rows(detailsRequest, presenter.isRevealed(detailsRequest.entryId)) != null", host)

    def test_all_compose_add_edit_paths_supply_live_save_guard(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        for call in ("creation.submit(addRequest, input, lifetime::checkActive)",
                     "creation.submitEmail(addRequest, input, lifetime::checkActive)"):
            self.assertIn(call, host)
        for method in ("submit", "submitPassword", "submitEmailPasswords", "submitEmailSettings"):
            self.assertIn(f"editing.{method}(editRequest, input, presenter.isRevealed(editRequest.entryId), lifetime::checkActive)", host)

    def test_password_export_is_explicit_plaintext_and_lifetime_guarded(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        dialog = (UI_MAIN / "vault/PasswordExportDialog.kt").read_text()
        self.assertIn("passwordExporter.confirm(request, lifetime::checkActive)", host)
        self.assertIn("passwordExporter.choose(request, index, target, lifetime::checkActive)", host)
        self.assertGreaterEqual(host.count("passwordExporter.close()"), 2)
        self.assertIn("OTP seeds and provider-specific secrets", host)
        self.assertIn("spreadsheet formulas", dialog)
        self.assertIn("password archives", dialog)

    def test_archive_export_requires_plaintext_consent_and_ignores_stored_paths(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        picker = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/DesktopSavePicker.kt").read_text()
        dialog = (UI_MAIN / "vault/ArchiveExportDialog.kt").read_text()
        self.assertIn("archiveExporter.confirm(request, lifetime::checkActive)", host)
        self.assertIn("archiveExporter.choose(request, index, target, lifetime::checkActive)", host)
        self.assertGreaterEqual(host.count("archiveExporter.close()"), 2)
        self.assertIn('picker.file = "password-archive.bin"', picker)
        self.assertIn("exact imported bytes", dialog)
        self.assertIn("No encryption is added", host)

    def test_rekey_host_blocks_unlock_and_exit_and_uses_ephemeral_masked_form(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        dialog = (UI_MAIN / "vault/PasswordChangeDialog.kt").read_text()
        self.assertIn("if (rekey.canCloseWindow && enrollment?.busy != true)", host)
        self.assertIn("vault == null && rekey.canUnlock", host)
        self.assertIn("rekey.sessionLocked()", host)
        self.assertIn("onChangePassword(lifetime::checkActive, presenter::lock)", host)
        self.assertIn("if (!busy)", dialog)
        self.assertIn("PasswordVisualTransformation()", dialog)
        self.assertIn("remember(inputKey)", dialog)
        self.assertNotIn("rememberSaveable", dialog)
        self.assertIn("clear(); onSubmit(a, b, c)", dialog)

    def test_storage_location_uses_startup_path_and_plain_read_only_text(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        dialog = (UI_MAIN / "vault/StorageLocationDialog.kt").read_text()
        self.assertIn("ComposeVaultApp(controller, DesktopStorageLocation(directory))", host)
        self.assertIn("directory = location.dataDirectory", host)
        self.assertIn("showStorageLocation = false", host)
        self.assertIn("Text(text = directory)", dialog)
        self.assertIn("data subfolder is appended automatically", dialog)
        self.assertNotIn("rememberSaveable", dialog)
        self.assertNotIn("Clipboard", dialog)

    def test_hardware_oath_ui_has_explicit_counter_action_and_lifetime_guard(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        dialog = (UI_MAIN / "vault/HardwareOathDialog.kt").read_text()
        self.assertIn("hardwareOath.submit(request, pin, index, lifetime::checkActive)", host)
        self.assertGreaterEqual(host.count("hardwareOath.close()"), 2)
        self.assertIn("Advance HOTP counter and show code", dialog)
        self.assertIn("not the FIDO PIN", dialog)
        self.assertIn("pin.toCharArray(); pin =", dialog)
        self.assertNotIn("rememberSaveable", dialog)

    def test_hardware_copy_requires_explicit_seed_export_and_touch(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        dialog = (UI_MAIN / "vault/HardwareCopyDialog.kt").read_text()
        self.assertIn("requireTouch = true", host)
        self.assertIn("hardwareCopy.submit(request, pin, lifetime::checkActive)", host)
        self.assertIn("hardwareCopy.select(request, index, lifetime::checkActive)", host)
        self.assertGreaterEqual(host.count("hardwareCopy.close()"), 2)
        self.assertIn("Export seed to connected key", dialog)
        self.assertIn("Cancellation cannot undo", dialog)
        self.assertIn("pin.toCharArray(); pin =", dialog)
        self.assertNotIn("rememberSaveable", dialog)

    def test_hardware_unlock_is_optional_serialized_and_ephemeral(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        screen = (UI_MAIN / "unlock/UnlockScreen.kt").read_text()
        dialog = (UI_MAIN / "unlock/HardwareUnlockDialog.kt").read_text()
        self.assertIn("onHardwareUnlock: (() -> Unit)? = null", screen)
        self.assertIn("DesktopHardwareUnlock(worker", host)
        self.assertIn("!busy && !hardwareDialog", host)
        self.assertIn("hardwareUnlock.close(); operation.close(); worker.shutdown()", host)
        self.assertIn("pin.toCharArray(); pin =", dialog)
        self.assertIn("PasswordVisualTransformation()", dialog)
        self.assertNotIn("rememberSaveable", dialog)

    def test_hardware_removal_requires_consent_and_distinguishes_local_record(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        self.assertIn("hardwareRemoval.confirm(request, lifetime::checkActive)", host)
        self.assertGreaterEqual(host.count("hardwareRemoval.close()"), 2)
        self.assertIn("Make sure you know the vault password", host)
        self.assertIn("revoke copies of this record elsewhere", host)
        self.assertIn("do not assume hardware unlock was disabled", host)

    def test_hardware_enrollment_blocks_unlock_exit_and_serializes_old_hardware_work(self):
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        dialog = (UI_MAIN / "unlock/HardwareEnrollmentDialog.kt").read_text()
        self.assertIn("rekey.canCloseWindow && enrollment?.busy != true", host)
        self.assertIn("rekey.canUnlock && enrollment == null", host)
        self.assertIn("DesktopHardwareEnrollment(vault, hardwareWorker", host)
        self.assertIn("onEnrollHardware(lifetime::checkActive, presenter::lock, exportWorker)", host)
        self.assertIn("Lock and replace local enrollment", dialog)
        self.assertIn("REPLACES", dialog)
        self.assertIn("PasswordVisualTransformation()", dialog)
        self.assertNotIn("rememberSaveable", dialog)

    def test_projection_tests_live_with_the_frame_layer(self):
        names = {path.name for path in kotlin_files(MODEL_TEST)}
        self.assertEqual(
            {"TokenDisplayTest.kt", "VaultProjectionTest.kt", "VaultFilterTest.kt",
             "VaultCardsTest.kt", "VaultPresenterTest.kt", "VaultDialogsTest.kt", "VaultFrameHostTest.kt", "VaultClockTest.kt", "VaultAppearanceTest.kt", "ManualEntryInputTest.kt", "EmailEntryInputTest.kt", "EmailPasswordInputTest.kt", "EmailSettingsInputTest.kt", "PasswordEntryInputTest.kt", "VaultToolsTest.kt"}, names
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

    def test_compose_artwork_uses_host_loaders_and_preserves_privacy(self):
        widgets = (UI_MAIN / "vault/VaultComponents.kt").read_text()
        self.assertIn("appearance.showThumbnails && card.detailsVisible", widgets)
        self.assertIn("remember(artwork, card.thumbnailName)", widgets)
        self.assertIn("contentScale = ContentScale.Fit", widgets)
        self.assertIn("contentDescription = null", widgets)
        self.assertIn("if (image == null)", widgets)
        self.assertIn("VaultLetterTile(", widgets)
        seam = (UI_ANDROID / "vault/VaultComposeView.kt").read_text()
        self.assertIn("loader(name)?.asImageBitmap()", seam)
        desktop = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("IssuerIcons.artwork(name)?.toComposeImageBitmap()", desktop)
        android = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        self.assertIn("AndroidIssuerArtwork(this)::load", android)
        for path in kotlin_files(MODEL_MAIN):
            self.assertNotIn("ImageBitmap", path.read_text(), "images must not enter the toolkit-free frame layer")

    def test_android_layout_preferences_reach_the_shared_renderer(self):
        controller = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/View/AndroidVaultController.kt").read_text()
        for call in ("settings.getLabelSize()", "settings.getThumbnailSize() / density", "settings.getCardLayout()"):
            self.assertIn(call, controller)
        widgets = (UI_MAIN / "vault/VaultComponents.kt").read_text()
        for marker in ("Modifier.basicMarquee", "appearance.labelSizeSp", "appearance.labelMaxLines",
                       "appearance.thumbnailSizeDp", "appearance.codeBelowIdentity"):
            self.assertIn(marker, widgets)

    def test_android_token_display_preferences_reach_the_shared_model(self):
        controller = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/View/AndroidVaultController.kt").read_text()
        for expression in ("tokenGroupSize = settings.getTokenSplitGroupSize()",
                           "showIssuer = !settings.isHideIssuerEnabled()",
                           "showPreviousToken = settings.getShowPrevToken()",
                           "showIndividualCountdowns = settings.isShowIndividualTimeoutsEnabled()",
                           "hideCountdownWhenMasked = settings.getTapToReveal()"):
            self.assertIn(expression, controller)
        tools = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Utilities/Tools.kt").read_text()
        self.assertIn("TokenFormat.groupFromRight(s, chunkSize)", tools)
        widgets = (UI_MAIN / "vault/VaultComponents.kt").read_text()
        self.assertIn("card.hasCountdown && card.showCountdown", widgets)

    def test_activity_publishes_compose_frames_instead_of_attaching_recycler(self):
        activity = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        self.assertNotIn("EntriesCardAdapter", activity)
        self.assertNotIn("RecyclerView", activity)
        self.assertNotIn("touchHelperCallback", activity)
        self.assertIn("VaultComposeView(this", activity)
        self.assertIn("view.render(published.state, VaultActions(", activity)
        self.assertIn("composeVaultGeneration != generation", activity)
        self.assertIn("val appearance = controller.composeAppearance()", activity)
        layout = (ROOT / "app/src/main/res/layout/activity_main.xml").read_text()
        self.assertNotIn("androidx.recyclerview.widget.RecyclerView", layout)
        self.assertIn("project(':ui')", (ROOT / "app/build.gradle").read_text())

    def test_android_frame_bridge_keeps_async_publication_and_live_identity_checks(self):
        controller = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/View/AndroidVaultController.kt").read_text()
        self.assertIn("VaultProjection(entries, presentation).snapshot(", controller)
        self.assertNotIn(".applyFilter(", controller)
        self.assertIn("presentation.selection()", controller)
        self.assertIn("VaultFilterCombination.SEARCH_OVERRIDES_TAGS", controller)
        self.assertIn("target.resolve(entries, generation)", controller)
        self.assertIn("!VaultSession.isCurrent(generation)", controller)
        self.assertIn('cardActions.onCopyButtonClicked("", position)', controller)
        self.assertIn("command != AndroidCardCommand.PRIVACY && !entry.areDetailsVisible()", controller)
        self.assertIn("project(':ui-model')", (ROOT / "app/build.gradle").read_text())

    def test_shared_custom_period_uses_android_localization_and_privacy_gating(self):
        activity = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/Activities/MainActivity.kt").read_text()
        self.assertIn("getString(R.string.format_custom_period, seconds)", activity)
        widgets = (UI_MAIN / "vault/VaultComponents.kt").read_text()
        self.assertIn("card.detailsVisible && customPeriod != null", widgets)
        self.assertIn("text = strings.customPeriod(customPeriod)", widgets)
        controller = (ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/View/AndroidVaultController.kt").read_text()
        self.assertIn("customPeriodSeconds = null", controller)

    def test_legacy_card_renderer_is_test_only(self):
        app = ROOT / "app/src/main/kotlin"
        legacy = {"EntriesCardAdapter", "EntryViewHolder", "SimpleDoubleClickListener",
                  "ItemTouchHelperAdapter", "ItemTouchHelperViewHolder", "SimpleItemTouchHelperCallback"}
        for path in app.rglob("*.kt"):
            text = path.read_text()
            for name in legacy:
                self.assertNotIn(name, text, f"{path} depends on a legacy renderer fixture")
            self.assertNotIn("androidx.recyclerview", text)
        gradle = (ROOT / "app/build.gradle").read_text()
        self.assertIn("androidTestImplementation 'androidx.recyclerview:", gradle)
        self.assertNotIn("implementation 'androidx.recyclerview:", gradle)
        harness = (ROOT / "scripts/kotlin-sandbox/run.sh").read_text()
        production = harness.split("APP_SOURCES=(", 1)[1].split("\n)", 1)[0]
        self.assertNotIn("androidTest", production)
        for name in legacy:
            self.assertNotIn(name, production)
        self.assertIn('-Xfriend-paths="$OUT/app" "${LEGACY_FIXTURES[@]}"', harness)

    def test_android_controller_owns_vault_rules_without_recycler_dependencies(self):
        view = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp/View"
        controller = (view / "AndroidVaultController.kt").read_text()
        renderer = (ROOT / "app/src/androidTest/kotlin/com/fairuniversal/offlineotp/View/EntriesCardAdapter.kt").read_text()
        for forbidden in ("RecyclerView", "EntryViewHolder", "LayoutInflater", "component_card_"):
            self.assertNotIn(forbidden, controller)
        for rule in ("VaultSession.MutationLock", "presentation.publishSearch", "persistence.save("):
            self.assertIn(rule, controller)
            self.assertNotIn(rule, renderer)
        self.assertIn("controller.cardActions", renderer)
        self.assertIn("controller.prepareEntry(i)", renderer)
        self.assertIn("override fun changed() = notifyDataSetChanged()", renderer)
        self.assertIn("interface EntryCardEvents", (view / "EntryCardEvents.kt").read_text())

    def test_manual_editor_accepts_a_renderer_independent_host(self):
        app = ROOT / "app/src/main/kotlin/com/fairuniversal/offlineotp"
        dialog = (app / "Dialogs/ManualEntryDialog.kt").read_text()
        self.assertNotIn("EntriesCardAdapter", dialog)
        self.assertIn("adapter: EntryEditorHost", dialog)
        contract = (app / "View/EntryEditorHost.kt").read_text()
        self.assertIn("interface EntryEditorHost", contract)
        for method in ("getTags", "getTagCounts", "addEntry", "replaceEntry"):
            self.assertIn("fun " + method + "(", contract)
        self.assertNotIn("import android", contract)
        self.assertIn('"$KOTLIN_APP/View/EntryEditorHost.kt"', (SANDBOX / "run.sh").read_text())

    def test_preferences_import_refreshes_only_mentioned_settings_after_lifetime_check(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopPreferencesImport(vault, exportWorker", host)
        self.assertIn("preferencesImporter.confirm(preferencesRequest, lifetime::checkActive)", host)
        for key in ("UI_INACTIVITY_LOCK_SECONDS", "UI_CARD_LAYOUT", "UI_SORT_MODE"):
            self.assertIn(f"plan.contains(PreferencesExport.{key})", host)
        self.assertIn("preferencesImporter.close()", host)
        form = (UI_MAIN / "vault/PreferencesImportDialog.kt").read_text()
        self.assertIn("A file may disable auto-lock", form)
        self.assertIn("enabled = !busy && count != null", form)

    def test_preferences_export_uses_worker_and_rechecks_lifetime(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopPreferencesExport(vault, exportWorker", host)
        self.assertIn("preferencesExporter.submit(target, lifetime::checkActive)", host)
        self.assertIn("preferencesExporter.close()", host)
        self.assertIn("No vault entries, passwords or authentication material", host)

    def test_openpgp_export_confirms_scope_and_recipient(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopOpenPgpExport(vault, exportWorker", host)
        self.assertIn("openPgpExporter.submit(pgpExportRequest, recipientFile)", host)
        self.assertIn("openPgpExporter.close()", host)
        form = (UI_MAIN / "vault/OpenPgpExportDialog.kt").read_text()
        self.assertIn("including concealed entries and regardless of filters", form)
        self.assertIn("intended recipient", form)
        self.assertIn("enabled = !busy && keySelected", form)

    def test_openpgp_import_requires_key_and_live_confirmation(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopOpenPgpImport(vault, exportWorker", host)
        self.assertIn("openPgpImporter.confirmRestore(openPgpRequest, replace, lifetime::checkActive)", host)
        self.assertIn("openPgpImporter.close()", host)
        form = (UI_MAIN / "vault/OpenPgpImportDialog.kt").read_text()
        self.assertIn("count != null || keySelected", form)
        self.assertIn("mutableStateOf(false)", form)
        self.assertIn("Replace all entries", form)
        self.assertIn("delete all current entries", form)
        self.assertIn("PasswordVisualTransformation()", form)
        self.assertNotIn("rememberSaveable", form)

    def test_linscp_export_uses_detached_worker_path_and_explains_scope(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("seal = DesktopLinScpExport::seal", host)
        self.assertIn("snapshot = { DesktopLinScpExport.snapshot(vault) }", host)
        self.assertIn("including concealed entries, regardless of filters", host)
        self.assertIn("linScpExporter.close()", host)
        self.assertIn("linScpExporter.submit(linScpExportRequest, password, confirmation)", host)

    def test_secondary_tools_are_grouped_scrolled_and_closed_before_dispatch(self):
        screen = (UI_MAIN / "vault/VaultScreen.kt").read_text()
        self.assertIn("VaultToolsBar(actions = actions, strings = strings)", screen)
        self.assertNotIn("actions.onImportLinScp", screen)
        tools = (UI_MAIN / "vault/VaultToolsBar.kt").read_text()
        self.assertIn("VaultTool.available(actions)", tools)
        self.assertIn("heightIn(max = 400.dp).verticalScroll", tools)
        self.assertIn("horizontalScroll", tools)
        self.assertIn("actions.onAddEntry", tools)
        self.assertIn("expanded = false\n                        tool.invoke(actions)", tools)
        self.assertNotIn("rememberSaveable", tools)

    def test_linscp_import_guards_native_key_selection_and_confirmation(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopLinScpImport(vault, exportWorker", host)
        self.assertIn("linScpImporter.confirm(linScpRequest, lifetime::checkActive)", host)
        self.assertIn("lifetime.checkActive() && linScpImporter.pending === linScpRequest && selected != null", host)
        self.assertIn("linScpImporter.close()", host)
        form = (UI_MAIN / "vault/LinScpImportDialog.kt").read_text()
        self.assertIn("PasswordVisualTransformation()", form)
        self.assertNotIn("rememberSaveable", form)
        self.assertIn('password = ""; onRead(submitted)', form)

    def test_layout_settings_load_persist_and_apply_without_recreating_presenter(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("appearance = VaultAppearance(cardLayout = controller.savedCardLayout())", host)
        self.assertIn("layoutSettings.submit(layoutRequest, layout, lifetime::checkActive, presenter::setCardLayout)", host)
        self.assertIn("layoutSettings.close()", host)
        form = (UI_MAIN / "vault/CardLayoutDialog.kt").read_text()
        self.assertIn("Constants.CardLayouts.values()", form)
        self.assertIn('ForestTextAction(text = "Save"', form)

    def test_email_details_rechecks_reveal_and_password_edit_is_reachable(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("emailDetails.rows(detailsRequest, presenter.isRevealed(detailsRequest.entryId))", host)
        self.assertIn("emailDetails.close()", host)
        self.assertIn("PasswordEntryInput.supports(it)", host)
        components = (UI_MAIN / "vault/VaultComponents.kt").read_text()
        self.assertIn("card.kind == VaultEntryKind.PASSWORDS && card.canEditPassword", components)
        form = (UI_MAIN / "vault/EmailDetailsDialog.kt").read_text()
        self.assertIn("Show passwords", form)
        self.assertNotIn("rememberSaveable", form)
        self.assertNotIn("SelectionContainer", form)

    def test_email_import_uses_worker_confirmation_and_clears_password_draft(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopEmailImport(vault, exportWorker", host)
        self.assertIn("emailImporter.confirm(emailRequest, lifetime::checkActive)", host)
        self.assertIn("emailImporter.close()", host)
        form = (UI_MAIN / "vault/EmailImportDialog.kt").read_text()
        self.assertIn("PasswordVisualTransformation()", form)
        self.assertNotIn("rememberSaveable", form)
        self.assertIn('password = ""; onRead(submitted)', form)

    def test_password_import_uses_worker_confirmation_and_terminal_cleanup(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopPasswordImport(vault, exportWorker", host)
        self.assertIn("passwordImporter.confirm(passwordRequest, lifetime::checkActive)", host)
        self.assertIn("passwordImporter.close()", host)
        form = (UI_MAIN / "vault/PasswordImportDialog.kt").read_text()
        self.assertIn("PasswordImport.Mode.INDIVIDUAL", form)
        self.assertIn("PasswordImport.Mode.ARCHIVE", form)
        self.assertIn("does not delete or protect the source file", form)
        self.assertIn("No current entries will be replaced", form)

    def test_password_edit_clears_drafts_and_checks_live_reveal(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("editing.submitPassword(editRequest, input, presenter.isRevealed(editRequest.entryId), lifetime::checkActive)", host)
        form = (UI_MAIN / "vault/PasswordEntryDialog.kt").read_text()
        self.assertIn("PasswordVisualTransformation()", form)
        self.assertIn("input = input.withoutPassword()", form)
        self.assertNotIn("rememberSaveable", form)

    def test_qr_image_read_is_guarded_and_requires_explicit_import(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopQrImageRead(exportWorker", host)
        self.assertIn("imageReader.close()", host)
        self.assertIn("lifetime.checkActive() && !vault.isLocked && uriImporter.pending === importRequest", host)
        self.assertIn("onImportImage = { allow ->", host)
        form = (UI_MAIN / "vault/UriImportDialog.kt").read_text()
        self.assertIn("enabled = !imageBusy && !imageReady", form)
        self.assertIn("Discard image payload", form)
        self.assertIn("Choose Import to validate and save it", form)

    def test_uri_import_bounds_masks_and_clears_seed_payloads(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("uriImporter.submit(importRequest, uri, allow, lifetime::checkActive)", host)
        self.assertIn("uriImporter.close()", host)
        form = (UI_MAIN / "vault/UriImportDialog.kt").read_text()
        self.assertIn("InputPolicy.MAX_URI_CHARS", form)
        self.assertIn("PasswordVisualTransformation()", form)
        self.assertNotIn("rememberSaveable", form)
        self.assertIn('val submitted = uri; uri = ""; onSubmit', form)
        self.assertIn("enabled = !collecting", form)

    def test_optional_secret_removal_is_explicit_edit_only_and_undoable(self):
        form = (UI_MAIN / "vault/ManualEntryDialog.kt").read_text()
        self.assertIn("Removal choices apply only when you Save", form)
        for flag in ("clearPin", "clearHiddenLabel", "clearSshPassword"):
            self.assertIn(f"if (!input.{flag}) EntryInputField", form)
            self.assertIn(f"{flag} = !input.{flag}", form)
        self.assertIn("Will remove PIN — undo", form)
        self.assertIn("Will remove SSH password — undo", form)
        self.assertIn("Will remove protected label — undo", form)

    def test_encrypted_restore_requires_confirmation_and_lifetime_checks(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopEncryptedRestore(vault, exportWorker", host)
        self.assertIn("source != null && lifetime.checkActive() && !vault.isLocked", host)
        self.assertIn("restorer.confirm(restoreRequest, replace, lifetime::checkActive)", host)
        self.assertIn("restorer.close()", host)
        form = (UI_MAIN / "vault/EncryptedRestoreDialog.kt").read_text()
        self.assertIn("mutableStateOf(false)", form)
        self.assertIn("PasswordVisualTransformation()", form)
        self.assertNotIn("rememberSaveable", form)
        self.assertIn("Replace all entries", form)
        self.assertIn('password = ""; onDecode(text)', form)

    def test_encrypted_export_uses_a_worker_and_rechecks_after_native_picker(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopEncryptedExport(vault, exportWorker", host)
        self.assertIn("target != null && lifetime.checkActive() && !vault.isLocked", host)
        self.assertIn("exporter.close()", host)
        self.assertIn("exportWorker.shutdown()", host)
        picker = (COMPOSE_HOST / "DesktopSavePicker.kt").read_text()
        self.assertIn("FileDialog.SAVE", picker)
        self.assertNotIn("javax.swing", picker)
        form = (UI_MAIN / "vault/EncryptedExportDialog.kt").read_text()
        self.assertIn("PasswordVisualTransformation()", form)
        self.assertNotIn("rememberSaveable", form)
        self.assertIn('password = ""; confirmation = ""', form)
        self.assertIn("password == confirmation", form)

    def test_auto_lock_settings_are_guarded_and_do_not_recreate_the_session(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("controller::trySaveInactivityLockSeconds", host)
        self.assertIn("autoLock.submit(timeoutRequest, seconds, lifetime)", host)
        self.assertIn("autoLock.close()", host)
        self.assertIn("val lifetime = remember(presenter)", host)
        form = (UI_MAIN / "vault/AutoLockDialog.kt").read_text()
        self.assertIn("remember(inputKey)", form)
        self.assertIn("onSave(selected)", form)
        self.assertNotIn("VaultController", form)

    def test_compose_manual_ordering_binds_the_frame_and_checks_drag_policy(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopEntryMove(vault, EntryEditor(controller, vault)::move)", host)
        self.assertIn("val moveFrame = mover.bind(state.cards)", host)
        self.assertIn("canMoveUp = vault.presentation.canDrag()", host)
        self.assertIn("mover.move(moveFrame, card, delta, lifetime::checkActive)", host)
        owner = (ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/DesktopEntryMove.kt").read_text()
        self.assertIn("shown.getOrNull(from + delta) !== neighbor", owner)
        self.assertIn("state.isLocked || !state.presentation.canDrag()", owner)

    def test_desktop_unlock_runs_off_ui_thread_and_releases_late_results(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopUnlockOperation(worker", host)
        self.assertIn("EventQueue.invokeLater(task)", host)
        self.assertIn("operation.close(); worker.shutdown()", host)
        self.assertIn("if (closing) DesktopUnlockOperation.discard(loaded)", host)
        unlock = (UI_MAIN / "unlock/UnlockScreen.kt").read_text()
        self.assertIn('password = ""\n                    onSubmit(submitted)', unlock)
        self.assertIn("enabled = !busy", unlock)

    def test_compose_copy_is_live_persisted_and_expires(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertNotIn("copyCode = ::copyToken", host)
        self.assertIn("val copyFrame = copier.bind(state.cards)", host)
        self.assertIn("presenter.isRevealed(request.entryId)", host)
        self.assertIn("ExpiringClipboard(scheduler, AwtClipboard)", host)
        self.assertIn("copier.close()", host)
        self.assertIn("card.kind != VaultEntryKind.SSH_KEYS", host)
        chooser = (UI_MAIN / "vault/EntryCopyDialog.kt").read_text()
        self.assertNotIn("VaultCardState", chooser)
        self.assertNotIn("EntryEditor", chooser)

    def test_manual_thumbnail_picker_uses_catalog_and_host_artwork_without_live_mutation(self):
        picker = (UI_MAIN / "vault/ThumbnailPicker.kt").read_text()
        self.assertIn("ThumbnailCatalog.NAMES.filter", picker)
        self.assertIn("ignoreCase = true", picker)
        self.assertIn("LazyColumn(modifier = Modifier.height(240.dp))", picker)
        self.assertIn("remember(artwork, name)", picker)
        self.assertNotIn("EntryEditor", picker)
        form = (UI_MAIN / "vault/ManualEntryDialog.kt").read_text()
        self.assertIn("input = input.copy(thumbnailName = it)", form)
        self.assertIn("onCancel = { pickingThumbnail = false }", form)
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("inputKey = addRequest, artwork = artwork", host)
        self.assertIn("editing = true, artwork = artwork", host)

    def test_desktop_editing_reuses_the_form_and_rechecks_live_reveal(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopEntryEditing(vault, EntryEditor(controller, vault)::replace)", host)
        self.assertIn("presenter.isRevealed(editRequest.entryId)", host)
        self.assertIn("editing.close()", host)
        form = (UI_MAIN / "vault/ManualEntryDialog.kt").read_text()
        self.assertIn("if (!editing) for (type", form)
        owner = (ROOT / "desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/control/DesktopEntryEditing.kt").read_text()
        self.assertIn("it === request.target", owner)
        self.assertIn("replacement.destroy()", owner)

    def test_desktop_manual_creation_uses_shared_form_and_guarded_persistence(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopEntryCreation(vault, EntryEditor(controller, vault)::add)", host)
        self.assertIn("creation.close()", host)
        self.assertIn("ManualEntryDialog(", host)
        form = (UI_MAIN / "vault/ManualEntryDialog.kt").read_text()
        self.assertIn("remember(inputKey)", form)
        self.assertNotIn("rememberSaveable", form)
        self.assertIn("input = input.withoutSecrets()", form)
        self.assertIn("PasswordVisualTransformation()", form)

    def test_desktop_compose_session_lifetime_is_wired_without_swing_widgets(self):
        host = (COMPOSE_HOST / "ComposeMain.kt").read_text()
        self.assertIn("DesktopSessionLifetime(scheduler, controller.savedInactivityLockSeconds(), startImmediately = false)", host)
        self.assertIn("lifetime.start()", host)
        self.assertIn("DisposableEffect(presenter)", host)
        self.assertIn("presenter.lock()", host)
        self.assertIn("toolkit.removeAWTEventListener(listener)", host)
        self.assertIn("try { lifetime.close() } finally { scheduler.close(); exportWorker.shutdown() }", host)
        self.assertIn("session?.lock()", host)
        self.assertIn("source === window", host)
        for source in COMPOSE_HOST.glob("*.kt"):
            self.assertNotIn("javax.swing", source.read_text())
        gate = (ROOT / "scripts/kotlin-sandbox/compile-ui.sh").read_text()
        self.assertIn('JvmTestRunnerKt "$OUT/host-tests"', gate)

    def test_android_seam_is_a_thin_view_and_holds_no_session(self):
        """Stage 2 uses the supported AbstractComposeView extension point."""
        seam = UI_ANDROID / "vault/VaultComposeView.kt"
        self.assertTrue(seam.is_file(), "missing the Android ComposeView seam")
        text = seam.read_text(encoding="utf-8")
        self.assertIn("class VaultComposeView(", text)
        self.assertIn(") : AbstractComposeView(context)", text)
        self.assertIn("override fun Content()", text)
        self.assertNotIn("setContent {", text)
        self.assertIn("fun render(next: VaultUiState)", text)
        self.assertIn("fun clear()", text)
        self.assertIn("host.close()", text)
        self.assertIn("if (host.render(next))", text)
        self.assertIn("actions = renderedActions", text)
        self.assertIn("DisposeOnViewTreeLifecycleDestroyed", text)
        self.assertIn("VaultScreenHost(", text)
        for forbidden in ("SecretKey", "VaultSession", "DesktopVaultState", "PasswordVault",
                          "SharedPreferences", "Settings"):
            self.assertNotIn(forbidden, text)
        self.assertNotIn("VaultEntryCard(", text)
        self.assertNotIn("Column(", text)

    def test_android_stub_does_not_allow_subclassing_final_compose_view(self):
        stub = (COMPOSE_STUBS / "android/androidx/compose/ui/platform/ComposeView.kt").read_text()
        self.assertIn("abstract class AbstractComposeView(", stub)
        self.assertIn("abstract fun Content()", stub)
        self.assertIn("class ComposeView(", stub)
        self.assertNotIn("open class ComposeView", stub)
        self.assertIn(": AbstractComposeView(context, attrs, defStyleAttr)", stub)

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
