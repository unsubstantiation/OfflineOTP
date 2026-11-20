import UIKit
import VisionKit
import Vision

/// Live QR-only camera reader. VisionKit supplies the camera lifecycle and permission UI; the
/// scanned payload goes unchanged to the same offline OTP/migration parser as image imports.
/// This is a native capability adapter, not a UI surface the shared Compose renderer draws.
final class QrCaptureViewController: UIViewController {
    private let onPayload: (String) -> Void
    private let onUnavailable: () -> Void
    private var scanner: DataScannerViewController?
    private var delivered = false

    init(onPayload: @escaping (String) -> Void, onUnavailable: @escaping () -> Void) {
        self.onPayload = onPayload
        self.onUnavailable = onUnavailable
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("use init(onPayload:onUnavailable:)") }

    override func loadView() {
        guard DataScannerViewController.isSupported, DataScannerViewController.isAvailable else {
            view = UIView(frame: UIScreen.main.bounds)
            view.backgroundColor = ForestPalette.background
            DispatchQueue.main.async { self.onUnavailable() }
            return
        }
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .accurate,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true)
        scanner.delegate = self
        self.scanner = scanner
        view = scanner.view
        addChild(scanner)
        scanner.didMove(toParent: self)
        do {
            try scanner.startScanning()
        } catch {
            DispatchQueue.main.async { self.onUnavailable() }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        scanner?.stopScanning()
    }
}

extension QrCaptureViewController: DataScannerViewControllerDelegate {
    func dataScanner(_ dataScanner: DataScannerViewController,
                     didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
        guard !delivered else { return }
        for item in addedItems {
            if case .barcode(let barcode) = item, let payload = barcode.payloadStringValue,
               !payload.isEmpty {
                delivered = true
                dataScanner.stopScanning()
                dismiss(animated: true)
                onPayload(payload)
                return
            }
        }
    }
}
