import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI
import OfflineOTPKit

/// An entry as a QR code — the counterpart of Android's **Show QR code** and the desktop's
/// **QR code** dialog.
///
/// The payload is the entry's `otpauth://` link, produced by `OtpUri.uri(for:)` in `OfflineOTPKit`,
/// so the code a phone scans here is byte-identical to the link the other clients show. Only OTP
/// entries have such a link: a note, an SSH key, a password archive or an email account says so
/// instead. The image is generated in memory and never written to disk.
struct QrCodeView: View {
    let title: String
    let payload: String

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                if let image = QrRenderer.image(for: payload) {
                    // The code is drawn on white with a quiet zone, because a dark theme behind it
                    // would leave a scanner without the contrast it expects.
                    Image(decorative: image, scale: 1)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .padding(16)
                        .background(Color.white)
                        .clipShape(RoundedRectangle(cornerRadius: 12))
                        .accessibilityLabel("QR code for \(title)")
                } else {
                    Text("This entry cannot be shown as a QR code.")
                        .font(.headline)
                        .foregroundColor(ForestPalette.warning)
                        .multilineTextAlignment(.center)
                }
                Text(title)
                    .font(.headline)
                    .foregroundColor(ForestPalette.textPrimary)
                    .multilineTextAlignment(.center)
                Text("Scan this with the other device. It carries the account and its secret, so do "
                     + "not photograph it or show it to a camera you do not control.")
                    .font(.footnote)
                    .foregroundColor(ForestPalette.textSecondary)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(ForestPalette.background)
            .navigationTitle("QR code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .preferredColorScheme(.dark)
    }
}

/// CoreImage's QR generator, on the white background and with the quiet zone a scanner expects.
///
/// `CIQRCodeGenerator` draws black modules over a transparent background, so the image is composited
/// onto white here rather than relying on whatever the view puts behind it — a dark theme behind the
/// code would leave some scanners unable to find the finder patterns.
enum QrRenderer {
    /// Pixels per module. Large enough to scan from another phone's screen without smoothing.
    static let scale: CGFloat = 12

    static func image(for payload: String) -> CGImage? {
        guard !payload.isEmpty else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(payload.utf8)
        // Medium correction keeps the code readable on a screen while leaving room for a label.
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        // Four modules of quiet zone, the QR specification's minimum, scaled with the code.
        let margin = max(output.extent.width, output.extent.height) * 0.08
        let white = CIImage(color: .white)
            .cropped(to: output.extent.insetBy(dx: -margin, dy: -margin))
        let composited = output.composited(over: white)
        let scaled = composited.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        return CIContext().createCGImage(scaled, from: scaled.extent)
    }
}
