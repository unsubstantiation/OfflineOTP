import XCTest
@testable import OfflineOTPKit

/// `otpauth://` in both directions: the rules `OtpUriParser` and `Entry.toUriString` apply on the
/// other two clients, and the round trip that has to hold between them.
final class OtpUriTests: XCTestCase {
    private let seed = "JBSWY3DPEHPK3PXP"

    // MARK: - Parsing

    func testALinkFromAnotherAppBecomesAnEntry() throws {
        let uri = try OtpUri.parse("otpauth://totp/Example:alice@example.com?"
                                   + "secret=\(seed)&issuer=Example")
        XCTAssertEqual(uri.kind, .totp)
        XCTAssertEqual(uri.issuer, "Example")
        // The label loses the issuer prefix, so the issuer is not shown twice.
        XCTAssertEqual(uri.label, "alice@example.com")
        XCTAssertEqual(uri.period, 30)
        XCTAssertEqual(uri.digits, 6)
        XCTAssertEqual(uri.algorithm, .sha1)
        XCTAssertEqual(uri.tags, [])

        let entry = try uri.entry()
        XCTAssertEqual(entry.kind, .totp)
        XCTAssertEqual(entry.secretBase32, seed)
        XCTAssertEqual(entry.issuer, "Example")
        XCTAssertEqual(entry.label, "alice@example.com")
        XCTAssertEqual(entry.thumbnail, EntryDraft.defaultThumbnailName)
        XCTAssertEqual(try entry.code(unixTime: 1700000000), "324550")
    }

    func testAHotpLinkCarriesItsCounter() throws {
        let uri = try OtpUri.parse("otpauth://hotp/Acme:seed?"
                                   + "secret=\(seed)&issuer=Acme&counter=8&digits=8"
                                   + "&algorithm=SHA256")
        XCTAssertEqual(uri.kind, .hotp)
        XCTAssertEqual(uri.counter, 8)
        XCTAssertEqual(uri.digits, 8)
        XCTAssertEqual(uri.algorithm, .sha256)
        let entry = try uri.entry()
        XCTAssertEqual(entry.counter, 8)
        XCTAssertEqual(entry.digits, 8)
        XCTAssertEqual(entry.algorithm, .sha256)
    }

    func testASteamLinkDefaultsToFiveDigits() throws {
        let uri = try OtpUri.parse("otpauth://steam/Steam:player?secret=\(seed)")
        XCTAssertEqual(uri.kind, .steam)
        XCTAssertEqual(uri.digits, 5)
        XCTAssertEqual(uri.label, "player")
    }

