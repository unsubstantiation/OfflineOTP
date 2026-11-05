import Foundation
import XCTest
@testable import OfflineOTPKit

/// Google Authenticator exports: the wire format, the account mapping, and the rule that a
/// half-scanned export is never half-imported.
///
/// The parser is the port of `GoogleAuthenticatorMigration` in `:core`, and the first fixture is the
/// same independent export the JVM tests use (`lennyerik/google-authenticator-export`,
/// `src/test/proto/test_data.bin`, MIT), so both clients are proved against the same bytes rather
/// than against bytes this implementation happened to produce.
final class MigrationImportTests: XCTestCase {
    /// An independent export: four TOTP accounts, SHA1, six digits, one batch.
    private let exporterSample = "CikKCkhlbGxvId6tvu8SCHRlc3R1c2VyGgtleGFtcGxlLmNvbSABKAEwAgo8"
        + "CgpXb3JsZCEgICAgEhx0ZXN0LWdpdGh1Yi51c2VyQGV4YW1wbGUuY29tGgpnaXRodWIuY29tIAEoATACCjcKCldv"
        + "cmxkISAgICESD2Fub3RoZXJ0ZXN0dXNlchoSc29tZWV4YW1wbGVzZXJ2aWNlIAEoATACCjIKCldvcmxkISAgICIS"
        + "CmZvdXJ0aHVzZXIaEnNvbWVleGFtcGxlc2VydmljZSABKAEwAhACGAEgAA=="

    // MARK: - The wire format

    func testAnIndependentExportParses() throws {
        let payload = try MigrationPayload.parse("otpauth-migration://offline?data=\(exporterSample)")
        XCTAssertEqual(2, payload.version)
        XCTAssertEqual(1, payload.batchSize)
        XCTAssertEqual(0, payload.batchIndex)
        XCTAssertEqual(4, payload.count)
        XCTAssertEqual(["testuser", "test-github.user@example.com", "anothertestuser", "fourthuser"],
                       payload.accounts.map(\.label))
        XCTAssertEqual(["example.com", "github.com", "someexampleservice", "someexampleservice"],
                       payload.accounts.map(\.issuer))
        // "Hello!" followed by the four-byte sample tail.
        XCTAssertEqual([72, 101, 108, 108, 111, 33, 0xDE, 0xAD, 0xBE, 0xEF],
                       payload.accounts[0].secret)
        for account in payload.accounts {
            XCTAssertEqual(.totp, account.kind)
            XCTAssertEqual(.sha1, account.algorithm)
            XCTAssertEqual(6, account.digits)
            XCTAssertEqual(0, account.counter)
        }
    }

    func testTheSchemeIsRecognisedIgnoringCase() {
        XCTAssertTrue(MigrationPayload.isMigration("otpauth-migration://offline?data=AA"))
        XCTAssertTrue(MigrationPayload.isMigration("OTPAUTH-MIGRATION://offline?data=AA"))
        XCTAssertFalse(MigrationPayload.isMigration("otpauth://totp/x?secret=AA"))
        XCTAssertFalse(MigrationPayload.isMigration(nil))
        XCTAssertFalse(MigrationPayload.isMigration(""))
    }

    func testRealTokenParametersSurviveTheImport() throws {
        let seed = Array("12345678901234567890".utf8)
        let parsed = try MigrationPayload.parse(uri(payload(accounts: [
            account(seed, name: "Example:alice", issuer: "Example", algorithm: 1, digits: 2, type: 2),
            account(seed, name: "Service:bob", issuer: "", algorithm: 1, digits: 1, type: 1,
                    counter: 0),
            account(seed, name: "carol", issuer: "Other", algorithm: 2, digits: 2, type: 2),
            account(seed, name: "dan", issuer: "Other", algorithm: 3, digits: 1, type: 2),
        ])))
        XCTAssertEqual(["alice", "bob", "carol", "dan"], parsed.accounts.map(\.label))
        XCTAssertEqual(["Example", "Service", "Other", "Other"], parsed.accounts.map(\.issuer))
        XCTAssertEqual([8, 6, 8, 6], parsed.accounts.map(\.digits))
        XCTAssertEqual([.sha1, .sha1, .sha256, .sha512], parsed.accounts.map(\.algorithm))
        XCTAssertEqual([.totp, .hotp, .totp, .totp], parsed.accounts.map(\.kind))

        // The parameters reach an entry unchanged: RFC 6238's vector for this seed at 59 seconds.
        let entry = try parsed.accounts[0].entry(newEntry: false)
        XCTAssertEqual(seed, entry.secretBytes)
        XCTAssertEqual("94287082", try entry.code(unixTime: 59))
        XCTAssertEqual(8, entry.digits)
        XCTAssertEqual(30, entry.period)
    }

