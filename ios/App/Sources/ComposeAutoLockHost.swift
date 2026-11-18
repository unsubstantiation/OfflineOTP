import SwiftUI
import UIKit
import OfflineOTPPresentation

/// Native persistence/session adapter. Presets, labels, selection and confirmation are Compose.
struct ComposeAutoLockHost: UIViewControllerRepresentable {
    @ObservedObject var store: VaultStore
    let onDismiss: () -> Void

    final class Coordinator: NSObject, AutoLockActions {
        let store: VaultStore
        let presentation: AutoLockPresentation
        private let generation: Int
        private var expectedSeconds: Int
        private let onDismiss: () -> Void
        private var active = true

        init(store: VaultStore, onDismiss: @escaping () -> Void) {
            self.store = store
            generation = store.generation
            expectedSeconds = store.inactivityLockSeconds
            presentation = AutoLockPresentation(currentSeconds: Int32(clamping: store.inactivityLockSeconds))
            self.onDismiss = onDismiss
        }

        private var current: Bool {
            active && store.isUnlocked && store.generation == generation
                && store.inactivityLockSeconds == expectedSeconds
        }

        func save(seconds: Int32) -> Bool {
            guard current, seconds >= 0 else { return false }
            expectedSeconds = Int(seconds)
            store.setInactivityLockSeconds(Int(seconds))
            return store.inactivityLockSeconds == Int(seconds)
        }

        func refresh() {
            if active && !current {
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
