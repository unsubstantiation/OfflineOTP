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

    def test_desktop_uses_only_allowed_packages(self):
        """Domain logic comes from :core and the UI from java.desktop; third-party imports are
        restricted to the libraries explicitly declared in desktop/build.gradle (allowlist)."""
        allowed = (
            "kotlin", "java", "javax",
            "org.shadowice.flocke.andotp",
            # Embedded OpenPGP engine (pgpainless-core plus its BouncyCastle transitives).
            "org.pgpainless", "org.bouncycastle",
            # PC/SC security-key discovery via the yubikit desktop module (smartcardio only).
            "com.yubico",
            # QR image decoding through zxing-core (no camera/screen-capture integration).
            "com.google.zxing",
        )
        offenders = []
        for path in DESKTOP_MAIN.rglob("*.kt"):
            for idx, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                stripped = line.strip()
                if not stripped.startswith("import "):
                    continue
                imported = stripped[len("import "):].split()[0].strip(".")
                if imported.startswith(allowed):
                    continue
                offenders.append(f"{path.relative_to(ROOT)}:{idx} {imported}")
        self.assertEqual([], offenders, "desktop imported a package outside the declared allowlist")

    def test_core_never_imports_a_desktop_library(self):
        """Desktop-only libraries must stay behind core port interfaces."""
        offenders = []
        for path in (ROOT / "core/src/main/kotlin").rglob("*.kt"):
            for idx, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
                stripped = line.strip()
                if stripped.startswith((
                    "import org.pgpainless", "import org.bouncycastle",
                    "import com.yubico.yubikit.desktop",
                )):
                    offenders.append(f"{path.relative_to(ROOT)}:{idx} {stripped}")
        self.assertEqual([], offenders)

    def test_hardware_ports_are_implemented_without_hid(self):
        """Key wrapping and hardware-unlock storage have desktop adapters; PC/SC excludes HID."""
        rsa = (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/storage/"
               "RsaKeyWrapPort.kt").read_text(encoding="utf-8")
        self.assertIn("class RsaKeyWrapPort", rsa)
        self.assertIn(": KeyWrapPort", rsa)
        # Same RSA/ECB/PKCS1Padding transformation as the Android KeyStore wrapper, via Constants.
        self.assertIn("Constants.ALGORITHM_ASYMMETRIC", rsa)

        hw_store = (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/storage/"
                    "DesktopHardwareUnlockStore.kt").read_text(encoding="utf-8")
        self.assertIn(": HardwareUnlockStoragePort", hw_store)
        self.assertIn("HardwareUnlockEnvelope.request", hw_store)

        discovery = (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/hardware/"
                     "PcscHardwareDiscovery.kt").read_text(encoding="utf-8")
        self.assertIn("PcscManager()", discovery)
        # javax.smartcardio is not aggregated by the java.se module, so the Kotlin JVM compiler
        # cannot resolve it; no desktop source may import it, and the javax-free yubikit default
        # constructor has to be the entry point.
        for source in DESKTOP_MAIN.rglob("*.kt"):
            for line in source.read_text(encoding="utf-8").splitlines():
                self.assertFalse(line.strip().startswith("import ") and "smartcardio" in line,
                                 f"{source} must not import javax.smartcardio")
        # No raw-HID library may be referenced from source; the PC/SC path uses smartcardio only.
        self.assertFalse(
            any(line.strip().startswith("import ") and "hid4java" in line
                for line in discovery.splitlines()))

        build = (ROOT / "desktop/build.gradle").read_text(encoding="utf-8")
        self.assertIn("com.yubico.yubikit:desktop", build)
        self.assertIn("org.hid4java", build)  # the exclusion must be declared explicitly
        gate = (SANDBOX / "compile-desktop.sh").read_text(encoding="utf-8")
        self.assertIn("stubs/com/yubico/yubikit/desktop", gate)
        # yubikit 3.2.1 places the PC/SC classes in the desktop.pcsc sub-package.
        self.assertTrue(
            (SANDBOX / "stubs/com/yubico/yubikit/desktop/pcsc/PcscManager.kt").is_file())
        self.assertFalse(
            (SANDBOX / "stubs/com/yubico/yubikit/desktop/PcscManager.kt").exists())
        # The real PcscManager is not Closeable, so neither the adapter nor the stub may call close().
        stub = (SANDBOX / "stubs/com/yubico/yubikit/desktop/pcsc/PcscManager.kt").read_text(
            encoding="utf-8")
        self.assertNotIn("AutoCloseable", stub)
        self.assertNotIn("manager.close", discovery)

    def test_qr_decoder_uses_zxing_core_without_android_integration(self):
        decoder = (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/qr/"
                   "ZxingQrDecoder.kt").read_text(encoding="utf-8")
        self.assertIn("class ZxingQrDecoder : QrImageDecoder", decoder)
        # Bounded ImageReader flow replaces the unbounded ImageIO.read so header dimensions
        # are validated before any pixel allocation (inJustDecodeBounds parity).
        self.assertIn("ImageIO.createImageInputStream", decoder)
        self.assertIn("ImageIO.getImageReaders", decoder)
        self.assertIn("InputPolicy.validImage", decoder)
        self.assertIn("TRY_HARDER", decoder)
        self.assertNotIn("integration.android", decoder)
        build = (ROOT / "desktop/build.gradle").read_text(encoding="utf-8")
        self.assertIn("com.google.zxing:core", build)
        # The desktop gate reuses the pure-JVM zxing stubs but excludes the android integration.
        gate = (SANDBOX / "compile-desktop.sh").read_text(encoding="utf-8")
        self.assertIn('not -path "*/integration/*"', gate)
        # The portable router lives in core and never references zxing.
        core_router = (ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp/"
                       "Utilities/QrImport.kt").read_text(encoding="utf-8")
        self.assertNotIn("com.google.zxing", core_router)

    def test_openpgp_adapter_implements_the_core_port_and_is_declared(self):
        adapter = (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/openpgp/"
                   "PgpainlessOpenPgpPort.kt").read_text(encoding="utf-8")
        self.assertIn("class PgpainlessOpenPgpPort : OpenPgpPort", adapter)
        # PGPainless 2.x: instance builders replace the deprecated static encrypt/decrypt entry
        # points, and PasswordProtector no longer exists.
        self.assertIn("PGPainless.getInstance()", adapter)
        self.assertIn("generateMessage()", adapter)
        self.assertIn("processMessage()", adapter)
        self.assertIn("org.pgpainless.key.parsing.KeyRingReader", adapter)
        self.assertIn("SecretKeyRingProtector.unlockAnyKeyWith", adapter)
        self.assertIn("Passphrase.fromPassword", adapter)
        # The removed/deprecated symbols must not be referenced by imports or calls (comments may
        # still explain the migration away from them).
        self.assertNotIn("import org.pgpainless.key.protection.PasswordProtector", adapter)
        self.assertNotIn("PasswordProtector.", adapter)
        self.assertNotIn("PGPainless.encryptAndOrSign", adapter)
        self.assertNotIn("PGPainless.decryptAndOrVerify", adapter)
        self.assertNotIn("org.pgpainless.key.reading", adapter)
        self.assertTrue(
            (SANDBOX / "stubs/org/pgpainless/key/parsing/KeyRingReader.kt").is_file())
        self.assertFalse(
            (SANDBOX / "stubs/org/pgpainless/key/reading").exists())
        build = (ROOT / "desktop/build.gradle").read_text(encoding="utf-8")
        self.assertIn("org.pgpainless:pgpainless-core", build)
        # The sandbox gate compiles the adapter against narrow stubs, never the real jar.
        gate = (SANDBOX / "compile-desktop.sh").read_text(encoding="utf-8")
        self.assertIn("stubs/org/pgpainless", gate)
        self.assertIn("stubs/org/bouncycastle", gate)
        for stub in (
            SANDBOX / "stubs/org/pgpainless/PGPainless.kt",
            SANDBOX / "stubs/org/bouncycastle/openpgp/PGPKeyRings.kt",
        ):
            self.assertTrue(stub.is_file(), f"missing desktop compile stub: {stub.name}")

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

    def test_storage_adapters_implement_the_core_ports(self):
        """The desktop file and settings adapters are swappable implementations of core ports."""
        nio = (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/storage/"
               "NioAtomicFileStore.kt").read_text(encoding="utf-8")
        self.assertIn("class NioAtomicFileStore", nio)
        self.assertIn(": AtomicByteStore", nio)
        # The desktop adapter must reproduce the Android AtomicFile durability steps.
        self.assertIn("ATOMIC_MOVE", nio)
        self.assertIn("fd.sync()", nio)

        settings = (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/storage/"
                    "DesktopSettings.kt").read_text(encoding="utf-8")
        self.assertIn("class DesktopSettings", settings)
        self.assertIn(": KeyValueSettings", settings)

        factory = (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/storage/"
                   "DesktopStorage.kt").read_text(encoding="utf-8")
        self.assertIn("PasswordVault(", factory)
        # The same on-disk filenames the Android vault uses.
        self.assertIn("Constants.FILENAME_DATABASE", factory)
        self.assertIn("Constants.FILENAME_DATABASE_BACKUP", factory)

    def test_headless_packages_stay_swing_free(self):
        """All domain logic must run headless; only the ui package touches Swing/heavyweight AWT."""
        # java.awt.image types (e.g. BufferedImage) are headless-safe under java.awt.headless=true;
        # Swing and AWT peer/top-level components would create heavyweight peers and are forbidden.
        forbidden = ("javax.swing", "java.awt.peer", "java.awt.Frame", "java.awt.Dialog",
                     "java.awt.Window", "java.awt.Panel", "java.awt.Canvas")
        for package in ("storage", "control", "hardware", "qr", "openpgp"):
            for path in (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop" / package).rglob("*.kt"):
                text = path.read_text(encoding="utf-8")
                for token in forbidden:
                    self.assertNotIn(token, text, f"{path.name} must stay headless ({token})")

    def test_hardware_stack_is_wired_into_the_swing_shell(self):
        """The previously dead hardware/key-wrap stack is reachable from production UI."""
        control = DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/control"
        manager = (control / "HardwareKeyManager.kt").read_text(encoding="utf-8")
        self.assertIn("class HardwareKeyManager", manager)
        # Headless logic must not drag Swing into the control package.
        self.assertNotIn("javax.swing", manager)
        self.assertIn("HardwareOathClient", manager)
        self.assertIn("HardwareFidoClient", manager)
        self.assertIn("HardwareUnlockEnvelope", manager)
        # The PIN buffer handed in for one operation is wiped on every exit path.
        self.assertIn("pin?.fill(' ')", manager)

        window = (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/ui/MainWindow.kt")\
            .read_text(encoding="utf-8")
        self.assertIn('JMenu("Hardware")', window)
        self.assertIn("HardwareKeyDialog.show", window)
        self.assertIn("HardwareKeyDialog.requestUnlock", window)
        self.assertIn("controller.unlockWithKey", window)

        dialog = (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/ui/"
                  "HardwareKeyDialog.kt").read_text(encoding="utf-8")
        self.assertIn("SwingWorker", dialog)
        self.assertIn("CancelToken", dialog)
        # The PC/SC discovery and envelope store are assembled in the entry point, not tests only.
        main = (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/Main.kt").read_text(
            encoding="utf-8")
        self.assertIn("PcscHardwareDiscovery()", main)
        self.assertIn("DesktopHardwareUnlockStore(", main)
        self.assertIn("HardwareKeyManager(", main)

    def test_token_copy_logic_is_headless_with_clipboard_only_in_the_swing_shell(self):
        editor = (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/control"
                  / "EntryEditor.kt").read_text(encoding="utf-8")
        self.assertIn("fun copyToken(entry: Entry)", editor)
        self.assertIn("fun advanceHotp(entry: Entry): Boolean", editor)
        self.assertIn("TokenCopyResult", editor)
        # Usage metadata mirrors the Android copy path; clipboard/AWT must not leak into control.
        self.assertIn("setLastUsed", editor)
        self.assertIn("setUsedFrequency", editor)
        self.assertNotIn("javax.swing", editor)
        self.assertNotIn("java.awt", editor)

        entry = (ROOT / "core/src/main/kotlin/org/shadowice/flocke/andotp/Database"
                 / "Entry.kt").read_text(encoding="utf-8")
        self.assertIn("fun hasCopyableToken()", entry)
        self.assertIn("MOTP_NO_PIN_CODE", entry)

        window = (DESKTOP_MAIN / "org/shadowice/flocke/andotp/desktop/ui"
                  / "MainWindow.kt").read_text(encoding="utf-8")
        self.assertIn('JButton("Copy token / note / key")', window)
        self.assertIn("copySelectedToken()", window)
        self.assertIn("Advance HOTP counter", window)
        self.assertIn("StringSelection", window)
        # Double-click and the row context menu both reach the same action.
        self.assertIn("mouseClicked", window)
        self.assertIn("JPopupMenu", window)

    def test_golden_interop_fixtures_are_wired_into_the_gate(self):
        resources = ROOT / "desktop/src/test/resources/golden"
        for name in ("secrets.dat", "vault.properties", "otp_accounts.json.aes",
                     "expected-vault.json", "README.md"):
            self.assertTrue((resources / name).is_file(), f"missing golden fixture: {name}")
        # The no-Gradle gate must mirror Gradle's test-resources classpath.
        gate = (SANDBOX / "compile-desktop.sh").read_text(encoding="utf-8")
        self.assertIn("desktop/src/test/resources", gate)


if __name__ == "__main__":
    unittest.main()
