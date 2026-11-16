"""All app icons come from the Android artwork, not a platform default."""
import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("mac_icon", ROOT / "scripts/branding/generate-macos-icon.py")
MAC = importlib.util.module_from_spec(spec)
spec.loader.exec_module(MAC)


class SharedAppIconTest(unittest.TestCase):
    def test_mac_generator_emits_every_retina_size_and_propagates_tool_failure(self):
        calls = []
        def run(args, check):
            self.assertTrue(check)
            calls.append(args)
            if args[0] == "sips":
                self.assertTrue(Path(args[4]).read_bytes().startswith(b"\x89PNG"))
            else:
                self.assertEqual("iconutil", args[0])
                Path(args[-1]).write_bytes(b"test-icon")
        with tempfile.TemporaryDirectory() as directory:
            destination = Path(directory) / "icons/test.icns"
            with patch.object(MAC.subprocess, "run", side_effect=run):
                MAC.generate(destination)
            self.assertTrue(destination.is_file())
            self.assertEqual(11, len(calls))
            self.assertEqual(10, len({Path(call[-1]).name for call in calls[:-1]}))
            self.assertEqual("1024", calls[-2][2])
            with patch.object(MAC.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "sips")):
                with self.assertRaises(subprocess.CalledProcessError):
                    MAC.generate(destination)

    def test_every_platform_uses_android_branding(self):
        branding = (ROOT / "scripts/branding/generate_icons.py").read_text()
        for path in ("design/forest-lock-no-circle.png", "res/mipmap-{density}/{name}.png",
                     "desktop/src/main/resources/offlineotp.png", "desktop/packaging/offlineotp.ico"):
            self.assertIn(path, branding)
        ios = (ROOT / "scripts/ios/generate-app-icon.py").read_text()
        self.assertIn('SOURCE_SVG = ROOT / "assets/logo_legacy.svg"', ios)
        self.assertEqual(ROOT / "assets/logo_legacy.svg", MAC.SOURCE)
        host = (ROOT / "desktop-compose/src/main/kotlin/com/fairuniversal/offlineotp/desktop/compose/ComposeMain.kt").read_text()
        self.assertIn('icon = painterResource("offlineotp.png")', host)
        build = (ROOT / "desktop-compose/build.gradle").read_text()
        self.assertIn("dependsOn 'installDist', 'generateMacIcon'", build)
        self.assertIn("(osName.contains('mac') ? macIcon : null)", build)
        self.assertIn("if (icon != null && !icon.isFile()) throw new GradleException", build)