    func testASteamLinkCannotChangeItsDigitCount() {
        // Steam tokens are five characters; a six-digit Steam link is a mistake, not a setting.
        XCTAssertThrowsError(try OtpUri.parse("otpauth://steam/x?secret=\(seed)&digits=6")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidParameters)
        }
    }

    func testAnMotpLinkCarriesHexadecimalText() throws {
        let hex = "4c6f72656d697073756d"
        let uri = try OtpUri.parse("otpauth://motp/x?secret=\(hex)")
        XCTAssertEqual(uri.kind, .motp)
        XCTAssertEqual(uri.secret, Array(hex.utf8))
        XCTAssertEqual(try uri.entry().rawSecretText, hex)
    }

    func testAnMotpLinkRejectsAnythingButHex() {
        XCTAssertThrowsError(try OtpUri.parse("otpauth://motp/x?secret=\(seed)")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidSecret)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://motp/x?secret=abc")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidSecret)
        }
    }

    func testAQueryValueDecodesOnceAndPlusesBecomeSpaces() throws {
        let uri = try OtpUri.parse("otpauth://totp/a%20b?secret=\(seed)&issuer=Acme+Inc")
        XCTAssertEqual(uri.issuer, "Acme Inc")
        XCTAssertEqual(uri.label, "a b")
        // A literal space in the label is escaped before parsing rather than refused.
        XCTAssertEqual(try OtpUri.parse("otpauth://totp/a b?secret=\(seed)").label, "a b")
        // A label's `+` stays a plus: only the query treats it as a space.
        XCTAssertEqual(try OtpUri.parse("otpauth://totp/a+b?secret=\(seed)").label, "a+b")
    }

    func testABrokenEscapeIsRefusedRatherThanGuessed() {
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/a%zz?secret=\(seed)")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidUri)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/a%C3?secret=\(seed)")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidUri)
        }
    }

    func testDuplicatesAreRefusedButTagsRepeat() throws {
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/x?secret=\(seed)&secret=\(seed)")) {
            XCTAssertEqual($0 as? OtpParseError, .duplicateParameter)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/x?secret=\(seed)&period=30&period=60")) {
            XCTAssertEqual($0 as? OtpParseError, .duplicateParameter)
        }
        let uri = try OtpUri.parse("otpauth://totp/x?secret=\(seed)&tags=work&tags=personal")
        XCTAssertEqual(uri.tags, ["work", "personal"])
        XCTAssertEqual(try uri.entry().tags, ["work", "personal"])
    }

    func testTooManyTagsIsAnError() {
        var pairs: [String] = []
        for index in 0...InputPolicy.maxTags { pairs.append("tags=t\(index)") }
        let text = "otpauth://totp/x?secret=\(seed)&" + pairs.joined(separator: "&")
        XCTAssertThrowsError(try OtpUri.parse(text)) {
            XCTAssertEqual($0 as? OtpParseError, .tooManyTags)
        }
    }

    func testAnUnknownParameterIsIgnored() throws {
        // Forward compatibility: a link from a newer app still imports.
        let uri = try OtpUri.parse("otpauth://totp/x?secret=\(seed)&image=https%3A%2F%2Fx%2Fy.png")
        XCTAssertEqual(uri.label, "x")
    }

    func testTheLinkShapesThatAreNotOneAccountAreRefused() {
        XCTAssertThrowsError(try OtpUri.parse("otpauth://yandex/x?secret=\(seed)")) {
            XCTAssertEqual($0 as? OtpParseError, .unsupportedType)
        }
        XCTAssertThrowsError(try OtpUri.parse("https://example.com/x?secret=\(seed)")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidUri)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/x?secret=\(seed)#fragment")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidUri)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth:/totp/x?secret=\(seed)")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidUri)
        }
        XCTAssertThrowsError(try OtpUri.parse("")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidUri)
        }
        // A query delimiter with nothing behind it is a malformed query, not a missing secret.
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/x?")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidUri)
        }
        // The scheme is case-sensitive, as `java.net.URI` makes it on the JVM.
        XCTAssertThrowsError(try OtpUri.parse("OTPAUTH://totp/x?secret=\(seed)")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidUri)
        }
        // The host is not.
        XCTAssertEqual(try OtpUri.parse("otpauth://TOTP/x?secret=\(seed)").kind, .totp)
    }

    func testAnOversizedLinkIsRefusedBeforeParsing() {
        let text = "otpauth://totp/" + String(repeating: "a", count: InputPolicy.maxURIChars)
        XCTAssertThrowsError(try OtpUri.parse(text)) {
            XCTAssertEqual($0 as? OtpParseError, .inputTooLong)
        }
    }

    func testMissingAndInvalidParameters() {
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/x")) {
            XCTAssertEqual($0 as? OtpParseError, .missingSecret)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://hotp/x?secret=\(seed)")) {
            XCTAssertEqual($0 as? OtpParseError, .missingCounter)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/x?secret=not-base32!")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidSecret)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/x?secret=JBSWY3DPEHPK3PX")) {
            // A 15-character seed is not canonical Base32.
            XCTAssertEqual($0 as? OtpParseError, .invalidSecret)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/x?secret=\(seed)&period=0")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidParameters)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/x?secret=\(seed)&period=86401")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidParameters)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/x?secret=\(seed)&digits=0")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidParameters)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://hotp/x?secret=\(seed)&counter=-1")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidParameters)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://hotp/x?secret=\(seed)&counter=9223372036854775807")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidParameters)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/x?secret=\(seed)&period=thirty")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidNumber)
        }
        XCTAssertThrowsError(try OtpUri.parse("otpauth://totp/x?secret=\(seed)&algorithm=MD5")) {
            XCTAssertEqual($0 as? OtpParseError, .invalidAlgorithm)
        }
    }

    func testAnErrorCarriesNoInputText() {
        // A refused link may contain a secret; the sentence shown next to it may not.
        for error in [OtpParseError.invalidUri, .invalidSecret, .unsupportedType,
                      .duplicateParameter, .missingCounter, .invalidParameters,
                      .invalidAlgorithm, .inputTooLong, .textTooLong, .tooManyTags,
                      .missingSecret, .invalidNumber] {
            XCTAssertFalse(error.message.contains(seed), "\(error) leaks the input")
            XCTAssertFalse(error.message.isEmpty)
        }
    }

    // MARK: - Serializing

    func testAnEntrySerializesInTheOrderTheOtherClientsUse() throws {
        let entry = try OtpUri.parse("otpauth://totp/Example:alice@example.com?"
                                     + "secret=\(seed)&issuer=Example&tags=work").entry()
        // `@` is not unreserved, so it is escaped; a space would be %20 and never `+`.
        XCTAssertEqual(OtpUri.uri(for: entry),
                       "otpauth://totp/alice%40example.com?secret=\(seed)"
                       + "&issuer=Example&tags=work")
    }

    func testDefaultsAreLeftOutAndDeviationsAreWritten() throws {
        let entry = try OtpUri.parse("otpauth://hotp/Acme:seed?secret=\(seed)&issuer=Acme"
                                     + "&counter=3&digits=8&algorithm=SHA256&period=60").entry()
        XCTAssertEqual(OtpUri.uri(for: entry),
                       "otpauth://hotp/seed?secret=\(seed)&issuer=Acme&counter=3&digits=8"
                       + "&algorithm=SHA256")

        // A period of 30 is the default and is not written; 60 for a TOTP entry is.
        let totp = try OtpUri.parse("otpauth://totp/x?secret=\(seed)&issuer=Acme&period=60").entry()
        XCTAssertEqual(OtpUri.uri(for: totp),
                       "otpauth://totp/x?secret=\(seed)&issuer=Acme&period=60")
    }

    func testASpaceInALabelIsPercentEncoded() throws {
        let entry = try OtpUri.parse("otpauth://totp/a+b?secret=\(seed)&issuer=Acme+Inc").entry()
        XCTAssertEqual(OtpUri.uri(for: entry),
                       "otpauth://totp/a%2Bb?secret=\(seed)&issuer=Acme%20Inc")
    }

    func testTheRoundTripHoldsForEveryOtpKind() throws {
        let links = [
            "otpauth://totp/Example:alice?secret=\(seed)&issuer=Example",
            "otpauth://totp/x?secret=\(seed)&digits=8&period=60&algorithm=SHA512",
            "otpauth://hotp/y?secret=\(seed)&counter=42&digits=7&algorithm=SHA256",
            "otpauth://steam/z?secret=\(seed)",
            "otpauth://motp/w?secret=4c6f72656d697073756d",
        ]
        for link in links {
            let first = try OtpUri.parse(link)
            let text = try XCTUnwrap(OtpUri.uri(for: try first.entry()))
            let second = try OtpUri.parse(text)
            XCTAssertEqual(second.kind, first.kind, link)
            XCTAssertEqual(second.secret, first.secret, link)
            XCTAssertEqual(second.issuer, first.issuer, link)
            XCTAssertEqual(second.label, first.label, link)
            XCTAssertEqual(second.digits, first.digits, link)
            XCTAssertEqual(second.period, first.period, link)
            XCTAssertEqual(second.counter, first.counter, link)
            XCTAssertEqual(second.algorithm, first.algorithm, link)
        }
    }

    func testOnlyOtpKindsHaveALink() throws {
        let note = try Entry.parse(.object([
            "secret": .string(Base32.encode(Array("hello".utf8))),
            "issuer": .string(""),
            "label": .string("note"),
            "digits": .int(6),
            "type": .string("NOTE"),
            "algorithm": .string("SHA1"),
            "thumbnail": .string("Default"),
            "last_used": .int(0),
            "used_frequency": .int(0),
        ]))
        XCTAssertEqual(note.kind, .note)
        XCTAssertNil(OtpUri.uri(for: note))
    }

    // MARK: - Classification of a scanned payload

    func testAPayloadIsClassifiedBeforeItIsParsed() throws {
        guard case .otp(let uri) = QrPayload.classify("otpauth://totp/x?secret=\(seed)") else {
            return XCTFail("a single link should classify as one account")
        }
        XCTAssertEqual(uri.label, "x")
        // The payload is classified as scanned: a stray character is the parser's problem, exactly as
        // on the other clients, rather than something this client quietly trims away.
        XCTAssertEqual(QrPayload.classify(" otpauth://totp/x?secret=\(seed)"), .invalid("INVALID_URI"))
        XCTAssertEqual(QrPayload.classify("otpauth-migration://offline?data=abc".uppercased()),
                       .migration)
        XCTAssertEqual(QrPayload.classify("otpauth-migration://offline?data=abc"), .migration)
        XCTAssertEqual(QrPayload.classify(nil), .unrecognized)
        XCTAssertEqual(QrPayload.classify("   "), .unrecognized)
        XCTAssertEqual(QrPayload.classify("https://example.com"), .invalid("INVALID_URI"))
        XCTAssertEqual(QrPayload.classify("otpauth://hotp/x?secret=\(seed)"),
                       .invalid("MISSING_COUNTER"))
    }
}
