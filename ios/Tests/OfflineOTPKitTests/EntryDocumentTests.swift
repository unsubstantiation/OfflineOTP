import XCTest
@testable import OfflineOTPKit

/// The entry document rules: which field order is written, which input is refused, and what happens
/// to data this build does not understand.
final class EntryDocumentTests: XCTestCase {
    private func entry(_ fields: [String: JSONValue]) throws -> Entry {
        try Entry.parse(.object(fields))
    }

    private func totpFields() -> [String: JSONValue] {
        [
            "secret": .string("JBSWY3DPEHPK3PXP"),
            "issuer": .string("Issuer"),
            "label": .string("label"),
            "digits": .int(6),
            "type": .string("TOTP"),
            "algorithm": .string("SHA1"),
            "thumbnail": .string("Default"),
            "last_used": .int(0),
            "used_frequency": .int(0),
            "period": .int(30),
            "tags": .array([]),
        ]
    }

    func testTheStoredFieldOrderIsReproduced() throws {
        let text = try entry(totpFields()).compact()
        XCTAssertEqual(text,
                       "{\"secret\":\"JBSWY3DPEHPK3PXP\",\"issuer\":\"Issuer\",\"label\":\"label\","
                       + "\"digits\":6,\"type\":\"TOTP\",\"algorithm\":\"SHA1\","
                       + "\"thumbnail\":\"Default\",\"last_used\":0,\"used_frequency\":0,"
                       + "\"period\":30,\"tags\":[]}")
    }

    func testFieldsThisBuildDoesNotKnowAreKeptAndWrittenAfterTheKnownOnes() throws {
        var fields = totpFields()
        fields["future_field"] = .string("kept")
        let text = try entry(fields).compact()
        XCTAssertTrue(text.hasSuffix(",\"future_field\":\"kept\"}"), text)
    }

    func testAnEntryKindThisBuildCannotDisplayIsStillReadableAndWritable() throws {
        // An email account is preserved, not modelled: the app cannot show it in v1, but a save must
        // not delete it from a vault the phone wrote.
        let fields: [String: JSONValue] = [
            "secret": .string("MZXW6==="),
            "issuer": .string(""),
            "label": .string("Mail"),
            "digits": .int(6),
            "type": .string("EMAIL"),
            "algorithm": .string("SHA1"),
            "thumbnail": .string("Default"),
            "last_used": .int(0),
            "used_frequency": .int(0),
            "tags": .array([.string("work")]),
        ]
        let parsed = try entry(fields)
        XCTAssertEqual(parsed.kind, .email)
        XCTAssertEqual(parsed.compact(), try entry(fields).compact())
        XCTAssertThrowsError(try parsed.code(unixTime: 0)) { error in
            XCTAssertEqual(error as? EntryError, .notAnOtpEntry)
        }
    }

    func testAnUnknownTypeIsRejectedBecauseNoOtherClientCouldOpenItEither() {
        var fields = totpFields()
        fields["type"] = .string("CARRIER_PIGEON")
        XCTAssertThrowsError(try entry(fields)) { error in
            XCTAssertEqual(error as? EntryError, .unsupportedKind)
        }
    }

    func testAMissingTypeDefaultsToTOTP() throws {
        var fields = totpFields()
        fields.removeValue(forKey: "type")
        XCTAssertEqual(try entry(fields).kind, .totp)
    }

    func testAMissingSeedIsRejected() {
        var fields = totpFields()
        fields.removeValue(forKey: "secret")
        XCTAssertThrowsError(try entry(fields)) { error in
            XCTAssertEqual(error as? EntryError, .missingSecret)
        }
    }

    func testASeedThatIsNotBase32IsRejected() {
        var fields = totpFields()
        fields["secret"] = .string("not base32!")
        XCTAssertThrowsError(try entry(fields)) { error in
            XCTAssertEqual(error as? EntryError, .invalidSecret)
        }
    }

    func testAnEmptySeedIsRejected() {
        var fields = totpFields()
        fields["secret"] = .string("")
        XCTAssertThrowsError(try entry(fields)) { error in
            XCTAssertEqual(error as? EntryError, .invalidSecret)
        }
    }

    func testDigitAndPeriodBoundsAreEnforced() {
        for (digits, period) in [(0, 30), (10, 30), (6, 0), (6, 86401)] {
            var fields = totpFields()
            fields["digits"] = .int(Int64(digits))
            fields["period"] = .int(Int64(period))
            XCTAssertThrowsError(try entry(fields), "digits \(digits) period \(period) must be refused") { error in
                XCTAssertEqual(error as? EntryError, .invalidParameters)
            }
        }
    }

    func testHOTPRequiresACounter() {
        var fields = totpFields()
        fields["type"] = .string("HOTP")
        fields.removeValue(forKey: "counter")
        fields.removeValue(forKey: "period")
        XCTAssertThrowsError(try entry(fields)) { error in
            XCTAssertEqual(error as? EntryError, .missingCounter)
        }
    }

