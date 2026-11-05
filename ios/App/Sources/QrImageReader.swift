import Foundation
import UIKit
import Vision

/// Reads the text out of a QR code in an image the person picked.
///
/// The platform's own detector (Vision) does the scanning, so there is no third-party dependency and
/// no camera permission: the file is read from the picker's sandboxed URL. The text is then routed by
/// `QrPayload` in `OfflineOTPKit`, which is the same classification the desktop client applies to an
/// image file — a Google Authenticator export is recognised and refused by name, a single
/// `otpauth://` link becomes an entry, and anything else says so.
enum QrImageReader {
    /// The first QR payload in the image, or nil when there is none.
    static func payload(in url: URL) -> String? {
        guard let image = UIImage(contentsOfFile: url.path) else { return nil }
        return payload(in: image)
    }

    static func payload(in image: UIImage) -> String? {
        guard let cgImage = image.cgImage else { return nil }
        let request = VNDetectBarcodesRequest()
        // QR only: the same symbology the phone's own scanner accepts for an account, so a
        // QR image that imports here imports there.
        request.symbologies = [.qr]
        let handler = VNImageRequestHandler(cgImage: cgImage, options: [:])
        do {
            try handler.perform([request])
        } catch {
            return nil
        }
        for barcode in request.results ?? [] {
            if let value = barcode.payloadStringValue, !value.isEmpty { return value }
        }
        return nil
    }
}
