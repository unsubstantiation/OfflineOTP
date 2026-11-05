import XCTest
@testable import OfflineOTPKit

/// Turning a form into an entry.
///
/// The point of these tests is the stored bytes, not the object: a hand-typed entry has to be
/// byte-identical to one the phone or the desktop client would have written for the same input, and
/// a value they refuse has to be refused here too.
final class EntryDraftTests: XCTestCase {
    private let now: Int64 = 1_700_000_000_000

    private func create(_ kind: EntryKind = .totp, issuer: String = "Issuer",
                        label: String = "alice", secret: String = "JBSWY3DPEHPK3PXP",
                        digits: String = "6", timing: String = "30",
                        algorithm: OtpHashAlgorithm = .sha1, pin: String = "", tags: [String] = [],
                        newEntry: Bool = true) throws -> Entry {
        try EntryDraft.create(kind: kind, issuer: issuer, label: label, secret: secret,
                             digits: digits, timing: timing, algorithm: algorithm, pin: pin,
                             tags: tags, newEntry: newEntry, now: now)
    }

    func testANewTimeBasedEntryIsWrittenInTheSharedFieldOrder() throws {
        let built = try create()
        XCTAssertEqual(built.compact(),
                       "{\"secret\":\"JBSWY3DPEHPK3PXP\",\"issuer\":\"Issuer\",\"label\":\"alice\","
                       + "\"digits\":6,\"type\":\"TOTP\",\"algorithm\":\"SHA1\",\"thumbnail\":\"Default\","
                       + "\"last_used\":1700000000000,\"used_frequency\":0,\"period\":30,\"tags\":[]}")
        // A new entry is stamped as used, so it leads a "Last used" list without being counted as
        // used — `EntryUsage.markImported`.
        XCTAssertEqual(built.lastUsed, now)
        XCTAssertEqual(built.usedFrequency, 0)
        XCTAssertEqual(built.detailsVisible, false)
    }

    func testASecretPastedAcrossLinesIsAccepted() throws {
        let built = try create(secret: "JBSW Y3DP\nEHPK 3PXP")
        XCTAssertEqual(built.secretBase32, "JBSWY3DPEHPK3PXP")
        XCTAssertEqual(try built.code(unixTime: 1_700_000_000), "324550")
    }

    func testACounterBasedEntryStoresItsCounterAndNotAPeriod() throws {
        // The fixed HOTP vector: `KRSXG5A=`, 8 digits, SHA-256, counter 9.
        let built = try create(.hotp, secret: "KRSXG5A=", digits: "8", timing: "9",
                              algorithm: .sha256)
        XCTAssertEqual(built.counter, 9)
        XCTAssertEqual(built.fields["period"], nil)
        XCTAssertEqual(built.digits, 8)
        XCTAssertEqual(try built.code(unixTime: 0), "24253975")
    }

    func testSteamOmitsTheDefaultPeriodAndKeepsANonDefaultOne() throws {
        // Only a non-default Steam period is written, so default Steam documents stay byte-identical
        // to the ones the other clients write.
        let standard = try create(.steam, digits: "5", timing: "30")
        XCTAssertEqual(standard.fields["period"], nil)
        XCTAssertEqual(standard.digits, 5)
        let custom = try create(.steam, digits: "5", timing: "60")
        XCTAssertEqual(custom.period, 60)
        XCTAssertEqual(custom.fields["period"], .int(60))
    }

    func testSteamRefusesAnyDigitCountButFive() throws {
        // `Entry.validate` requires exactly five; the loader is what refuses it here.
        XCTAssertThrowsError(try create(.steam, digits: "6", timing: "30")) { error in
            XCTAssertEqual(error as? EntryError, .invalidParameters)
        }
    }

    func testAnMOTPEntryStoresTheHexSeedAsTextAndOnlyWritesANonEmptyPin() throws {
        let seed = "4c6f6e67206c697665207468652070696e21"
        let withPin = try create(.motp, secret: seed, digits: "6", timing: "30", pin: "1234")
        XCTAssertEqual(withPin.fields["pin"], .string("1234"))
        XCTAssertEqual(withPin.secretBase32, Base32.encode(Array(seed.utf8)))
        XCTAssertEqual(withPin.rawSecretText, seed)
        XCTAssertEqual(try withPin.code(unixTime: 1_700_000_000), "693ae2")

        let withoutPin = try create(.motp, secret: seed, digits: "6", timing: "30")
        XCTAssertEqual(withoutPin.fields["pin"], nil)
        XCTAssertEqual(try withoutPin.code(unixTime: 1_700_000_000), Entry.motpNoPinCode)
    }

