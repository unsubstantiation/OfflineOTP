import Combine
import UIKit
import OfflineOTPKit
import OfflineOTPPresentation

/// Lockscreen adapter. The form, its wording and all validation UI are shared Compose; this class
/// owns only authentication, biometric availability and native file selection for first-run restore.
final class UnlockCoordinator: NSObject, UnlockActions {
    let store: VaultStore
    let presentation = UnlockPresentation()
    private let onRestore: () -> Void
    var onAbout: (() -> Void)?
    private var subscription: AnyCancellable?
    private var attemptedBiometricAutoPrompt = false
    private var active = true

    init(store: VaultStore, onRestore: @escaping () -> Void) {
        self.store = store
        self.onRestore = onRestore
        super.init()
        subscription = store.objectWillChange
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in self?.render() }
    }

    private func render() {
        guard active else { return }
        presentation.update(busy: false, error: store.message, creating: !store.hasVault,
                             quickUnlockLabel: store.canUnlockWithBiometrics ? "Unlock with \(store.biometryTitle)" : nil,
                             hint: store.folderPath)
    }

    func refresh() {
        guard active else { return }
        store.refreshBiometry()
        render()
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
        refresh()
    }

    func restore() {
        guard active, !store.hasVault, !store.isUnlocked else { return }
        onRestore()
    }

    func about() {
        guard active, !store.isUnlocked else { return }
        onAbout?()
    }

    func attemptBiometricUnlockOnce() {
        guard active, !attemptedBiometricAutoPrompt, !store.isUnlocked, store.canUnlockWithBiometrics else { return }
        attemptedBiometricAutoPrompt = true
        store.unlockWithBiometrics()
        refresh()
    }

    func close() {
        active = false
        onAbout = nil
        subscription?.cancel()
        subscription = nil
        presentation.close()
    }
}

/// UIKit host for the shared unlock/create screen. No SwiftUI view or fallback form exists.
final class UnlockComposeViewController: UIViewController {
    let coordinator: UnlockCoordinator

    init(store: VaultStore, onRestore: @escaping () -> Void) {
        coordinator = UnlockCoordinator(store: store, onRestore: onRestore)
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("use init(store:onRestore:)") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ForestPalette.background
        coordinator.onAbout = { [weak self] in self?.presentAbout() }
        coordinator.refresh()
        let composed = coordinator.presentation.viewController(
            strings: UnlockStrings(appTitle: "OfflineOTP", passwordLabel: "Vault password",
                                   unlockAction: "Unlock", unlocking: "Unlocking…"),
            actions: coordinator)
        addChild(composed)
        composed.view.frame = view.bounds
        composed.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(composed.view)
        composed.didMove(toParent: self)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        coordinator.attemptBiometricUnlockOnce()
    }

    func refresh() { coordinator.refresh() }

    /// Presents the shared About dialog prior to unlock, matching Desktop's AboutNavigation.
    func presentAbout() {
        guard presentedViewController == nil else { return }
        let buildVersion = (Bundle.main.object(forInfoDictionaryKey: "OfflineOTPBuildVersion") as? String)
            ?? (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String)
            ?? "Build version unavailable"
        let about = AboutComposeViewController(version: buildVersion) { url in
            UIApplication.shared.open(url, options: [:], completionHandler: nil)
        }
        about.modalPresentationStyle = .overFullScreen
        present(about, animated: true)
    }

    /// Presents the shared full-restore form after the native picker returned bounded bytes.
    func restore(from data: Data, store: VaultStore) {
        let restore = RestoreComposeViewController(store: store, data: data)
        restore.modalPresentationStyle = .overFullScreen
        present(restore, animated: true)
    }

    override func didMove(toParent parent: UIViewController?) {
        super.didMove(toParent: parent)
        if parent == nil { coordinator.close() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if isMovingFromParent || isBeingDismissed || parent == nil {
            coordinator.close()
        }
    }
}

/// Native browser/lifecycle adapter for the shared pre-unlock About dialog.
final class AboutCoordinator: NSObject, AboutActions {
    let version: String
    let presentation = AboutPresentation()
    private let onOpenLink: (URL) -> Void
    var onDismiss: (() -> Void)?
    private var active = true

    init(version: String, onOpenLink: @escaping (URL) -> Void) {
        self.version = version
        self.onOpenLink = onOpenLink
    }

    func openLink(url: String) -> Bool {
        guard active else { return false }
        let allowedIds = ["website", "releases", "changelog", "license", "source", "issues",
                          "author1", "author2", "originalAuthor", "originalApp", "contributors", "support"]
        guard allowedIds.contains(where: { AboutInfo.url(id: $0) == url }),
              let target = URL(string: url),
              ["http", "https"].contains(target.scheme ?? "") else {
            return false
        }
        onOpenLink(target)
        return true
    }

    func dismiss() {
        close()
        onDismiss?()
    }

    func close() {
        active = false
        onDismiss = nil
        presentation.close()
    }
}

final class AboutComposeViewController: UIViewController {
    let coordinator: AboutCoordinator

    init(version: String, onOpenLink: @escaping (URL) -> Void) {
        coordinator = AboutCoordinator(version: version, onOpenLink: onOpenLink)
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("use init(version:onOpenLink:)") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ForestPalette.scrim
        coordinator.onDismiss = { [weak self] in
            self?.dismiss(animated: false)
        }
        let composed = coordinator.presentation.viewController(version: coordinator.version, actions: coordinator)
        addChild(composed)
        composed.view.frame = view.bounds
        composed.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(composed.view)
        composed.didMove(toParent: self)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if isMovingFromParent || isBeingDismissed || parent == nil {
            coordinator.close()
        }
    }
}

/// Native vault/store adapter for the shared first-run restore dialog.
final class RestoreCoordinator: NSObject, NewVaultRestoreActions {
    let store: VaultStore
    let data: Data
    let presentation = NewVaultRestorePresentation(backupName: "Selected encrypted backup")
    var onDismiss: (() -> Void)?
    private var active = true

    init(store: VaultStore, data: Data) {
        self.store = store
        self.data = data
    }

    func restore(backupPassword: String, newPassword: String, confirmation: String) -> String? {
        guard active, !store.hasVault, !store.isUnlocked else { return "A vault already exists here." }
        return store.restoreNewVault(encrypted: data, backupPassword: backupPassword,
                                     newPassword: newPassword, confirmation: confirmation)
            ? nil : (store.message ?? "The backup could not be restored.")
    }

    func dismiss() {
        close()
        onDismiss?()
    }
    func close() { active = false; presentation.close() }
}

final class RestoreComposeViewController: UIViewController {
    let coordinator: RestoreCoordinator

    init(store: VaultStore, data: Data) {
        coordinator = RestoreCoordinator(store: store, data: data)
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("use init(store:data:)") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ForestPalette.scrim
        coordinator.onDismiss = { [weak self] in
            self?.dismiss(animated: false)
        }
        let composed = coordinator.presentation.viewController(actions: coordinator)
        addChild(composed)
        composed.view.frame = view.bounds
        composed.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(composed.view)
        composed.didMove(toParent: self)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if isMovingFromParent || isBeingDismissed || parent == nil {
            coordinator.close()
        }
    }
}
