"""Prove the shipped iOS bundle is complete instead of asking anyone to trust its size.

The unsigned `.ipa` is ~2.5 MB, which looks small next to the other clients until you notice what
is *not* in it: no JVM, no bundled fonts or images, no embedded Swift runtime (iOS carries it), one
arm64 slice, Release with dead stripping. Two things could nevertheless make it genuinely
incomplete, and neither used to fail the build:

* the app shipped **no icon at all** — there was no asset catalog, so iOS drew a blank placeholder;
* `ios/Package.swift` compiles the OpenPGP port out when the Rust XCFramework is absent, so an app
  without the cryptography still builds green.

These guards drive `scripts/ios/report-ipa-contents.py` against synthetic bundles — a complete one
and each incomplete shape — so the CI check is known to catch what it claims to catch, plus the
icon generator's contract with the asset catalog and the workflow.
"""
import importlib.util
import json
import plistlib
import struct
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
REPORT_SCRIPT = ROOT / "scripts/ios/report-ipa-contents.py"
ICON_SCRIPT = ROOT / "scripts/ios/generate-app-icon.py"
WORKFLOW = (ROOT / ".github/workflows/ios.yml").read_text(encoding="utf-8")
PROJECT = yaml.safe_load((ROOT / "ios/project.yml").read_text(encoding="utf-8"))
APP_TARGET = PROJECT["targets"]["OfflineOTP"]


def load(path: Path, name: str):
    """Import a script whose filename has hyphens in it."""
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


REPORT = load(REPORT_SCRIPT, "report_ipa_contents")
ICON = load(ICON_SCRIPT, "generate_app_icon")

ARM64 = 0x0100000C
X86_64 = 0x01000007
RUST_PATH = b"/Users/runner/.cargo/registry/src/index.crates.io/pgp-0.21.0/src/lib.rs"

LINK_MAP = """# Path: /build/OfflineOTP.app/OfflineOTP
# Arch: arm64
# Object files:
[  0] linker synthesized
[  1] /build/EntryListView.o
[  2] /build/OfflineOTPKit.o
[  3] /ios/Frameworks/offlineotp_openpgp.xcframework/ios-arm64/libofflineotp_openpgp.a(rpgp.o)
[  4] /ios/Frameworks/offlineotp_openpgp.xcframework/ios-arm64/libofflineotp_openpgp.a(uniffi.o)
# Sections:
# Address	Size    	Segment	Section
0x100000000	0x00001000	__TEXT	__text
# Symbols:
# Address	Size    	File  Name
0x100000000	0x00000100	[  1] _main
0x100000100	0x00000200	[  2] _kit
0x100000300	0x00000400	[  3] _pgp_encrypt
0x100000700	0x00000800	[  4] _uniffi_checksum
"""


def thin_macho(cpu: int = ARM64, payload: bytes = RUST_PATH) -> bytes:
    """A 64-bit little-endian Mach-O header followed by bytes standing in for __TEXT."""
    return struct.pack("<IIIIIIII", 0xFEEDFACF, cpu, 0, 2, 0, 0, 0, 0) + payload


def fat_macho(cpus=(ARM64, X86_64)) -> bytes:
    header = struct.pack(">II", 0xCAFEBABE, len(cpus))
    for index, cpu in enumerate(cpus):
        header += struct.pack(">IIIII", cpu, 0, 4096 * (index + 1), 32, 12)
    return header


