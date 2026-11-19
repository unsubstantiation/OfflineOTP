import SwiftUI
import UIKit
import OfflineOTPKit
import OfflineOTPPresentation

/// Native file/settings adapter only. The shared Compose dialog owns the confirmation wording and
/// the Apply/Cancel decision; this coordinator owns the picked bytes, the plan and the settings port.
struct ComposePreferencesImportHost: UIViewControllerRepresentable {
    @ObservedObject var store: VaultStore
    let data: Data
    let onDismiss: () -> Void
    let onApplied: (String) -> Void

    /// A throwaway port: applying into it validates the whole document and yields the same plan
    /// counts as the real import, without touching any setting before the person confirms.
    private final class ScratchPort: PreferencesExport.SettingsPort {
        func storedKeys() -> Set<String> { [] }
        func read(_ key: String) -> PreferencesExport.Value? { nil }
        func write(_ key: String, _ value: PreferencesExport.Value) {}
    }

    final class Coordinator: NSObject, PreferencesImportActions {
        let store: VaultStore
        let data: Data
        let presentation = PreferencesImportPresentation()
        private let generation: Int
        private let onDismiss: () -> Void
        private let onApplied: (String) -> Void
        private var active = true
        private var plan: PreferencesExport.Import?
        private var failure: String?

        init(store: VaultStore, data: Data, onDismiss: @escaping () -> Void,
             onApplied: @escaping (String) -> Void) {
            self.store = store
            self.data = data
            self.generation = store.generation
            self.onDismiss = onDismiss
            self.onApplied = onApplied
            do {
                plan = try PreferencesExport.apply(data, into: ScratchPort())
            } catch PreferencesExport.PreferencesError.wrongPlatform(let source, _) {
                failure = "That file holds \(source) preferences; this client can only import iOS ones."
            } catch {
                failure = "That file is not an OfflineOTP preferences file this version can read."
            }
            refresh()
        }

        private var currentSession: Bool { active && store.isUnlocked && store.generation == generation }

        func refresh() {
            guard active else { return }
            if !currentSession {
                close()
                DispatchQueue.main.async { self.onDismiss() }
                return
            }
            presentation.update(busy: false, count: plan.map { Int32($0.applied) } ?? -1,
                                skipped: Int32((plan?.unknownKeys.count ?? 0) + (plan?.refusedKeys.count ?? 0)),
                                failed: failure != nil, errorText: failure)
        }

        func confirm_() -> Bool {
            guard currentSession, plan != nil else { return false }
            do {
                let result = try PreferencesExport.apply(data, into: store.preferencesPort)
                // The auto-lock sheet must reflect a value that just changed underneath it.
                store.reloadInactivityLock()
                let skipped = result.unknownKeys.isEmpty ? ""
                    : " \(result.unknownKeys.count) settings are not known to this version and were skipped."
                onApplied("\(result.applied) settings imported.\(skipped)")
                return true
            } catch {
                return false
            }
        }

        func close() { active = false; presentation.close() }
        func dismiss() { close(); onDismiss() }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(store: store, data: data, onDismiss: onDismiss, onApplied: onApplied)
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
