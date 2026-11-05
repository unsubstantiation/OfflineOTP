import XCTest
@testable import OfflineOTP

/// The display rules: how a code is grouped and how long a step has left.
final class CodeFormatTests: XCTestCase {
    func testGroupingInsertsSpacesWithoutChangingTheCode() {
        XCTAssertEqual(CodeFormat.grouped("324550"), "324 550")
        XCTAssertEqual(CodeFormat.grouped("18948850"), "189 488 50")
        XCTAssertEqual(CodeFormat.grouped("12345"), "12 345")
        XCTAssertEqual(CodeFormat.grouped("123"), "123")
        XCTAssertEqual(CodeFormat.grouped("2KM2P"), "2KM 2P")
        XCTAssertEqual(CodeFormat.grouped("693ae2"), "693 ae2")
    }

    func testGroupingNeverLosesOrReordersCharacters() {
        for code in ["324550", "2KM2P", "693ae2", "1", "", "123456789"] {
            XCTAssertEqual(CodeFormat.grouped(code).replacingOccurrences(of: " ", with: ""), code)
        }
    }

    func testAStepIsCountedDownAndRestarts() {
        // 1700000000 is a multiple of 10, so a 30-second TOTP step has all 30 seconds left.
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertEqual(CodeFormat.secondsRemaining(kind: "TOTP", period: 30, now: start), 30)
        XCTAssertEqual(CodeFormat.secondsRemaining(kind: "TOTP", period: 30,
            now: start.addingTimeInterval(29)), 1)
        XCTAssertEqual(CodeFormat.secondsRemaining(kind: "TOTP", period: 30,
            now: start.addingTimeInterval(30)), 30)
        XCTAssertEqual(CodeFormat.fractionRemaining(kind: "TOTP", period: 30, now: start), 1)
        XCTAssertEqual(CodeFormat.fractionRemaining(kind: "TOTP", period: 30,
            now: start.addingTimeInterval(15)), 0.5)
    }

    func testMOTPRollsOverEveryTenSecondsWhateverTheStoredPeriod() {
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertEqual(CodeFormat.secondsRemaining(kind: "MOTP", period: 30, now: start), 10)
        XCTAssertEqual(CodeFormat.secondsRemaining(kind: "MOTP", period: 30,
            now: start.addingTimeInterval(9)), 1)
        XCTAssertEqual(CodeFormat.secondsRemaining(kind: "MOTP", period: 60, now: start), 10)
    }

    func testHOTPHasNoCountdown() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertNil(CodeFormat.secondsRemaining(kind: "HOTP", period: 30, now: now))
        XCTAssertEqual(CodeFormat.fractionRemaining(kind: "HOTP", period: 30, now: now), 0)
        XCTAssertNil(CodeFormat.stepSeconds(kind: "HOTP", period: 30))
    }

    func testANoteHasNoCountdownEither() {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertNil(CodeFormat.secondsRemaining(kind: "NOTE", period: 30, now: now))
        XCTAssertNil(CodeFormat.secondsRemaining(kind: "TOTP", period: 0, now: now))
    }
}
