import XCTest
@testable import OfflineOTP

/// The display rules: how a code is grouped and how long a step has left.
final class CodeFormatTests: XCTestCase {
    func testGroupingInsertsSpacesWithoutChangingTheCode() {
        XCTAssertEqual(CodeFormat.grouped("324550"), "324 550")
        XCTAssertEqual(CodeFormat.grouped("18948850"), "189 488 50")
        // Groups from the left, so a five-character code reads "123 45" — the spaces never move a
        // character, they only make the code easier to read out.
        XCTAssertEqual(CodeFormat.grouped("12345"), "123 45")
        XCTAssertEqual(CodeFormat.grouped("123"), "123")
        XCTAssertEqual(CodeFormat.grouped("2KM2P"), "2KM 2P")
        XCTAssertEqual(CodeFormat.grouped("693ae2"), "693 ae2")
    }

    func testGroupingNeverLosesOrReordersCharacters() {
        for code in ["324550", "2KM2P", "693ae2", "1", "", "123456789"] {
            XCTAssertEqual(CodeFormat.grouped(code).replacingOccurrences(of: " ", with: ""), code)
        }
    }

    func testACodeIsSpokenOneCharacterAtATime() {
        // A six-digit code read as a number is unusable; VoiceOver is given the digits separately.
        XCTAssertEqual(CodeFormat.spoken("324550"), "3 2 4 5 5 0")
        XCTAssertEqual(CodeFormat.spoken("18948850"), "1 8 9 4 8 8 5 0")
        // Steam and mOTP codes mix letters and digits, and are spoken the same way.
        XCTAssertEqual(CodeFormat.spoken("2KM2P"), "2 K M 2 P")
        XCTAssertEqual(CodeFormat.spoken("693ae2"), "6 9 3 a e 2")
        XCTAssertEqual(CodeFormat.spoken(""), "")
        // The characters never change: only the pauses a screen reader already reads are added.
        for code in ["324550", "2KM2P", "693ae2", ""] {
            XCTAssertEqual(CodeFormat.spoken(code).replacingOccurrences(of: " ", with: ""), code)
        }
    }

    func testTheTimeLeftIsAWordingWithASingularAndNoZeroCase() {
        XCTAssertEqual(CodeFormat.remainingDescription(12), "12 seconds left")
        XCTAssertEqual(CodeFormat.remainingDescription(1), "1 second left")
        XCTAssertEqual(CodeFormat.remainingDescription(0), "0 seconds left")
        XCTAssertEqual(CodeFormat.remainingDescription(nil), "")
        // The row's spoken value is the code, then the time left, separated by a pause.
        XCTAssertEqual(CodeFormat.spoken("324550") + ", "
                       + CodeFormat.remainingDescription(
                            CodeFormat.secondsRemaining(kind: "TOTP", period: 30,
                                                        now: Date(timeIntervalSince1970: 1_700_000_010))),
                       "3 2 4 5 5 0, 30 seconds left")
    }

    func testAStepIsCountedDownAndRestarts() {
        // 1700000010 is the start of a 30-second step (and of a ten-second one), so the countdown
        // begins at a full period. 1700000000 is not: it is 20 seconds into a step.
        let start = Date(timeIntervalSince1970: 1_700_000_010)
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