    func testAnIssuerPrefixIsRemovedOnlyWhenItAgrees() throws {
        let seed = Array("12345678901234567890".utf8)
        // No issuer field: the name carries "Issuer:account".
        let split = try MigrationPayload.parse(uri(payload(accounts: [
            account(seed, name: "Example:alice", issuer: "", algorithm: 1, digits: 1, type: 2),
        ])))
        XCTAssertEqual("Example", split.accounts[0].issuer)
        XCTAssertEqual("alice", split.accounts[0].label)

        // Both present and agreeing: the prefix goes.
        let agreeing = try MigrationPayload.parse(uri(payload(accounts: [
            account(seed, name: "Example:alice", issuer: "Example", algorithm: 1, digits: 1,
                    type: 2),
        ])))
        XCTAssertEqual("alice", agreeing.accounts[0].label)

        // Both present and disagreeing: the name is kept as Google wrote it.
        let disagreeing = try MigrationPayload.parse(uri(payload(accounts: [
            account(seed, name: "Example:alice", issuer: "Something else", algorithm: 1, digits: 1,
                    type: 2),
        ])))
        XCTAssertEqual("Example:alice", disagreeing.accounts[0].label)
        XCTAssertEqual("Something else", disagreeing.accounts[0].issuer)

        // A name with no colon at all gives the whole name to the label.
        let plain = try MigrationPayload.parse(uri(payload(accounts: [
            account(seed, name: "plain", issuer: "", algorithm: 1, digits: 1, type: 2),
        ])))
        XCTAssertEqual("plain", plain.accounts[0].label)
        XCTAssertEqual("", plain.accounts[0].issuer)
    }

    func testAnImportedAccountIsWrittenLikeAnyOtherEntry() throws {
        let seed = Array("12345678901234567890".utf8)
        let parsed = try MigrationPayload.parse(uri(payload(accounts: [
            account(seed, name: "GitHub:alice", issuer: "", algorithm: 2, digits: 2, type: 0),
        ])))
        let entry = try parsed.accounts[0].entry(now: 1_700_000_000_000)
        XCTAssertEqual("GitHub", entry.issuer)
        XCTAssertEqual("alice", entry.label)
        XCTAssertEqual(1_700_000_000_000, entry.lastUsed)
        XCTAssertEqual(0, entry.usedFrequency)
        XCTAssertEqual("GitHub", entry.fields["thumbnail"]?.stringValue)
        XCTAssertEqual(EntryKind.totp, entry.kind)
        XCTAssertEqual(.sha256, entry.algorithm)
        XCTAssertEqual(8, entry.digits)
        // Concealed by default, like every other entry.
        XCTAssertFalse(entry.detailsVisible)
    }

    // MARK: - Refusals

    func testAnExportThatIsNotOneIsRefused() {
        XCTAssertEqual(error(nil), .invalidURI)
        XCTAssertEqual(error(""), .invalidURI)
        XCTAssertEqual(error("otpauth-migration://online?data=AA"), .invalidURI)
        XCTAssertEqual(error("otpauth-migration://offline/path?data=AA"), .invalidURI)
        XCTAssertEqual(error("otpauth-migration://offline?data=AA#fragment"), .invalidURI)
        XCTAssertEqual(error("otpauth-migration://offline?other=AA"), .invalidURI)
        XCTAssertEqual(error("otpauth-migration://offline?data=AA&data=BB"), .invalidURI)
        XCTAssertEqual(error("otpauth-migration://offline?data"), .invalidURI)
        XCTAssertEqual(error("otpauth-migration://offline"), .invalidURI)
        XCTAssertEqual(error("otpauth-migration://offline?data="), .invalidData)
        // A payload that is not base64 at all, and one that decodes to nothing useful.
        XCTAssertEqual(error("otpauth-migration://offline?data=not*base64!"), .invalidData)
        XCTAssertEqual(error("otpauth-migration://offline?data=AAAA"), .invalidData)
        XCTAssertEqual(error("otpauth-migration://offline?data=AAA="), .invalidData)
        // The 16 KiB ceiling applies before anything is decoded.
        let huge = "otpauth-migration://offline?data=" + String(repeating: "A",
                                                               count: InputPolicy.maxURIChars)
        XCTAssertEqual(error(huge), .tooLarge)
    }

