import XCTest
import UIKit
import UniformTypeIdentifiers
@testable import OfflineOTP

final class NativeFilePickerTests: XCTestCase {
    private final class Presenter: UIViewController {
        var shown: UIViewController?
        override func present(_ viewControllerToPresent: UIViewController, animated flag: Bool, completion: (() -> Void)? = nil) {
            shown = viewControllerToPresent; completion?()
        }
    }
    @MainActor
    func testLatePickerCannotInvokeOrCancelNewHandlers() throws {
        let picker = NativeFilePicker(); let presenter = Presenter()
        var old = 0; var new = 0
        picker.open(types: [.data], from: presenter) { _ in old += 1 }
        let first = try XCTUnwrap(presenter.shown as? UIDocumentPickerViewController)
        picker.open(types: [.data], from: presenter) { _ in new += 1 }
        let second = try XCTUnwrap(presenter.shown as? UIDocumentPickerViewController)
        picker.documentPicker(first, didPickDocumentsAt: [URL(fileURLWithPath: "/synthetic/old")])
        picker.documentPickerWasCancelled(first)
        XCTAssertEqual(0, old); XCTAssertEqual(0, new)
        picker.documentPicker(second, didPickDocumentsAt: [URL(fileURLWithPath: "/synthetic/new")])
        picker.documentPicker(second, didPickDocumentsAt: [])
        XCTAssertEqual(0, old); XCTAssertEqual(1, new)
    }
    @MainActor
    func testExportIsPrivateIsolatedAndRevokedOnCancelWithoutTouchingUnrelatedFiles() throws {
        let picker = NativeFilePicker(); let presenter = Presenter()
        picker.save(data: Data("synthetic export".utf8), named: "../../backup.csv", from: presenter)
        let url = try XCTUnwrap(picker.pendingExportURL)
        let directory = url.deletingLastPathComponent()
        XCTAssertEqual("backup.csv", url.lastPathComponent)
        XCTAssertTrue(directory.lastPathComponent.hasPrefix("OfflineOTP-export-"))
        XCTAssertEqual(Data("synthetic export".utf8), try Data(contentsOf: url))
        let mode = try FileManager.default.attributesOfItem(atPath: directory.path)[.posixPermissions] as? NSNumber
        XCTAssertEqual(0o700, (mode?.intValue ?? 0) & 0o777)
        picker.cancel()
        XCTAssertNil(picker.pendingExportURL)
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
    }
    @MainActor
    func testCancelledImportAndInvalidExportNamesAreInert() throws {
        let picker = NativeFilePicker(); let presenter = Presenter()
        picker.open(types: [.data], from: presenter) { _ in XCTFail("cancelled handler called") }
        let origin = try XCTUnwrap(presenter.shown as? UIDocumentPickerViewController)
        picker.cancel(); picker.documentPicker(origin, didPickDocumentsAt: [])
        for name in ["", "/", ".", "..", "bad\n.csv"] {
            picker.save(data: Data([1]), named: name, from: presenter)
            XCTAssertNil(picker.pendingExportURL, name)
        }
    }
}
