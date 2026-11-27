import Foundation
import XCTest
@testable import OfflineOTPKit

final class PropertyListSettingsAtomicTests: XCTestCase {
    func testMergePreservesUnmentionedAndVaultKeysAndReopens() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("settings.plist")
        let settings = PropertyListSettings(url: url)
        settings.setString("auth_salt", "synthetic-vault-material")
        settings.setString("future_key", "keep")
        try settings.mergeAtomic(["ui_sort_mode": "LABEL", "ui_inactivity_lock_seconds": 45])
        let reopened = PropertyListSettings(url: url)
        XCTAssertEqual("synthetic-vault-material", reopened.string("auth_salt"))
        XCTAssertEqual("keep", reopened.string("future_key"))
        XCTAssertEqual("LABEL", reopened.string("ui_sort_mode"))
        XCTAssertEqual(45, reopened.integer("ui_inactivity_lock_seconds"))
    }
    func testFailedWritePreservesCacheAndCanBeRetried() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: directory) }
        let blocker = directory.appendingPathComponent("parent")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let settings = PropertyListSettings(url: blocker.appendingPathComponent("settings.plist"))
        try Data([1]).write(to: blocker)
        XCTAssertThrowsError(try settings.mergeAtomic(["ui_sort_mode": "LABEL"]))
        XCTAssertTrue(settings.keys().isEmpty())
        try FileManager.default.removeItem(at: blocker)
        try settings.mergeAtomic(["ui_sort_mode": "LABEL"])
        XCTAssertEqual("LABEL", settings.string("ui_sort_mode"))
        XCTAssertEqual("LABEL", PropertyListSettings(url: blocker.appendingPathComponent("settings.plist")).string("ui_sort_mode"))
    }
}
