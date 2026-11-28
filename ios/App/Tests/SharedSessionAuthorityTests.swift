import Foundation
import XCTest
import OfflineOTPPresentation
@testable import OfflineOTP

final class SharedSessionAuthorityTests: XCTestCase {
    func testExportedAuthorityRevokesOnLockFailureAndRekeyWithoutTransportingKeys() {
        let session = SessionAuthority()
        XCTAssertEqual(session.phase, .locked)
        let first = session.beginUnlock()
        XCTAssertNil(session.activate(expected: first, keyChanged: true))
        let unlocked = session.generation
        XCTAssertTrue(session.isUnlocked(expected: unlocked))
        XCTAssertNil(session.activate(expected: unlocked, keyChanged: false))
        XCTAssertEqual(session.generation, unlocked)
        XCTAssertNil(session.activate(expected: unlocked, keyChanged: true))
        XCTAssertFalse(session.isCurrent(expected: unlocked))
        _ = session.fail(error: .recoveryRequired)
        XCTAssertEqual(session.activate(expected: session.generation, keyChanged: true), .failedSession)
        let fresh = session.beginUnlock()
        XCTAssertNil(session.activate(expected: fresh, keyChanged: true))
        _ = session.lock(reason: .background)
        XCTAssertFalse(session.isUnlocked(expected: session.generation))
    }

    func testExportedAttemptRejectsCancelledStaleAndDuplicateProviderResults() {
        let attempt = UnlockAttemptPolicy()
        let old = attempt.start(generation: 1, permitted: true)
        attempt.cancel()
        let current = attempt.start(generation: 2, permitted: true)
        XCTAssertFalse(attempt.complete(ticket: old, generation: 1, permitted: true, authenticated: true))
        XCTAssertTrue(attempt.current(ticket: current, generation: 2, permitted: true))
        XCTAssertTrue(attempt.complete(ticket: current, generation: 2, permitted: true, authenticated: true))
        XCTAssertFalse(attempt.complete(ticket: current, generation: 2, permitted: true, authenticated: true))
        attempt.close()
        XCTAssertEqual(attempt.start(generation: 2, permitted: true), 0)
    }

    func testExportedElapsedDeadlineCountsBackgroundTimeAndFailsClosedOnBackwardTime() {
        let clock = InactivityPolicy()
        clock.arm(nowMillis: 100, timeoutMillis: 1000)
        XCTAssertFalse(clock.expired(nowMillis: 1099))
        XCTAssertTrue(clock.expired(nowMillis: 1100))
        XCTAssertTrue(clock.expired(nowMillis: 99))
        clock.cancel()
        XCTAssertFalse(clock.expired(nowMillis: 9999))
    }
}