    func testAnUnsupportedFieldOrEnumIsRefusedRatherThanGuessedAt() {
        let seed = Array("12345678901234567890".utf8)
        // A version this client does not know.
        XCTAssertEqual(error(uri(payload(accounts: [account(seed)], version: 3))), .unsupported)
        // An algorithm, digit count and account type Google may add later.
        XCTAssertEqual(error(uri(payload(accounts: [account(seed, algorithm: 4)]))), .unsupported)
        XCTAssertEqual(error(uri(payload(accounts: [account(seed, digits: 4)]))), .unsupported)
        XCTAssertEqual(error(uri(payload(accounts: [account(seed, type: 3)]))), .unsupported)
        // An unknown top-level field.
        XCTAssertEqual(error(uri(payload(accounts: [account(seed)]) + unsigned(field: 9, 1))),
                       .unsupported)
        // An unknown field inside an account.
        XCTAssertEqual(error(uri(payload(accounts: [account(seed) + unsigned(field: 9, 1)]))),
                       .unsupported)
    }

    func testMalformedStructureIsRefused() {
        let seed = Array("12345678901234567890".utf8)
        // A repeated singleton field.
        XCTAssertEqual(error(uri(unsigned(field: 2, 2) + unsigned(field: 2, 2)
                                 + lengthDelimited(field: 1, account(seed)))), .invalidData)
        // No accounts at all.
        XCTAssertEqual(error(uri(unsigned(field: 2, 2))), .invalidData)
        // An account with no seed.
        XCTAssertEqual(error(uri(payload(accounts: [lengthDelimited(field: 2,
                                                                    Array("x".utf8))]))),
                       .invalidData)
        // A batch index outside the batch.
        XCTAssertEqual(error(uri(payload(accounts: [account(seed)], batchSize: 2, batchIndex: 2))),
                       .invalidData)
        // A batch size of one is the same as none.
        let single = try? MigrationPayload.parse(uri(payload(accounts: [account(seed)],
                                                             batchSize: 1)))
        XCTAssertEqual(1, single?.batchSize)
        // A counter that would be `Long.MAX_VALUE` on the JVM.
        XCTAssertEqual(error(uri(payload(accounts: [account(seed, type: 1,
                                                           counter: UInt64(Int64.max))]))),
                       .invalidData)
        // A truncated varint: the last byte says another one follows.
        XCTAssertEqual(error(uri(payload(accounts: [account(seed)]) + [0x10, 0x80])), .invalidData)
        // A length that runs past the end of the buffer.
        XCTAssertEqual(error(uri(payload(accounts: [account(seed)]) + [0x0A, 0x7F])), .invalidData)
    }

    func testAnAccountNameThatIsTooLongIsRefused() {
        let long = String(repeating: "a", count: InputPolicy.maxTextChars + 1)
        XCTAssertEqual(error(uri(payload(accounts: [account(Array("1234".utf8), name: long)]))),
                       .tooLarge)
    }

    // MARK: - Multi-code exports

    func testASingleCodeExportIsCompleteAtOnce() throws {
        var collector = MigrationBatchCollector(generation: 4)
        let parsed = try MigrationPayload.parse(uri(payload(accounts: [
            account(Array("12345678901234567890".utf8)),
            account(Array("23456789012345678901".utf8)),
        ])))
        XCTAssertEqual(.complete, collector.add(parsed, generation: 4, sessionReady: true))
        XCTAssertEqual(1, collector.received)
        XCTAssertEqual(1, collector.expected)
        XCTAssertEqual(2, collector.takeAccounts(generation: 4, sessionReady: true)?.count)
        // Taking the accounts closes the collector: an export is imported once.
        XCTAssertFalse(collector.isOpen)
        XCTAssertNil(collector.takeAccounts(generation: 4, sessionReady: true))
    }

