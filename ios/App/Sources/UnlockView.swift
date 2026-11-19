import SwiftUI
import UIKit
import UniformTypeIdentifiers
import OfflineOTPKit
import OfflineOTPPresentation

/// Lifecycle adapter only. Password, confirmation, errors and buttons are drawn by shared Compose.
struct UnlockView: View {
    @ObservedObject var store: VaultStore
    @State private var pickingRestore = false
    @State private var restoreItem: RestoreBackupItem?

    private struct RestoreBackupItem: Identifiable {
        let id = UUID()
        let data: Data
    }

    var body: some View {
        ComposeUnlockHost(store: store) { pickingRestore = true }
            .onAppear { store.refreshBiometry() }
            .task {
                // One automatic attempt per visit, preserving the existing biometric behavior.
                if store.canUnlockWithBiometrics { store.unlockWithBiometrics() }
            }
            .fileImporter(isPresented: $pickingRestore, allowedContentTypes: [.data],
                          allowsMultipleSelection: false) { result in
                do {
                    guard let url = try result.get().first else { return }
                    let scoped = url.startAccessingSecurityScopedResource()
                    defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                    let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                    guard size > 0, size <= InputPolicy.maxDocumentBytes else {
                        store.reportRestoreSelectionFailure("The backup file is too large or empty.")
                        return
                    }
                    restoreItem = RestoreBackupItem(data: try Data(contentsOf: url))
                } catch {
                    store.reportRestoreSelectionFailure("The encrypted backup could not be read.")
                }
            }
            .sheet(item: $restoreItem) { item in
                ComposeNewVaultRestoreHost(store: store, data: item.data) { restoreItem = nil }
            }
    }
}

private struct ComposeUnlockHost: UIViewControllerRepresentable {
    @ObservedObject var store: VaultStore
    let onRestore: () -> Void

    final class Coordinator: NSObject, UnlockActions {
        let store: VaultStore
        let presentation = UnlockPresentation()
        private let onRestore: () -> Void
        private var active = true

        init(store: VaultStore, onRestore: @escaping () -> Void) {
            self.store = store
            self.onRestore = onRestore
        }

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

        func restore() {
            guard active, !store.hasVault, !store.isUnlocked else { return }
            onRestore()
        }

        func close() { active = false; presentation.close() }
    }

    func makeCoordinator() -> Coordinator { Coordinator(store: store, onRestore: onRestore) }

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
