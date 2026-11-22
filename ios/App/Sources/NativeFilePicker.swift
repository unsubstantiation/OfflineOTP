import UIKit
import UniformTypeIdentifiers

/// Native document IO adapter. It carries bytes and names only: no picker is a UI surface the app
/// owns, and nothing here decides how a backup, CSV or preferences file is interpreted.
final class NativeFilePicker: NSObject, UIDocumentPickerDelegate {
    private var openHandler: (([URL]) -> Void)?
    private var pendingExportURL: URL?

    private func cleanupPendingExport() {
        guard let url = pendingExportURL else { return }
        pendingExportURL = nil
        if let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
           let size = attrs[.size] as? Int, size > 0 {
            try? Data(repeating: 0, count: size).write(to: url)
        }
        try? FileManager.default.removeItem(at: url)
    }

    func open(types: [UTType], from presenter: UIViewController,
              handler: @escaping ([URL]) -> Void) {
        cleanupPendingExport()
        openHandler = handler
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        presenter.present(picker, animated: true)
    }

    func save(data: Data, named name: String, from presenter: UIViewController) {
        cleanupPendingExport()
        openHandler = nil
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do {
            try data.write(to: url, options: .atomic)
        } catch {
            return
        }
        pendingExportURL = url
        let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        picker.delegate = self
        presenter.present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        cleanupPendingExport()
        let handler = openHandler
        openHandler = nil
        handler?(urls)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        cleanupPendingExport()
        openHandler = nil
    }
}