    func testAMultiCodeExportArrivesInOrderAndOnlyThenCounts() throws {
        let first = try MigrationPayload.parse(uri(payload(
            accounts: [account(Array("1111".utf8), name: "first")],
            batchSize: 2, batchIndex: 0, batchId: 7)))
        let second = try MigrationPayload.parse(uri(payload(
            accounts: [account(Array("2222".utf8), name: "second")],
            batchSize: 2, batchIndex: 1, batchId: 7)))

        var collector = MigrationBatchCollector(generation: 1)
        XCTAssertEqual(.added, collector.add(second, generation: 1, sessionReady: true))
        XCTAssertEqual(1, collector.received)
        XCTAssertEqual(2, collector.expected)
        // An incomplete export yields nothing at all, rather than one account of two, and asking
        // for them ends the import.
        XCTAssertNil(collector.takeAccounts(generation: 1, sessionReady: true))
        XCTAssertFalse(collector.isOpen)

        // Out of order is still in code order once complete.
        var ordered = MigrationBatchCollector(generation: 1)
        XCTAssertEqual(.added, ordered.add(second, generation: 1, sessionReady: true))
        XCTAssertEqual(.complete, ordered.add(first, generation: 1, sessionReady: true))
        XCTAssertEqual(["first", "second"],
                       ordered.takeAccounts(generation: 1, sessionReady: true)?.map(\.label))
    }

    func testARepeatedOrForeignCodeIsRefused() throws {
        let code = try MigrationPayload.parse(uri(payload(
            accounts: [account(Array("1111".utf8), name: "one")],
            batchSize: 2, batchIndex: 0, batchId: 7)))
        let foreign = try MigrationPayload.parse(uri(payload(
            accounts: [account(Array("2222".utf8), name: "two")],
            batchSize: 2, batchIndex: 1, batchId: 8)))

        var collector = MigrationBatchCollector(generation: 1)
        XCTAssertEqual(.added, collector.add(code, generation: 1, sessionReady: true))
        XCTAssertEqual(.duplicate, collector.add(code, generation: 1, sessionReady: true))
        XCTAssertEqual(.differentExport, collector.add(foreign, generation: 1, sessionReady: true))
        XCTAssertEqual(1, collector.received)
        // A foreign code does not end the export: the code this one was meant to be can still come.
        XCTAssertTrue(collector.isOpen)
        // A code that arrives after the vault locked, or after it was unlocked again, belongs to no
        // running import and takes the rest of the export with it.
        XCTAssertEqual(.stale, collector.add(foreign, generation: 1, sessionReady: false))
        XCTAssertFalse(collector.isOpen)

        var reopened = MigrationBatchCollector(generation: 2)
        XCTAssertEqual(.stale, reopened.add(code, generation: 3, sessionReady: true))
        XCTAssertFalse(reopened.isOpen)
        XCTAssertNil(reopened.takeAccounts(generation: 3, sessionReady: true))
    }

    func testAnExportMayNotGrowPastTheVaultLimit() throws {
        // A vault holds at most `MAX_ENTRIES`. One code only carries as many accounts as fit inside
        // the URI ceiling, so a full vault takes several codes; the code that would cross the limit
        // ends the import instead of importing part of the export.
        let perCode = 700
        func code(_ index: Int) throws -> MigrationPayload {
            let accounts = (0..<perCode).map { _ in account(Array("a".utf8), name: "") }
            return try MigrationPayload.parse(uri(payload(accounts: accounts, batchSize: 3,
                                                          batchIndex: index, batchId: 9)))
        }
        var collector = MigrationBatchCollector(generation: 1)
        XCTAssertEqual(.added, collector.add(try code(0), generation: 1, sessionReady: true))
        XCTAssertEqual(.added, collector.add(try code(1), generation: 1, sessionReady: true))
        XCTAssertEqual(.tooMany, collector.add(try code(2), generation: 1, sessionReady: true))
        XCTAssertFalse(collector.isOpen)
        XCTAssertEqual(0, collector.received)
        XCTAssertNil(collector.takeAccounts(generation: 1, sessionReady: true))
    }

    // MARK: - Wording

