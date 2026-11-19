import SwiftUI
import UIKit
import OfflineOTPKit
import OfflineOTPPresentation

/// Native file bytes and vault adapter; the restore/password form itself is shared Compose.
struct ComposeNewVaultRestoreHost: UIViewControllerRepresentable {
    @ObservedObject var store: VaultStore
    let data: Data
    let onDismiss: () -> Void

    final class Coordinator: NSObject, NewVaultRestoreActions {
        let store: VaultStore
        let data: Data
        let presentation: NewVaultRestorePresentation
        private let onDismiss: () -> Void
        private var active = true

        init(store: VaultStore, data: Data, onDismiss: @escaping () -> Void) {
            self.store = store
            self.data = data
            self.onDismiss = onDismiss
            presentation = NewVaultRestorePresentation(backupName: "Selected encrypted backup")
        }

        func restore(backupPassword: String, newPassword: String, confirmation: String) -> String? {
            guard active, !store.hasVault, !store.isUnlocked else { return "A vault already exists here." }
            return store.restoreNewVault(encrypted: data, backupPassword: backupPassword,
                                         newPassword: newPassword, confirmation: confirmation)
                ? nil : (store.message ?? "The backup could not be restored.")
        }

        func dismiss() { close(); onDismiss() }
        func close() { active = false; presentation.close() }
    }

    func makeCoordinator() -> Coordinator { Coordinator(store: store, data: data, onDismiss: onDismiss) }
    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.presentation.viewController(actions: context.coordinator)
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) {}
    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Coordinator) {
        coordinator.close()
    }
}