def make_ipa(directory: Path, *, icon: bool = True, rust: bool = True,
             binary: bytes | None = None, stray: bool = False,
             platform: str = "iphoneos", icon_form: str = "car") -> Path:
    """Write a bundle shaped like the one CI packs, with the parts under test toggled."""
    plist = {
        "CFBundleExecutable": "OfflineOTP",
        "CFBundleIdentifier": "com.fairuniversal.offlineotp",
        "CFBundleShortVersionString": "0.1",
        "CFBundleVersion": "1",
        "DTPlatformName": platform,
        "MinimumOSVersion": "17.0",
    }
    if icon:
        plist["CFBundleIconName"] = "AppIcon"
    if binary is None:
        binary = thin_macho(payload=RUST_PATH if rust else b"swift_core stdlib dyld")
    ipa = directory / "OfflineOTP-ios-unsigned.ipa"
    with zipfile.ZipFile(ipa, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("Payload/OfflineOTP.app/OfflineOTP", binary)
        archive.writestr("Payload/OfflineOTP.app/Info.plist", plistlib.dumps(plist))
        if icon:
            if icon_form == "car":
                archive.writestr("Payload/OfflineOTP.app/Assets.car", b"CAR\x00" * 4096)
            else:
                archive.writestr("Payload/OfflineOTP.app/AppIcon60x60@2x.png", b"\x89PNG" * 512)
                archive.writestr("Payload/OfflineOTP.app/AppIcon76x76@2x~ipad.png", b"\x89PNG" * 512)
        archive.writestr("Payload/OfflineOTP.app/PkgInfo", "APPL????")
        if stray:
            archive.writestr("SHA256SUMS.txt", "0" * 64 + "  OfflineOTP-ios-unsigned.ipa\n")
    return ipa


def write_map(directory: Path, text: str = LINK_MAP) -> Path:
    path = directory / "OfflineOTP-LinkMap-normal-arm64.txt"
    path.write_text(text, encoding="utf-8")
    return path


class IpaContentsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)

    def test_a_complete_bundle_passes_and_says_where_the_weight_is(self):
        ipa = make_ipa(self.directory)
        report, problems = REPORT.inspect(ipa, write_map(self.directory))
        self.assertEqual([], problems)
        text = "\n".join(report)
        self.assertIn("arm64", text)
        self.assertIn("app icon: Assets.car", text)
        self.assertIn("OpenPGP: linked", text)
        self.assertIn("libofflineotp_openpgp.a", text)
        self.assertIn("com.fairuniversal.offlineotp", text)
        # 0x400 + 0x800 of 0x100+0x200+0x400+0x800 = 3072 of 3840 bytes.
        self.assertIn("3.0 KB in 2 objects (80% of the linked image)", text)

    def test_an_app_without_an_icon_is_refused(self):
        report, problems = REPORT.inspect(make_ipa(self.directory, icon=False),
                                          write_map(self.directory))
        self.assertTrue(any("no compiled app icon" in p for p in problems), problems)
        self.assertTrue(any("generate-app-icon.py" in p for p in problems), problems)

    def test_copied_icon_pngs_count_as_an_icon_too(self):
        # actool does not always leave an Assets.car for an icon-only catalog; the Info.plist key
        # plus the copied AppIcon files are the same proof.
        report, problems = REPORT.inspect(
            make_ipa(self.directory, icon_form="png"), write_map(self.directory))
        self.assertEqual([], problems)
        self.assertIn("app icon: AppIcon", "\n".join(report))

    def test_an_app_without_the_rust_backend_is_refused(self):
        # The silent failure: Package.swift drops OFFLINEOTP_OPENPGP_NATIVE and everything builds.
        ipa = make_ipa(self.directory, rust=False)
        empty_map = write_map(self.directory, LINK_MAP.replace("offlineotp_openpgp", "someotherlib"))
        report, problems = REPORT.inspect(ipa, empty_map)
        self.assertTrue(any("Rust OpenPGP backend is not in the app" in p for p in problems),
                        problems)

    def test_rust_panic_paths_are_accepted_when_no_link_map_exists(self):
        report, problems = REPORT.inspect(make_ipa(self.directory), None)
        self.assertEqual([], problems)
        self.assertIn("Rust source paths in the binary", "\n".join(report))

    def test_a_simulator_or_intel_binary_is_refused(self):
        for binary in (thin_macho(cpu=X86_64), fat_macho()):
            with self.subTest(binary=binary[:4]):
                report, problems = REPORT.inspect(make_ipa(self.directory, binary=binary),
                                                  write_map(self.directory))
                self.assertTrue(any("not a thin arm64 Mach-O" in p for p in problems), problems)

    def test_a_simulator_platform_is_refused(self):
        report, problems = REPORT.inspect(
            make_ipa(self.directory, platform="iphonesimulator"), write_map(self.directory))
        self.assertTrue(any("DTPlatformName" in p for p in problems), problems)

    def test_files_beside_the_bundle_are_refused(self):
        # The .ipa is the only deliverable; nothing else may ride along inside it either.
        report, problems = REPORT.inspect(make_ipa(self.directory, stray=True),
                                          write_map(self.directory))
        self.assertTrue(any("outside the app bundle" in p for p in problems), problems)

    def test_the_largest_link_map_is_the_one_read(self):
        write_map(self.directory, "# Object files:\n[ 1] tiny\n")
        bigger = self.directory / "OfflineOTP-LinkMap-normal-arm64-2.txt"
        bigger.write_text(LINK_MAP, encoding="utf-8")
        self.assertEqual(bigger, REPORT.find_link_map(self.directory))
        self.assertIsNone(REPORT.find_link_map(self.directory / "missing"))
        self.assertIsNone(REPORT.find_link_map(None))

    def test_the_link_map_parser_sums_sizes_per_object(self):
        total, openpgp, count, archives = REPORT.parse_link_map(LINK_MAP)
        self.assertEqual(0x100 + 0x200 + 0x400 + 0x800, total)
        self.assertEqual(0x400 + 0x800, openpgp)
        self.assertEqual(2, count)
        self.assertEqual(1, len(archives))
        self.assertTrue(archives[0].endswith("libofflineotp_openpgp.a"))

    def test_the_cli_publishes_one_annotation_and_fails_on_an_incomplete_bundle(self):
        ipa = make_ipa(self.directory)
        write_map(self.directory)
        good = subprocess.run([sys.executable, str(REPORT_SCRIPT), "--ipa", str(ipa),
                               "--link-map-dir", str(self.directory), "--annotate"],
                              capture_output=True, text=True)
        self.assertEqual(0, good.returncode, good.stdout + good.stderr)
        notices = [line for line in good.stdout.splitlines() if line.startswith("::notice")]
        self.assertEqual(1, len(notices), good.stdout)
        self.assertIn("%0A", notices[0])
        self.assertNotIn("::error", good.stdout)

        bad_directory = self.directory / "bad"
        bad_directory.mkdir()
        bad = subprocess.run([sys.executable, str(REPORT_SCRIPT), "--ipa",
                              str(make_ipa(bad_directory, icon=False)), "--annotate"],
                             capture_output=True, text=True)
        self.assertEqual(1, bad.returncode)
        self.assertIn("::error title=iOS bundle::", bad.stdout)

    def test_a_missing_archive_is_an_error_not_a_traceback(self):
        result = subprocess.run([sys.executable, str(REPORT_SCRIPT), "--ipa",
                                 str(self.directory / "absent.ipa"), "--annotate"],
                                capture_output=True, text=True)
        self.assertEqual(1, result.returncode)
        self.assertIn("does not exist", result.stderr)
        self.assertNotIn("Traceback", result.stderr)


