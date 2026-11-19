import SwiftUI
import UIKit
import OfflineOTPKit
import OfflineOTPPresentation

struct ComposeBiometricEnableHost: UIViewControllerRepresentable {
    @ObservedObject var store: VaultStore
    let onDismiss: () -> Void

    final class Coordinator: NSObject, BiometricEnableActions {
        let store: VaultStore
        let presentation: BiometricEnablePresentation
        private let onDismiss: () -> Void
        private var active = true

        init(store: VaultStore, onDismiss: @escaping () -> Void) {
            self.store = store
            self.onDismiss = onDismiss
            let title = "Turn on \(store.biometryTitle)"
            presentation = BiometricEnablePresentation(
                title: title,
                detail: "Confirm your vault password to enable quick unlock.",
                passwordLabel: "Vault password", confirmLabel: title, cancelLabel: "Cancel")
        }

        func confirm(password: String) -> String? {
            guard active, store.isUnlocked, store.biometry.isAvailable else {
                return store.biometry.refusal.isEmpty ? "Quick unlock is no longer available." : store.biometry.refusal
            }
            return store.enableBiometricUnlock(password: password) ? nil
                : (store.message ?? "Biometric unlock could not be enabled.")
        }

        func dismiss() { close(); onDismiss() }
        func close() { active = false; presentation.close() }
    }

    func makeCoordinator() -> Coordinator { Coordinator(store: store, onDismiss: onDismiss) }
    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.presentation.viewController(actions: context.coordinator)
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Coordinator) {
        coordinator.close()
    }
}
