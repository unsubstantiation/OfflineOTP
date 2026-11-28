import Foundation
import XCTest
import OfflineOTPKit
import OfflineOTPPresentation
@testable import OfflineOTP

final class SharedRestoreWorkflowTests: XCTestCase {
    func testExportedStateMachineRequiresConsentAndRejectsStaleOrRepeatedResults() {
        let flow = RestoreWorkflow()
        let old = flow.start(authority: 1, firstRun: false)
        let current = flow.start(authority: 2, firstRun: false)
        XCTAssertFalse(flow.decoded(ticket: old, authority: 1, permitted: true, count: 1, unique: true))
        XCTAssertEqual(flow.phase, .reading)
        XCTAssertFalse(flow.confirm(ticket: current, authority: 2, permitted: true, replace: true))
        XCTAssertTrue(flow.decoded(ticket: current, authority: 2, permitted: true, count: 1, unique: true))
        XCTAssertFalse(flow.beginInstall(ticket: current, authority: 2, permitted: true, resultCount: 1))
        XCTAssertTrue(flow.confirm(ticket: current, authority: 2, permitted: true, replace: false))
        XCTAssertTrue(flow.beginInstall(ticket: current, authority: 2, permitted: true, resultCount: 1))
        XCTAssertFalse(flow.beginInstall(ticket: current, authority: 2, permitted: true, resultCount: 1))
        XCTAssertFalse(flow.installed(ticket: current, success: false))
        XCTAssertEqual(flow.failure, .storage)
        flow.cancel()
        XCTAssertFalse(flow.installed(ticket: current, success: true))
    }

    func testFirstRunAllowsEmptyButNeverInstallsWithoutConsent() {
        let flow = RestoreWorkflow(); let ticket = flow.start(authority: 0, firstRun: true)
        XCTAssertTrue(flow.decoded(ticket: ticket, authority: 0, permitted: true, count: 0, unique: true))
        XCTAssertFalse(flow.beginInstall(ticket: ticket, authority: 0, permitted: true, resultCount: 0))
        XCTAssertTrue(flow.confirm(ticket: ticket, authority: 0, permitted: true, replace: false))
        XCTAssertTrue(flow.replace)
        XCTAssertFalse(flow.beginInstall(ticket: ticket, authority: 1, permitted: true, resultCount: 0))
        XCTAssertEqual(flow.failure, .stale)
    }

    func testPreviewIsReadOnlyBoundToTheFileAndCannotReplayAfterLock() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: folder) }
        let store = VaultStore(directory: folder)
        store.unlock(password: "synthetic recovery", confirmation: "synthetic recovery", creating: true)
        XCTAssertTrue(store.isUnlocked)
        let source = Data(#"[{"type":"TOTP","secret":"JBSWY3DPEHPK3PXP","label":"synthetic import","digits":6,"algorithm":"SHA1","period":30}]"#.utf8)
        XCTAssertNil(store.restore(encrypted: source, password: "", replace: true, format: "andotp", andOtpVariant: "plain"))
        XCTAssertEqual(store.previewEncryptedBackupCount(encrypted: source, password: "", format: "andotp", andOtpVariant: "plain"), 1)
        XCTAssertTrue(store.entries.isEmpty)
        XCTAssertNil(store.restore(encrypted: source + Data(" ".utf8), password: "", replace: true, format: "andotp", andOtpVariant: "plain"))
        XCTAssertTrue(store.entries.isEmpty)
        XCTAssertEqual(store.previewEncryptedBackupCount(encrypted: source, password: "", format: "andotp", andOtpVariant: "plain"), 1)
        store.lock(); store.unlock(password: "synthetic recovery")
        XCTAssertNil(store.restore(encrypted: source, password: "", replace: true, format: "andotp", andOtpVariant: "plain"))
        XCTAssertTrue(store.entries.isEmpty)
        XCTAssertEqual(store.previewEncryptedBackupCount(encrypted: source, password: "", format: "andotp", andOtpVariant: "plain"), 1)
        XCTAssertNotNil(store.restore(encrypted: source, password: "", replace: false, format: "andotp", andOtpVariant: "plain"))
        XCTAssertNil(store.restore(encrypted: source, password: "", replace: true, format: "andotp", andOtpVariant: "plain"))
        XCTAssertEqual(store.entries.count, 1)
        let reopened = VaultStore(directory: folder); reopened.unlock(password: "synthetic recovery")
        XCTAssertEqual(reopened.entries.count, 1)
    }
}
