import UIKit
import UniformTypeIdentifiers

/// Native document IO adapter. It carries bytes and names only: no picker is a UI surface the app
/// owns, and nothing here decides how a backup, CSV or preferences file is interpreted.
final class NativeFilePicker: NSObject, UIDocumentPickerDelegate {
    private var openHandler: (([URL]) -> Void)?

    func open(types: [UTType], from presenter: UIViewController,
              handler: @escaping ([URL]) -> Void) {
        openHandler = handler
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: types, asCopy: true)
        picker.delegate = self
        picker.allowsMultipleSelection = false
        presenter.present(picker, animated: true)
    }

    func save(data: Data, named name: String, from presenter: UIViewController) {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        do {
            try data.write(to: url, options: .atomic)
        } catch {
            return
        }
        let picker = UIDocumentPickerViewController(forExporting: [url], asCopy: true)
        presenter.present(picker, animated: true)
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        let handler = openHandler
        openHandler = nil
        handler?(urls)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
        openHandler = nil
    }
}