    func testSteamRequiresFiveDigits() {
        var fields = totpFields()
        fields["type"] = .string("STEAM")
        fields["digits"] = .int(6)
        XCTAssertThrowsError(try entry(fields)) { error in
            XCTAssertEqual(error as? EntryError, .invalidParameters)
        }
    }

    func testTooManyTagsAreRefused() {
        var fields = totpFields()
        fields["tags"] = .array((0...InputPolicy.maxTags).map { .string("tag\($0)") })
        XCTAssertThrowsError(try entry(fields)) { error in
            XCTAssertEqual(error as? EntryError, .tooManyTags)
        }
    }

    func testAnOversizedOtpSeedIsRefusedButAFramedSshKeyIsNot() {
        var fields = totpFields()
        fields["secret"] = .string(Base32.encode([UInt8](repeating: 7, count: InputPolicy.maxSecretBytes + 1)))
        XCTAssertThrowsError(try entry(fields)) { error in
            XCTAssertEqual(error as? EntryError, .invalidSecret)
        }

        var ssh = fields
        ssh["type"] = .string("SSH_KEY")
        ssh["label"] = .string("key")
        ssh["secret"] = .string(Base32.encode([UInt8](repeating: 7, count: 6000)))
        XCTAssertNoThrow(try entry(ssh))
    }

    func testANoteMustBeReadableUtf8AndWithinItsLengthBound() throws {
        let text = "Line one\nLine two \u{1F511}"
        var fields = totpFields()
        fields["type"] = .string("NOTE")
        fields["secret"] = .string(Base32.encode(Array(text.utf8)))
        XCTAssertEqual(try entry(fields).secretBytes.map { String(decoding: $0, as: UTF8.self) }, text)

        var malformed = fields
        malformed["secret"] = .string(Base32.encode([0xFF, 0xFE]))
        XCTAssertThrowsError(try entry(malformed)) { error in
            XCTAssertEqual(error as? EntryError, .invalidNote)
        }

        var overlong = fields
        overlong["secret"] = .string(Base32.encode(Array(String(repeating: "a",
            count: InputPolicy.maxNoteCharacters + 1).utf8)))
        XCTAssertThrowsError(try entry(overlong)) { error in
            XCTAssertEqual(error as? EntryError, .invalidNote)
        }
    }

    func testTheDocumentRefusesTooManyEntries() {
        let one = try! entry(totpFields())
        let text = "[" + Array(repeating: one.compact(), count: InputPolicy.maxEntries + 1)
            .joined(separator: ",") + "]"
        XCTAssertThrowsError(try VaultDocument.decode(text)) { error in
            XCTAssertEqual(error as? VaultDocumentError, .tooManyEntries)
        }
    }

    func testTheDocumentRefusesDeepJsonAndTrailingGarbage() {
        let deep = String(repeating: "[", count: InputPolicy.maxJSONDepth + 1)
            + String(repeating: "]", count: InputPolicy.maxJSONDepth + 1)
        XCTAssertThrowsError(try VaultDocument.decode(deep))
        XCTAssertThrowsError(try VaultDocument.decode("[] trailing"))
        XCTAssertThrowsError(try VaultDocument.decode("{\"not\":\"an array\"}")) { error in
            XCTAssertEqual(error as? VaultDocumentError, .notAnArray)
        }
    }

    func testTheDocumentRefusesOneBadEntryRatherThanReturningTheGoodOnes() throws {
        let good = try entry(totpFields()).compact()
        let bad = good.replacingOccurrences(of: "\"TOTP\"", with: "\"NOPE\"")
        XCTAssertThrowsError(try VaultDocument.decode("[\(good),\(bad)]")) { error in
            XCTAssertEqual(error as? VaultDocumentError, .invalidEntry)
        }
    }

    func testQuotingMatchesTheJvmWriter() {
        // Android's `org.json` escapes a slash only after `<`; a quote and a backslash always.
        XCTAssertEqual(JSONValue.quote("a/b"), "\"a/b\"")
        XCTAssertEqual(JSONValue.quote("</script>"), "\"<\\/script>\"")
        XCTAssertEqual(JSONValue.quote("say \"hi\""), "\"say \\\"hi\\\"\"")
        XCTAssertEqual(JSONValue.quote("a\\b"), "\"a\\\\b\"")
        XCTAssertEqual(JSONValue.quote("line\nbreak"), "\"line\\nbreak\"")
        XCTAssertEqual(JSONValue.quote("\u{01}"), "\"\\u0001\"")
    }

    func testAnEntryComputesItsOwnCode() throws {
        var fields = totpFields()
        fields["secret"] = .string("JBSWY3DPEHPK3PXP")
        let parsed = try entry(fields)
        XCTAssertEqual(try parsed.code(unixTime: 1700000000), "324550")
    }
}
