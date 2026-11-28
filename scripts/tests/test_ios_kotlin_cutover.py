"""Cutover source guards, not Kotlin/Native compilation or Apple service tests."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "ios/App/Sources"
NATIVE = ROOT / "ui-foundation/src/iosMain/kotlin/com/fairuniversal/offlineotp/ui/platform"


class IosKotlinCutoverTest(unittest.TestCase):
    def test_removed_native_services_are_called_through_kotlin(self):
        for retired in ("CodeFormat.swift", "Clipboard.swift", "QrImageReader.swift", "ForestPalette.swift", "AboutInfo.swift"):
            self.assertFalse((APP / retired).exists(), retired)
        vault = (APP / "ComposeVaultHost.swift").read_text()
        self.assertIn("PortableCodeFormat.shared.grouped(code:", vault)
        self.assertIn("PortableCodeFormat.shared.secondsRemaining(kind:", vault)
        self.assertIn("IosClipboard.shared.copy(code:", vault)
        self.assertIn("IosClipboard.shared.copyUntil(code:", vault)
        root = (APP / "AppRootViewController.swift").read_text()
        self.assertIn("import OfflineOTPPresentation", root)
        self.assertIn("IosQrImageReader.shared.payload(image:", root)

    def test_native_clipboard_uses_shared_bounded_policy_and_os_expiry(self):
        adapter = (NATIVE / "IosClipboard.kt").read_text()
        for required in ("PortableClipboardPolicy.expiry(", "UIPasteboard.generalPasteboard.setItems(",
                         "UIPasteboardOptionExpirationDate", "UIPasteboardOptionLocalOnly to true"):
            self.assertIn(required, adapter)
        # Refusing an expired value must happen before touching the system pasteboard.
        self.assertLess(adapter.index("?: return false"), adapter.index("generalPasteboard.setItems"))

    def test_native_image_adapter_remains_qr_only_and_fail_closed(self):
        adapter = (NATIVE / "IosQrImageReader.kt").read_text()
        self.assertIn("request.symbologies = listOf(VNBarcodeSymbologyQR)", adapter)
        self.assertIn("if (!handler.performRequests(listOf(request), error = null)) return null", adapter)
        self.assertIn("filterIsInstance<VNBarcodeObservation>()", adapter)
        self.assertNotIn("AVCaptureDevice", adapter)

    def test_clipboard_imports_foundation_extension_property(self):
        adapter = (NATIVE / "IosClipboard.kt").read_text()
        self.assertIn("import platform.Foundation.timeIntervalSince1970", adapter)
        stub = (ROOT / "scripts/kotlin-sandbox/stubs-compose/ios/platform/Foundation/NSDate.kt").read_text()
        self.assertIn("val NSDate.timeIntervalSince1970", stub)
        self.assertNotIn("    val timeIntervalSince1970", stub)

    def test_ci_backed_images_render_to_owned_cgimage_for_vision(self):
        adapter = (NATIVE / "IosQrImageReader.kt").read_text()
        self.assertIn("import platform.CoreImage.CIContext", adapter)
        self.assertIn("CIContext().createCGImage(ciImage, fromRect = ciImage.extent) ?: return null", adapter)
        self.assertIn("sourceCgImage ?: renderedCgImage ?: return null", adapter)
        self.assertIn("finally {", adapter)
        self.assertIn("if (renderedCgImage != null) CGImageRelease(renderedCgImage)", adapter)
        self.assertNotIn("VNImageRequestHandler(ciImage", adapter)
        self.assertNotIn(" as ", adapter)
        # Do not let a fictitious overload make this exact metadata error pass again.
        stub = (ROOT / "scripts/kotlin-sandbox/stubs-compose/ios/platform/Vision/Barcode.kt").read_text()
        self.assertNotIn("StubCIImage", stub)

    def test_ci_validates_shared_kotlin_and_the_app_not_retired_package_tests(self):
        workflow = (ROOT / ".github/workflows/ios.yml").read_text()
        self.assertIn(":ui-model:desktopTest", workflow)
        self.assertIn(":ui-foundation:assembleOfflineOTPPresentationReleaseXCFramework", workflow)
        self.assertIn("xcodebuild", workflow)
        self.assertNotIn("swift test", workflow)
        self.assertNotIn("swift build -c release --build-tests", workflow)
        tests = ROOT / "ui-model/src/desktopTest/kotlin/com/fairuniversal/offlineotp/ui/vault/PortableCodeFormatTest.kt"
        self.assertIn("clipboardExpiryIsBoundedByBothTimeoutAndTokenExpiry", tests.read_text())
        self.assertFalse((ROOT / "ios/App/Tests/CodeFormatTests.swift").exists())
