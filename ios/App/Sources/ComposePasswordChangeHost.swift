import SwiftUI
import UIKit
import OfflineOTPKit
import OfflineOTPPresentation

/// Native rekey/session adapter only. Compose owns all three ephemeral password fields.
struct ComposePasswordChangeHost: UIViewControllerRepresentable {
    @ObservedObject var store: VaultStore
    let onDismiss: () -> Void

    final class Coordinator: NSObject, PasswordChangeActions {
        let store: VaultStore
        let presentation = PasswordChangePresentation(maxFieldChars: Int32(InputPolicy.maxTextChars))
        private let generation: Int
        private let onDismiss: () -> Void
        private var active = true

        init(store: VaultStore, onDismiss: @escaping () -> Void) {
            self.store = store
            generation = store.generation
            self.onDismiss = onDismiss
        }

        private var currentSession: Bool { active && store.isUnlocked && store.generation == generation }

        func change(current: String, next: String, confirmation: String) -> String? {
            guard currentSession else { return "The session changed. Close and reopen settings." }
            guard !current.isEmpty, next != current, next == confirmation,
                  [current, next, confirmation].allSatisfy({ $0.utf16.count <= InputPolicy.maxTextChars }) else {
                return "Enter the current password and a different new password with matching confirmation."
            }
            return store.changePassword(currentPassword: current, newPassword: next, confirmation: confirmation)
                ? nil : (store.message ?? "The vault password could not be updated.")
        }

        func successMessage() -> String {
            store.message ?? "Vault password updated. Existing backups keep their own passwords."
        }

        func refresh() {
            if active && !currentSession {
                close()
                DispatchQueue.main.async { self.onDismiss() }
            }
        }
        func close() { active = false; presentation.close() }
        func dismiss() { close(); onDismiss() }
    }

    func makeCoordinator() -> Coordinator { Coordinator(store: store, onDismiss: onDismiss) }
    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.refresh()
        return context.coordinator.presentation.viewController(actions: context.coordinator)
    }
    func updateUIViewController(_ controller: UIViewController, context: Context) { context.coordinator.refresh() }
    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Coordinator) { coordinator.close() }
}