    func testTheWordingIsTheDesktopsWording() {
        XCTAssertEqual(MigrationWording.title, "Google Authenticator import")
        XCTAssertEqual(MigrationWording.finished(added: 3, skipped: 1),
                       "Imported 3 accounts; skipped 1 identical accounts.")
        XCTAssertEqual(MigrationWording.finished(added: 1, skipped: 0),
                       "Imported 1 accounts; skipped 0 identical accounts.")
        XCTAssertEqual(MigrationWording.confirm(count: 4), "Import 4 accounts from this export?")
        XCTAssertEqual(MigrationWording.progress(received: 1, expected: 2),
                       "Scanned 1 of 2 QR codes. Scan or choose the next code from the same export.")
        XCTAssertEqual(MigrationError.unsupported.message, MigrationWording.unsupported)
        XCTAssertEqual(MigrationError.invalidData.message, MigrationWording.invalid)
        XCTAssertEqual(MigrationError.invalidURI.message, MigrationWording.invalid)
        // Android shows the plain invalid sentence for an oversized payload too.
        XCTAssertEqual(MigrationError.tooLarge.message, MigrationWording.invalid)
        XCTAssertEqual(MigrationWording.message(for: .duplicate),
                       "This export QR code was already scanned.")
        XCTAssertEqual(MigrationWording.message(for: .differentExport),
                       "This code belongs to a different export. Choose a code from the original "
                       + "export.")
        // A stale code is dismissed without a sentence, as Android does.
        XCTAssertEqual(MigrationWording.message(for: .stale), "")
        XCTAssertEqual(MigrationWording.message(for: .added), "")
        XCTAssertEqual(MigrationWording.message(for: .complete), "")
    }

    // MARK: - Helpers

    private func error(_ text: String?) -> MigrationError? {
        do {
            _ = try MigrationPayload.parse(text)
            return nil
        } catch let error as MigrationError {
            return error
        } catch {
            return nil
        }
    }

    private func varint(_ value: UInt64) -> [UInt8] {
        var bytes: [UInt8] = []
        var remaining = value
        repeat {
            var byte = UInt8(remaining & 0x7F)
            remaining >>= 7
            if remaining != 0 { byte |= 0x80 }
            bytes.append(byte)
        } while remaining != 0
        return bytes
    }

    private func tag(_ field: Int, _ wire: Int) -> [UInt8] {
        varint(UInt64((field << 3) | wire))
    }

    private func unsigned(field: Int, _ value: UInt64) -> [UInt8] {
        tag(field, 0) + varint(value)
    }

    private func lengthDelimited(field: Int, _ bytes: [UInt8]) -> [UInt8] {
        tag(field, 2) + varint(UInt64(bytes.count)) + bytes
    }

    private func account(_ seed: [UInt8], name: String = "account", issuer: String = "",
                         algorithm: Int = 1, digits: Int = 1, type: Int = 2,
                         counter: UInt64 = 0) -> [UInt8] {
        var bytes = lengthDelimited(field: 1, seed)
        bytes += lengthDelimited(field: 2, Array(name.utf8))
        if !issuer.isEmpty { bytes += lengthDelimited(field: 3, Array(issuer.utf8)) }
        bytes += unsigned(field: 4, UInt64(algorithm))
        bytes += unsigned(field: 5, UInt64(digits))
        bytes += unsigned(field: 6, UInt64(type))
        bytes += unsigned(field: 7, counter)
        return bytes
    }

    private func payload(accounts: [[UInt8]], version: Int = 1, batchSize: Int = 0,
                         batchIndex: Int = 0, batchId: Int = 0) -> [UInt8] {
        var bytes: [UInt8] = []
        for item in accounts { bytes += lengthDelimited(field: 1, item) }
        bytes += unsigned(field: 2, UInt64(version))
        if batchSize != 0 { bytes += unsigned(field: 3, UInt64(batchSize)) }
        if batchIndex != 0 { bytes += unsigned(field: 4, UInt64(batchIndex)) }
        if batchId != 0 { bytes += unsigned(field: 5, UInt64(batchId)) }
        return bytes
    }

    private func uri(_ binary: [UInt8]) -> String {
        "otpauth-migration://offline?data=" + Data(binary).base64EncodedString()
    }
}
