"""Guards for the iOS app shell: project definition, palette, and the promise to stay offline.

The shell is the first place an OfflineOTP client draws a code, so the things worth pinning are the
ones that would silently diverge from the other clients: the colour tokens, the minimum password
length, the fact that nothing in the app talks to a network, and the shape of the build (a simulator
build with no signing identity).
"""
import re
import unittest
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
IOS = ROOT / "ios"
APP = IOS / "App"
SOURCES = APP / "Sources"
APP_TESTS = APP / "Tests"
PALETTE = (SOURCES / "ForestPalette.swift").read_text(encoding="utf-8")
FOREST = ROOT / "desktop/src/main/kotlin/org/shadowice/flocke/andotp/desktop/ui/ForestTheme.kt"
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
            [{"package": "OfflineOTPKit", "product": "OfflineOTPKit"}], target["dependencies"])
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

    def test_the_project_format_is_one_the_runner_can_open(self):
        """Xcode 15.4 on `macos-14` cannot read a project generated in the newest format."""
        project = yaml.safe_load((IOS / "project.yml").read_text(encoding="utf-8"))
        options = project["options"]
        self.assertEqual("xcode15_3", options["projectFormat"])
        self.assertEqual("15.4", options["xcodeVersion"])
        workflow = WORKFLOW
        self.assertIn("runs-on: macos-14", workflow)
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

    def test_the_app_is_a_single_swiftui_entry_point(self):
        sources = app_sources()
        self.assertIn("@main", sources["OfflineOTPApp.swift"])
        self.assertIn("struct OfflineOTPApp: App", sources["OfflineOTPApp.swift"])
        self.assertIn("import SwiftUI", sources["OfflineOTPApp.swift"])
        # Everything that touches the vault goes through the store that the app tests can drive.
        self.assertIn("import OfflineOTPKit", sources["VaultStore.swift"])
        self.assertIn("class VaultStore: ObservableObject", sources["VaultStore.swift"])
        for name in ("UnlockView.swift", "EntryListView.swift"):
            self.assertIn("import SwiftUI", sources[name])
        # Only one file constructs a vault, so the file locations cannot drift between views.
        constructors = [name for name, text in sources.items() if "PasswordVault(" in text]
        self.assertEqual(["VaultStore.swift"], constructors)

    def test_the_app_stays_offline(self):
        """The phone and desktop clients declare no networking; the iOS client must not add one."""
        for path in list(SOURCES.glob("*.swift")) + list(APP_TESTS.glob("*.swift")):
            text = path.read_text(encoding="utf-8")
            for forbidden in ("URLSession", "NSURLConnection", "CFNetwork", "NWConnection",
                              "http://", "https://", "Network.framework"):
                self.assertNotIn(forbidden, text, f"{path.name} must not use {forbidden}")

    def test_the_palette_is_the_shared_dark_forest(self):
        """Every token the app draws with comes from the same numbers as the desktop theme."""
        forest = FOREST.read_text(encoding="utf-8")
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
        for kotlin_name, swift_name in tokens.items():
            kotlin = re.search(
                rf"val {kotlin_name} = ColorUIResource\(0x([0-9A-Fa-f]{{2}}), "
                rf"0x([0-9A-Fa-f]{{2}}), 0x([0-9A-Fa-f]{{2}})\)", forest)
            self.assertIsNotNone(kotlin, f"{kotlin_name} missing from ForestTheme")
            red, green, blue = (int(group, 16) for group in kotlin.groups())
            expected = (f"public static let {swift_name} = Color(red: 0x{red:02X} / 255, "
                        f"green: 0x{green:02X} / 255, blue: 0x{blue:02X} / 255)")
            self.assertIn(expected, PALETTE, f"{swift_name} drifted from ForestTheme.{kotlin_name}")

    def test_the_password_rules_match_the_other_clients(self):
        store = (SOURCES / "VaultStore.swift").read_text(encoding="utf-8")
        self.assertIn("InputPolicy.minVaultPasswordChars", store)
        policy = (IOS / "Sources/OfflineOTPKit/InputPolicy.swift").read_text(encoding="utf-8")
        self.assertIn("minVaultPasswordChars = 6", policy)
        constants = (ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/Constants.kt"
                     ).read_text(encoding="utf-8")
        self.assertIn("const val AUTH_MIN_PASSWORD_LENGTH = 6", constants)
        self.assertIn("const val AUTH_MIN_PIN_LENGTH = 4", constants)
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

    def test_the_views_do_not_compute_codes_or_touch_files(self):
        """A view asks the store or the format layer; it never reimplements either."""
        for name in ("EntryListView.swift", "UnlockView.swift", "OfflineOTPApp.swift"):
            text = (SOURCES / name).read_text(encoding="utf-8")
            self.assertNotIn("FileManager", text, name)
            self.assertNotIn("PBKDF2", text, name)
            self.assertNotIn("SymmetricKey", text, name)
            self.assertNotIn("TokenCalculator", text, name)

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
        self.assertIn("-destination 'generic/platform=iOS Simulator'", WORKFLOW)
        self.assertIn("CODE_SIGNING_ALLOWED=NO", WORKFLOW)
        self.assertIn("platform=iOS Simulator,id=$UDID", WORKFLOW)
        self.assertIn("OfflineOTP-ios-simulator", WORKFLOW)
        self.assertIn("OfflineOTP-ios-kit-source", WORKFLOW)
        self.assertEqual(2, WORKFLOW.count("actions/upload-artifact@"))
        self.assertIn("SHA256SUMS.txt", WORKFLOW)
        # A simulator build needs no certificate; device signing and TestFlight stay manual.
        for forbidden in ("codesign", "xcodebuild archive", "exportArchive", "notarytool",
                          "secrets.", "contents: write", "gh release", "development-team",
                          "DEVELOPMENT_TEAM: ABC"):
            self.assertNotIn(forbidden, WORKFLOW, f"ios.yml must not use {forbidden}")

    def test_the_plan_records_what_the_shell_does_and_does_not_do(self):
        plan = (ROOT / "docs/ios-client-plan.md").read_text(encoding="utf-8")
        self.assertIn("M1.5", plan)
        self.assertIn("**M1.5** ✅", plan)
        for honest_limit in ("no app target and no SwiftUI yet", "tabs", "Face ID"):
            if honest_limit == "no app target and no SwiftUI yet":
                self.assertNotIn(honest_limit, plan,
                                 "the plan still claims there is no app target")
            else:
                self.assertIn(honest_limit, plan)


if __name__ == "__main__":
    unittest.main()
