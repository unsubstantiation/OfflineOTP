import Combine
import UIKit
import UniformTypeIdentifiers
import OfflineOTPKit

/// The app shell. It owns the store, switches between the shared Compose unlock and vault screens,
/// and provides only the services UIKit must supply: document pickers, scene lifecycle and the
/// privacy cover. There is no SwiftUI view in the release path.
final class AppRootViewController: UIViewController {
    let store = VaultStore()

    private let filePicker = NativeFilePicker()
    private let fileReader = NativeFileReader()
    private var subscription: AnyCancellable?
    private var inactivityTimer: Timer?
    private var current: UIViewController?
    private var privacyCover: UIView?
    private var nativeEpoch = UUID()
    private var foreground = UIApplication.shared.applicationState != .background

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = ForestPalette.background
        install(unlocked: store.isUnlocked)
        subscription = store.$isUnlocked.receive(on: DispatchQueue.main)
            .sink { [weak self] unlocked in self?.install(unlocked: unlocked) }
        NotificationCenter.default.addObserver(self, selector: #selector(sceneActive),
                                               name: UIScene.didActivateNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sceneInactive),
                                               name: UIScene.willDeactivateNotification, object: nil)
        NotificationCenter.default.addObserver(self, selector: #selector(sceneBackgrounded),
                                               name: UIScene.didEnterBackgroundNotification, object: nil)
        inactivityTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            DispatchQueue.main.async { self?.store.checkInactivityAutoLock() }
        }
    }

    func noteWindowActivity() {
        if store.isUnlocked { store.noteUserActivity() }
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    private func install(unlocked: Bool) {
        if unlocked && current is VaultComposeViewController { return }
        if !unlocked && current is UnlockComposeViewController { return }

        nativeEpoch = UUID()
        filePicker.cancel(); fileReader.cancel()
        if presentedViewController != nil {
            dismiss(animated: false)
        }
        if current?.presentedViewController != nil {
            current?.dismiss(animated: false)
        }
        (current as? VaultComposeViewController)?.coordinator.invalidate()
        (current as? UnlockComposeViewController)?.coordinator.close()

        let next: UIViewController
        if unlocked {
            next = VaultComposeViewController(
                store: store,
                onExport: { [weak self] data, name in
                    guard let self, self.foreground, self.store.isUnlocked,
                          self.current is VaultComposeViewController else { return }
                    self.filePicker.save(data: data, named: name, from: self)
                },
                onImport: { [weak self] kind in
                    guard let self else { return }
                    if kind == "automaticBackupFolder" { self.pickAutomaticBackupFolder() }
                    else if kind == "qrCamera" { self.scanQr() } else if kind == "qrImage" { self.pickQrImage() } else { self.pickImport(kind: kind) }
                },
                onOpenLink: { url in
                    UIApplication.shared.open(url, options: [:], completionHandler: nil)
                })
        } else {
            next = UnlockComposeViewController(store: store) { [weak self] in
                self?.pickRestoreBackup()
            }
        }

        current?.willMove(toParent: nil)
        current?.view.removeFromSuperview()
        current?.removeFromParent()
        addChild(next)
        next.view.frame = view.bounds
        next.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.insertSubview(next.view, at: 0)
        next.didMove(toParent: self)
        current = next
    }

    private func pickRestoreBackup() {
        guard foreground, !store.isUnlocked, !store.hasVault,
              let origin = current as? UnlockComposeViewController else { return }
        nativeEpoch = UUID(); fileReader.cancel(); let epoch = nativeEpoch; let generation = store.generation
        filePicker.open(types: [.data], from: self) { [weak self, weak origin] urls in
            guard let self, let origin, self.foreground, self.nativeEpoch == epoch,
                  self.store.generation == generation, self.current === origin,
                  !self.store.isUnlocked, !self.store.hasVault, let url = urls.first else { return }
            self.readBounded(url) { data in
                guard self.current === origin, self.nativeEpoch == epoch else { return }
                origin.restore(from: data, store: self.store)
            }
        }
    }

    /// Native callbacks carry originating coordinator + session/surface authority, not `current` alone.
    private func scanQr() {
        guard foreground, let origin = current as? VaultComposeViewController else { return }
        let coordinator = origin.coordinator
        let request = coordinator.nativeRequestID; let generation = store.generation
        nativeEpoch = UUID(); fileReader.cancel(); let epoch = nativeEpoch
        func permitted(_ owner: AppRootViewController, _ coordinator: VaultCoordinator) -> Bool {
            owner.foreground && owner.nativeEpoch == epoch && owner.store.generation == generation
                && (owner.current as? VaultComposeViewController)?.coordinator === coordinator
                && coordinator.acceptsNativeResult(request)
        }
        let capture = QrCaptureViewController(
            onPayload: { [weak self, weak coordinator] payload in
                guard let self, let coordinator, permitted(self, coordinator) else { return }
                coordinator.importUriPayload(payload)
            },
            onUnavailable: { [weak self, weak coordinator] in
                guard let self, let coordinator, permitted(self, coordinator) else { return }
                coordinator.showQrUnavailable()
            })
        capture.modalPresentationStyle = .overFullScreen
        present(capture, animated: true)
    }

    private func pickQrImage() {
        guard foreground, let origin = current as? VaultComposeViewController else { return }
        let coordinator = origin.coordinator
        let request = coordinator.nativeRequestID; let generation = store.generation
        nativeEpoch = UUID(); fileReader.cancel(); let epoch = nativeEpoch
        filePicker.open(types: [.image], from: self) { [weak self, weak coordinator] urls in
            guard let self, let coordinator, self.foreground, self.nativeEpoch == epoch,
                  self.store.generation == generation, coordinator.acceptsNativeResult(request),
                  (self.current as? VaultComposeViewController)?.coordinator === coordinator,
                  let url = urls.first else { return }
            self.readBounded(url, onFailure: { [weak coordinator] _ in
                guard let coordinator, coordinator.acceptsNativeResult(request) else { return }
                coordinator.importedQrImagePayload(nil)
            }) { data in
                guard coordinator.acceptsNativeResult(request) else { return }
                let payload = UIImage(data: data).flatMap(QrImageReader.payload(in:))
                coordinator.importedQrImagePayload(payload)
            }
        }
    }

    private func pickAutomaticBackupFolder() {
        guard foreground, let vault = current as? VaultComposeViewController else { return }
        let coordinator = vault.coordinator
        let generation = store.generation; let request = coordinator.nativeRequestID
        nativeEpoch = UUID(); fileReader.cancel(); let epoch = nativeEpoch
        filePicker.openFolder(from: self) { [weak self, weak coordinator] urls in
            guard let self = self, let coordinator = coordinator, self.foreground, self.store.isUnlocked,
                  self.nativeEpoch == epoch, coordinator.acceptsNativeResult(request), self.store.generation == generation,
                  (self.current as? VaultComposeViewController)?.coordinator === coordinator,
                  let folder = urls.first else { return }
            coordinator.selectedAutomaticBackupFolder(folder)
        }
    }

    private func pickImport(kind: String) {
        guard foreground, let origin = current as? VaultComposeViewController else { return }
        let coordinator = origin.coordinator
        let generation = store.generation; let request = coordinator.nativeRequestID
        nativeEpoch = UUID(); fileReader.cancel(); let epoch = nativeEpoch
        let types: [UTType] = kind == "preferences" ? [.json] : [.data, .json, .commaSeparatedText]
        filePicker.open(types: types, from: self, asCopy: kind != "openpgpAutomaticSigningKey") { [weak self, weak coordinator] urls in
            guard let self, let coordinator, self.foreground, self.nativeEpoch == epoch,
                  self.store.generation == generation, coordinator.acceptsNativeResult(request),
                  (self.current as? VaultComposeViewController)?.coordinator === coordinator,
                  let url = urls.first else { return }
            let filename = url.lastPathComponent
            self.readBounded(url, onFailure: { [weak coordinator] reason in
                guard let coordinator, coordinator.acceptsNativeResult(request) else { return }
                coordinator.reportImportFileFailure(reason)
            }) { data in
                guard coordinator.acceptsNativeResult(request) else { return }
                if kind.hasPrefix("openpgp") { coordinator.setOpenPgpData(data, kind: kind, filename: filename) }
                else { coordinator.imported(data: data, kind: kind) }
            }
        }
    }

    /// One bounded read per picked file; the stored size limit is the same on every client.
    private func readBounded(
        _ url: URL,
        onFailure: ((String) -> Void)? = nil,
        then: @escaping (Data) -> Void
    ) {
        let reportFailure = onFailure ?? { [weak self] reason in
            self?.store.reportRestoreSelectionFailure(reason)
        }
        let epoch = nativeEpoch; let generation = store.generation
        fileReader.start(url, maxBytes: InputPolicy.maxDocumentBytes) { [weak self] data in
            guard let self, self.foreground, self.nativeEpoch == epoch, self.store.generation == generation else { return }
            guard let data else { reportFailure("The file is empty, oversized or could not be read."); return }
            then(data)
        }
    }

    @objc private func sceneActive() {
        foreground = true
        store.checkInactivityAutoLock()
        setCovered(false)
    }

    @objc private func sceneInactive() {
        setCovered(true)
    }

    @objc private func sceneBackgrounded() {
        store.cancelPendingUnlock()
        foreground = false; nativeEpoch = UUID(); filePicker.cancel(); fileReader.cancel()
        setCovered(true)
        if store.isUnlocked && store.relockOnBackground {
            store.lock()
        }
    }

    /// iOS cannot block screenshots; covering the window keeps a revealed code out of the snapshot.
    private func setCovered(_ covered: Bool) {
        if covered, privacyCover == nil {
            let cover = UIView(frame: view.bounds)
            cover.backgroundColor = ForestPalette.background
            cover.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            let lock = UIImageView(image: UIImage(systemName: "lock.fill"))
            lock.tintColor = ForestPalette.textHint
            lock.translatesAutoresizingMaskIntoConstraints = false
            cover.addSubview(lock)
            NSLayoutConstraint.activate([
                lock.centerXAnchor.constraint(equalTo: cover.centerXAnchor),
                lock.centerYAnchor.constraint(equalTo: cover.centerYAnchor),
                lock.widthAnchor.constraint(equalToConstant: 44),
                lock.heightAnchor.constraint(equalToConstant: 44)
            ])
            view.addSubview(cover)
            privacyCover = cover
        } else if !covered {
            privacyCover?.removeFromSuperview()
            privacyCover = nil
        }
    }

    deinit {
        subscription?.cancel()
        inactivityTimer?.invalidate()
        NotificationCenter.default.removeObserver(self)
    }
}
