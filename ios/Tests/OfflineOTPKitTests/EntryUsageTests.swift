import XCTest
@testable import OfflineOTPKit

/// The usage rules: the stamps **Last used** and **Most used** read, and the HOTP counter.
///
/// These mirror `EntryUsage` in `:core`, so an entry copied on the phone and the same entry copied
/// here end up with the same stamp, the same counter and the same ceiling.
final class EntryUsageTests: XCTestCase {
    private func entry(_ type: String, counter: Int64 = 0) throws -> Entry {
        var fields: [String: JSONValue] = [
            "secret": .string("JBSWY3DPEHPK3PXP"),
            "issuer": .string("Issuer"),
            "label": .string("Label"),
            "digits": .int(6),
            "type": .string(type),
            "algorithm": .string("SHA1"),
            "thumbnail": .string(""),
            "last_used": .int(0),
            "used_frequency": .int(0),
            "tags": .array([]),
        ]
        if type == "HOTP" { fields["counter"] = .int(counter) }
        if type == "TOTP" { fields["period"] = .int(30) }
        return try Entry.parse(.object(fields))
    }

    func testAUseStampsTheEntryAndCountsIt() throws {
        var built = try entry("TOTP")
        EntryUsage.recordUse(&built, now: 1_700_000_000_000)
        XCTAssertEqual(built.lastUsed, 1_700_000_000_000)
        XCTAssertEqual(built.usedFrequency, 1)
        EntryUsage.recordUse(&built, now: 1_700_000_001_000)
        XCTAssertEqual(built.usedFrequency, 2)
        XCTAssertEqual(built.lastUsed, 1_700_000_001_000)
    }

    func testAnImportedEntryIsStampedButNotCounted() throws {
        // A new entry leads a "Last used" list without pretending to have been used.
        var built = try entry("TOTP")
        EntryUsage.markImported(&built, now: 1_700_000_000_000)
        XCTAssertEqual(built.lastUsed, 1_700_000_000_000)
        XCTAssertEqual(built.usedFrequency, 0)
    }

    func testACounterStepsByExactlyOne() throws {
        var built = try entry("HOTP", counter: 0)
        XCTAssertTrue(EntryUsage.advanceHotp(&built))
        XCTAssertEqual(built.counter, 1)
        XCTAssertTrue(EntryUsage.advanceHotp(&built))
        XCTAssertEqual(built.counter, 2)
        // The code follows the stored counter, so stepping changes it.
        XCTAssertEqual(try built.code(unixTime: 0), try entry("HOTP", counter: 2).code(unixTime: 0))
    }

    func testOnlyACounterEntryCanBeStepped() throws {
        var timeBased = try entry("TOTP")
        XCTAssertFalse(EntryUsage.advanceHotp(&timeBased))
        XCTAssertEqual(timeBased.fields["counter"], nil)
    }

    func testTheCounterStopsOneBelowTheCeiling() throws {
        // `EntryUsage.MAX_COUNTER` is `Long.MAX_VALUE - 1`: the last step that still leaves a
        // positive counter behind. Both other clients stop here rather than overflow.
        XCTAssertEqual(EntryUsage.maxCounter, Int64.max - 1)
        var built = try entry("HOTP", counter: EntryUsage.maxCounter - 1)
        XCTAssertTrue(EntryUsage.advanceHotp(&built))
        XCTAssertEqual(built.counter, EntryUsage.maxCounter)
        XCTAssertFalse(EntryUsage.advanceHotp(&built))
        XCTAssertEqual(built.counter, EntryUsage.maxCounter)
    }

    func testTheStoredStampSurvivesASaveAndReopen() throws {
        var built = try entry("TOTP")
        EntryUsage.recordUse(&built, now: 1_700_000_000_000)
        let document = try VaultDocument.encode([built])
        let reopened = try VaultDocument.decode(document)
        XCTAssertEqual(reopened.first?.lastUsed, 1_700_000_000_000)
        XCTAssertEqual(reopened.first?.usedFrequency, 1)
    }
}
