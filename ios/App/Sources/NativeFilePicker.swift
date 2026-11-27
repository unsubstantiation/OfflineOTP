import UIKit
import UniformTypeIdentifiers

/// Native document IO only. Each callback belongs to one picker, never to a later request.
final class NativeFilePicker: NSObject, UIDocumentPickerDelegate {
    private var openHandler: (([URL]) -> Void)?
    private weak var activePicker: UIDocumentPickerViewController?
    private(set) var pendingExportURL: URL?
    private var pendingExportDirectory: URL?

    private func cleanupPendingExport() {
        let directory = pendingExportDirectory
        pendingExportDirectory = nil
        if let url = pendingExportURL {
            pendingExportURL = nil
            // Best effort only: flash/COW storage cannot promise physical erasure.
            if let handle = try? FileHandle(forWritingTo: url) {
                if let size = try? handle.seekToEnd(), size <= 17 * 1024 * 1024 {
                    try? handle.seek(toOffset: 0)
                    let zeros = Data(repeating: 0, count: 64 * 1024)
                    var remaining = size
                    while remaining > 0 {
                        let count = min(remaining, UInt64(zeros.count))
                        do { try handle.write(contentsOf: zeros.prefix(Int(count))) } catch { break }
                        remaining -= count
                    }
                }
                try? handle.close()
            }
            try? FileManager.default.removeItem(at: url)
        }
        if let directory { try? FileManager.default.removeItem(at: directory) }
    }

    func cancel() {
        let prior = activePicker
        activePicker = nil; openHandler = nil
        prior?.delegate = nil
        cleanupPendingExport()
        prior?.dismiss(animated: false)
    }

    func open(types: [UTType], from presenter: UIViewController, asCopy: Bool = true,
              handler: @escaping ([URL]) -> Void) {
        cancel()
        openHandler = handler
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: asCopy)
        activePicker = picker; picker.delegate = self
        picker.allowsMultipleSelection = false
        presenter.present(picker, animated: true)
    }

    func openFolder(from presenter: UIViewController, handler: @escaping ([URL]) -> Void) {
        cancel()
        openHandler = handler
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder], asCopy: false)
        activePicker = picker; picker.delegate = self
        picker.allowsMultipleSelection = false
        presenter.present(picker, animated: true)
    }

    func save(data: Data, named name: String, from presenter: UIViewController) {
        cancel()
        guard !data.isEmpty, data.count <= 17 * 1024 * 1024 else { return }
        let filename = (name as NSString).lastPathComponent
        guard !filename.isEmpty, filename != ".", filename != "..", filename != "/", !filename.contains("/"), filename.utf8.count <= 255,
              !filename.unicodeScalars.contains(where: { CharacterSet.controlCharacters.contains($0) }) else { return }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("OfflineOTP-export-" + UUID().uuidString, isDirectory: true)
        let url = directory.appendingPathComponent(filename)
        pendingExportDirectory = directory; pendingExportURL = url
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false,
                attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.complete])
            try data.write(to: url, options: [.atomic, .completeFileProtection])
            try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        } catch { cleanupPendingExport(); return }
        let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        activePicker = picker; picker.delegate = self
        presenter.present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard controller === activePicker else { return }
        activePicker = nil; controller.delegate = nil
        cleanupPendingExport()
        let handler = openHandler; openHandler = nil
        handler?(urls)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        guard controller === activePicker else { return }
        activePicker = nil; controller.delegate = nil
        cleanupPendingExport(); openHandler = nil
    }
    deinit { cleanupPendingExport() }
}