    func testAnMOTPSeedThatIsNotHexIsRefused() throws {
        XCTAssertThrowsError(try create(.motp, secret: "zzzz", digits: "6", timing: "30")) {
            XCTAssertEqual($0 as? EntryDraftError, .invalidSecret)
        }
    }

    func testANoteKeepsEveryCharacterAndNeedsATitle() throws {
        let text = "line one\nline two — ünïcode"
        let built = try create(.note, label: "Recovery", secret: text, digits: "", timing: "")
        XCTAssertEqual(built.noteText, text)
        XCTAssertEqual(built.secretBase32, Base32.encode(Array(text.utf8)))
        XCTAssertEqual(built.fields["period"], nil)
        XCTAssertThrowsError(try create(.note, label: "   ", secret: text, digits: "", timing: "")) {
            XCTAssertEqual($0 as? EntryDraftError, .titleRequired)
        }
    }

    func testTheStructuredKindsCannotBeTypedIn() throws {
        for kind in [EntryKind.password, .passwordArchive, .email, .sshKey] {
            XCTAssertThrowsError(try create(kind, secret: Base32.encode(Array("material".utf8)))) {
                XCTAssertEqual($0 as? EntryDraftError, .kindNotOfferable, "\(kind)")
            }
            XCTAssertFalse(EntryDraft.offerableKinds.contains(kind))
        }
        XCTAssertEqual(EntryDraft.offerableKinds, [.totp, .hotp, .motp, .steam, .note])
    }

    func testASecretThatIsNotBase32IsRefusedRatherThanStored() throws {
        XCTAssertThrowsError(try create(secret: "not a base32 secret!")) {
            XCTAssertEqual($0 as? EntryDraftError, .invalidSecret)
        }
        XCTAssertThrowsError(try create(secret: "")) {
            XCTAssertEqual($0 as? EntryDraftError, .invalidSecret)
        }
    }

    func testATypedNumberThatIsNotOneIsRefusedWithTheSharedWording() throws {
        XCTAssertThrowsError(try create(digits: "six")) {
            XCTAssertEqual(($0 as? EntryDraftError)?.message, "Digits must be a number")
        }
        XCTAssertThrowsError(try create(timing: "soon")) {
            XCTAssertEqual(($0 as? EntryDraftError)?.message, "Period must be a number")
        }
        XCTAssertThrowsError(try create(.hotp, timing: "later")) {
            XCTAssertEqual(($0 as? EntryDraftError)?.message, "HOTP counter must be a number")
        }
        // The boundaries the model enforces are reported as parameters, not as a crash.
        XCTAssertThrowsError(try create(digits: "10")) {
            XCTAssertEqual($0 as? EntryError, .invalidParameters)
        }
        XCTAssertThrowsError(try create(timing: "0")) {
            XCTAssertEqual($0 as? EntryError, .invalidParameters)
        }
    }

    func testTagsAreTrimmedDeduplicatedAndBounded() throws {
        let built = try create(tags: [" work ", "home", "work", "", "  "])
        XCTAssertEqual(built.tags, ["work", "home"])
        // The shared ceiling: 32 tags of at most 128 characters.
        let tooMany = (0...InputPolicy.maxTags).map { "tag\($0)" }
        XCTAssertThrowsError(try create(tags: tooMany)) {
            XCTAssertEqual($0 as? EntryError, .tooManyTags)
        }
        let tooLong = [String(repeating: "x", count: InputPolicy.maxTagChars + 1)]
        XCTAssertThrowsError(try create(tags: tooLong))
    }

    func testAnEditKeepsTheMetadataTheFormDoesNotShow() throws {
        var existing = try create()
        existing.setDetailsVisible(true)
        existing.setLastUsed(1_600_000_000_000)
        existing.setUsedFrequency(7)
        let replacement = EntryDraft.preservingMetadata(of: existing,
                                                        in: try create(label: "carol"))
        XCTAssertEqual(replacement.label, "carol")
        XCTAssertEqual(replacement.detailsVisible, true)
        XCTAssertEqual(replacement.lastUsed, 1_600_000_000_000)
        XCTAssertEqual(replacement.usedFrequency, 7)
    }

    func testAnEntryBuiltHereSurvivesADocumentRoundTrip() throws {
        let built = [try create(), try create(.motp, secret: "4c6f6e67", digits: "6",
                                             timing: "30", pin: "1234"),
                     try create(.note, label: "Note", secret: "text", digits: "", timing: "")]
        let document = try VaultDocument.encode(built)
        let reopened = try VaultDocument.decode(document)
        XCTAssertEqual(reopened.map { $0.compact() }, built.map { $0.compact() })
    }
}
