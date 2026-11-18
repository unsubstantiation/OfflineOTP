import SwiftUI
import UIKit
import OfflineOTPKit
import OfflineOTPPresentation

/// Native delete adapter only. The shared Compose dialog owns the confirmation wording and the
/// Delete/Cancel decision; this coordinator owns the pending removal and its session checks.
struct ComposeEntryConfirmationHost: UIViewControllerRepresentable {
    @ObservedObject var store: VaultStore
    let index: Int
    let name: String
    let entry: Entry
    let epoch: Int
    let onDismiss: () -> Void

    final class Coordinator: NSObject, EntryConfirmationActions {
        let store: VaultStore
        let index: Int
        let entry: Entry
        let epoch: Int
        let presentation: EntryConfirmationPresentation
        private let onDismiss: () -> Void
        private var active = true

        init(store: VaultStore, index: Int, name: String, entry: Entry, epoch: Int,
             onDismiss: @escaping () -> Void) {
            self.store = store
            self.index = index
            self.entry = entry
            self.epoch = epoch
            self.onDismiss = onDismiss
            // The prompt names the entry the way every client does, and keeps the desktop title.
            presentation = EntryConfirmationPresentation(
                title: "Delete account?",
                message: "Are you sure you want to remove the account \"\(name)\"? This cannot be undone.",
                confirmLabel: "Delete", dismissLabel: "Cancel")
        }

        private var current: Bool {
            active && store.isUnlocked && store.generation == epoch
                && store.entries.indices.contains(index)
                && store.entries[index].fields == entry.fields
                && store.isRevealed(entry)
        }

        func confirm() -> String? {
            guard current else { return "The entry changed. Close and try again." }
            return store.remove(at: index) ? nil : (store.message ?? "The entry could not be removed.")
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

    func makeCoordinator() -> Coordinator {
        Coordinator(store: store, index: index, name: name, entry: entry, epoch: epoch, onDismiss: onDismiss)
    }

    func makeUIViewController(context: Context) -> UIViewController {
        context.coordinator.refresh()
        return context.coordinator.presentation.viewController(actions: context.coordinator)
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {
        context.coordinator.refresh()
    }

    static func dismantleUIViewController(_ controller: UIViewController, coordinator: Coordinator) {
        coordinator.close()
    }
}