class AppIconTest(unittest.TestCase):
    def test_the_shared_artwork_is_large_enough_and_opaque(self):
        data = ICON.embedded_png(ICON.SOURCE_SVG.read_text(encoding="utf-8"))
        width, height, colour = ICON.png_header(data)
        self.assertGreaterEqual(width, 1024)
        self.assertGreaterEqual(height, 1024)
        self.assertNotIn(colour, (4, 6), "the iOS icon must not carry alpha")

    def test_the_catalog_asks_for_the_single_size_the_generator_writes(self):
        catalog = ROOT / "ios/App/Resources/Assets.xcassets"
        contents = json.loads((catalog / "AppIcon.appiconset/Contents.json").read_text())
        self.assertEqual(1, len(contents["images"]))
        image = contents["images"][0]
        self.assertEqual("1024x1024", image["size"])
        self.assertEqual("universal", image["idiom"])
        self.assertEqual("ios", image["platform"])
        self.assertEqual(ICON.ICON_FILENAME, image["filename"])
        self.assertEqual(catalog / "AppIcon.appiconset" / image["filename"], ICON.ICON_PATH)
        # The catalog itself is committed; the PNG is generated like the macOS .icns.
        self.assertTrue((catalog / "Contents.json").is_file())
        ignored = (ROOT / ".gitignore").read_text(encoding="utf-8")
        self.assertIn("ios/App/Resources/Assets.xcassets/AppIcon.appiconset/*.png", ignored)

    def test_the_app_target_compiles_the_catalog_and_names_the_icon(self):
        sources = [entry["path"] for entry in APP_TARGET["sources"]]
        self.assertIn("App/Resources", sources)
        self.assertEqual("AppIcon",
                         APP_TARGET["settings"]["base"]["ASSETCATALOG_COMPILER_APPICON_NAME"])

    def test_the_generator_refuses_artwork_it_cannot_ship(self):
        with self.assertRaises(SystemExit):
            ICON.png_header(b"not a png at all")
        with self.assertRaises(SystemExit):
            ICON.embedded_png("<svg><path d='M0 0'/></svg>")


class WorkflowWiringTest(unittest.TestCase):
    def test_the_icon_is_generated_before_the_project_is(self):
        steps = yaml.safe_load(WORKFLOW)["jobs"]["build"]["steps"]
        names = [step.get("name", "") for step in steps]
        self.assertIn("Generate the app icon", names)
        self.assertLess(names.index("Generate the app icon"),
                        names.index("Generate the Xcode project"))
        icon_step = steps[names.index("Generate the app icon")]
        self.assertIn("scripts/ios/generate-app-icon.py", icon_step["run"])

    def test_the_device_build_emits_a_link_map_the_report_can_read(self):
        self.assertIn("LD_GENERATE_MAP_FILE=YES", WORKFLOW)
        self.assertIn("scripts/ios/report-ipa-contents.py", WORKFLOW)
        self.assertIn("--link-map-dir ios/build/DerivedData", WORKFLOW)
        self.assertIn("--annotate", WORKFLOW)
        # Still exactly one published file: the report is an annotation, not an artifact.
        self.assertEqual(1, WORKFLOW.count("actions/upload-artifact@"))
        self.assertIn("path: artifacts/OfflineOTP-ios-unsigned.ipa", WORKFLOW)

    def test_the_report_runs_on_the_archive_that_gets_uploaded(self):
        steps = yaml.safe_load(WORKFLOW)["jobs"]["build"]["steps"]
        packaging = next(step for step in steps
                         if step.get("name") == "Package the unsigned device IPA")
        run = packaging["run"]
        self.assertLess(run.index("ditto -c -k"), run.index("report-ipa-contents.py"),
                        "the report must read the finished .ipa")
        self.assertIn("--ipa artifacts/OfflineOTP-ios-unsigned.ipa", run)


if __name__ == "__main__":
    unittest.main()
