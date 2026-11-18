import SwiftUI
import UIKit
import OfflineOTPPresentation

/// Lifecycle adapter only. Password, confirmation, errors and buttons are drawn by shared Compose.
struct UnlockView: View {
    @ObservedObject var store: VaultStore

    var body: some View {
        ComposeUnlockHost(store: store)
            .onAppear { store.refreshBiometry() }
            .task {
                // One automatic attempt per visit, preserving the existing biometric behavior.
                if store.canUnlockWithBiometrics { store.unlockWithBiometrics() }
            }
    }
}

private struct ComposeUnlockHost: UIViewControllerRepresentable {
    @ObservedObject var store: VaultStore

    final class Coordinator: NSObject, UnlockActions {
        let store: VaultStore
        let presentation = UnlockPresentation()
        private var active = true

        init(store: VaultStore) { self.store = store }

        func refresh() {
            presentation.update(busy: false, error: store.message, creating: !store.hasVault,
                                quickUnlockLabel: store.canUnlockWithBiometrics ? "Unlock with \(store.biometryTitle)" : nil,
                                hint: store.folderPath)
        }

        func unlock(password: String) {
            guard active, !store.isUnlocked, store.hasVault else { return }
            store.unlock(password: password)
            refresh()
        }

        func create(password: String, confirmation: String) {
            guard active, !store.isUnlocked, !store.hasVault else { return }
            store.unlock(password: password, confirmation: confirmation, creating: true)
            refresh()
        }

        func quickUnlock() {
            guard active, !store.isUnlocked, store.canUnlockWithBiometrics else { return }
            store.unlockWithBiometrics()
        }

        func close() { active = false; presentation.close() }
    }

    func makeCoordinator() -> Coordinator { Coordinator(store: store) }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.refresh()
        return context.coordinator.presentation.viewController(
            strings: UnlockStrings(appTitle: "OfflineOTP", passwordLabel: "Vault password",
                                   unlockAction: "Unlock", unlocking: "Unlocking…"),
            actions: context.coordinator)
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {
        context.coordinator.refresh()
    }

    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Coordinator) {
        coordinator.close()
    }
}
