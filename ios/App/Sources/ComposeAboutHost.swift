import SwiftUI
import UIKit
import OfflineOTPPresentation

/// Native link/version/lifecycle adapter. About layout and content are shared Compose code.
struct ComposeAboutHost: UIViewControllerRepresentable {
    let onDismiss: () -> Void

    final class Coordinator: NSObject, AboutActions {
        let presentation = AboutPresentation()
        private let onDismiss: () -> Void
        private var active = true

        init(onDismiss: @escaping () -> Void) { self.onDismiss = onDismiss }

        func openLink(url: String) -> Bool {
            guard active, let url = URL(string: url),
                  ["https", "http"].contains(url.scheme?.lowercased() ?? ""),
                  UIApplication.shared.canOpenURL(url) else { return false }
            UIApplication.shared.open(url)
            return true
        }

        func dismiss() {
            guard active else { return }
            close()
            onDismiss()
        }

        func close() { active = false; presentation.close() }
    }

    func makeCoordinator() -> Coordinator { Coordinator(onDismiss: onDismiss) }

    func makeUIViewController(context: Context) -> UIViewController {
        let version = Bundle.main.object(forInfoDictionaryKey: "OfflineOTPBuildVersion") as? String
            ?? "Build version unavailable"
        return context.coordinator.presentation.viewController(version: version, actions: context.coordinator)
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Coordinator) {
        coordinator.close()
    }
}
