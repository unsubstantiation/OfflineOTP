import UIKit
import UniformTypeIdentifiers

/// Putting a code on the pasteboard, with an expiry.
///
/// The other clients clear a copied secret after a short delay (`CLIPBOARD_CLEAR_TIMEOUT` on
/// Android, the same idea on the desktop); iOS has no app to clear it later, because the app may not
/// be running, so the pasteboard itself is asked to forget the item at a fixed time. That is the
/// platform's supported way to do the same job.
public enum Clipboard {
    /// How long a copied code stays on the pasteboard.
    public static let expirySeconds: TimeInterval = 30

    @discardableResult
    public static func copy(_ code: String, now: Date = Date()) -> Bool {
        let expiry = now.addingTimeInterval(expirySeconds)
        UIPasteboard.general.setItems(
            [[UTType.utf8PlainText.identifier: code]],
            options: [.expirationDate: expiry])
        return true
    }
}
