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
    private var subscription: AnyCancellable?
    private var inactivityTimer: Timer?
    private var current: UIViewController?
    private var privacyCover: UIView?

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
        inactivityTimer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            DispatchQueue.main.async { self?.store.checkInactivityAutoLock() }
        }
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }

    private func install(unlocked: Bool) {
        if unlocked && current is VaultComposeViewController { return }
        if !unlocked && current is UnlockComposeViewController { return }

        let next: UIViewController
        if unlocked {
            next = VaultComposeViewController(
                store: store,
                onExport: { [weak self] data, name in
                    guard let self else { return }
                    self.filePicker.save(data: data, named: name, from: self)
                },
                onImport: { [weak self] kind in
                    guard let self else { return }
                    if kind == "qrCamera" { self.scanQr() } else { self.pickImport(kind: kind) }
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
        filePicker.open(types: [.data], from: self) { [weak self] urls in
            guard let self, let url = urls.first else { return }
            self.readBounded(url) { data in
                (self.current as? UnlockComposeViewController)?.restore(from: data, store: self.store)
            }
        }
    }

    /// The camera is a native capability adapter; only the scanned string reaches the shared UI.
    private func scanQr() {
        let capture = QrCaptureViewController(
            onPayload: { [weak self] payload in
                (self?.current as? VaultComposeViewController)?.coordinator.importUriPayload(payload)
            },
            onUnavailable: { [weak self] in
                (self?.current as? VaultComposeViewController)?.coordinator.showQrUnavailable()
            })
        capture.modalPresentationStyle = .overFullScreen
        present(capture, animated: true)
    }

    private func pickImport(kind: String) {
        let types: [UTType] = kind == "preferences" ? [.json] : [.data, .json, .commaSeparatedText]
        filePicker.open(types: types, from: self) { [weak self] urls in
            guard let self, let url = urls.first else { return }
            self.readBounded(url) { data in
                guard let vault = self.current as? VaultComposeViewController else { return }
                if kind.hasPrefix("openpgp") {
                    vault.coordinator.setOpenPgpData(data, kind: kind)
                } else {
                    vault.coordinator.imported(data: data, kind: kind)
                }
            }
        }
    }

    /// One bounded read per picked file; the stored size limit is the same on every client.
    private func readBounded(_ url: URL, then: @escaping (Data) -> Void) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        do {
            let values = try url.resourceValues(forKeys: [.fileSizeKey])
            let size = values.fileSize ?? 0
            guard size > 0, size <= InputPolicy.maxDocumentBytes else {
                store.reportRestoreSelectionFailure("The file is too large or empty.")
                return
            }
            then(try Data(contentsOf: url))
        } catch {
            store.reportRestoreSelectionFailure("The file could not be read.")
        }
    }

    @objc private func sceneActive() {
        setCovered(false)
        store.checkInactivityAutoLock()
    }

    @objc private func sceneInactive() {
        setCovered(true)
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
