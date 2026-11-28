import XCTest
import OfflineOTPKit
import OfflineOTPPresentation
@testable import OfflineOTP

final class SharedPersistenceAdapterTests: XCTestCase {
    func testProductionAndOracleRequireBackupAndOneShotPromotion() {
        let adapters: [PersistenceAdapter] = [SharedPersistenceAdapter(), NativePersistenceAdapter()]
        for adapter in adapters {
            let transaction = adapter.transaction(requiresBackup: true)
            XCTAssertFalse(transaction.beginPromotion())
            XCTAssertFalse(transaction.backedUp())
            XCTAssertTrue(transaction.verified())
            XCTAssertFalse(transaction.beginPromotion())
            XCTAssertTrue(transaction.backedUp())
            XCTAssertTrue(transaction.beginPromotion())
            XCTAssertTrue(transaction.promoted())
            XCTAssertFalse(transaction.promoted())
            XCTAssertFalse(transaction.beginPromotion())
            XCTAssertFalse(adapter.canInitialize(vaultExists: false, backupExists: true))
            XCTAssertTrue(adapter.canInitialize(vaultExists: false, backupExists: false))
        }
    }

    func testSharedJournalDoesNotDiscardAmbiguousOrCommittedWork() {
        let policy = PersistenceRecoveryPolicy.shared
        XCTAssertEqual(policy.journal(present: true, valid: true, markerMatches: true,
                                     livePromotion: false, expectedMatches: false, blocked: false), .promote)
        XCTAssertEqual(policy.journal(present: true, valid: true, markerMatches: false,
                                     livePromotion: false, expectedMatches: false, blocked: false), .discard)
        XCTAssertEqual(policy.journal(present: true, valid: false, markerMatches: false,
                                     livePromotion: false, expectedMatches: false, blocked: false), .block)
        let flow = PersistenceWorkflow(requiresBackup: false, metadataTransaction: true)
        XCTAssertTrue(flow.verified(permitted: true)); XCTAssertTrue(flow.staged())
        XCTAssertFalse(flow.metadataCommitted(decision: .unknown, markerMatches: true))
        XCTAssertEqual(flow.phase, .recoveryRequired)
        XCTAssertFalse(flow.promoted())
    }
}
