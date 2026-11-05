import XCTest
@testable import OfflineOTPKit

/// Backups and restores: the file-name rule, the strict plain-text reader, and the merge the other
/// two clients perform.
final class BackupRestoreTests: XCTestCase {
    private let seed = "JBSWY3DPEHPK3PXP"

    private func entry(_ label: String, issuer: String = "Issuer",
                       used: Int64 = 0, frequency: Int64 = 0) throws -> Entry {
        var entry = try OtpUri.parse("otpauth://totp/\(issuer):\(label)?secret=\(seed)"
                                     + "&issuer=\(issuer)").entry(newEntry: false)
        entry.setLastUsed(used)
        entry.setUsedFrequency(frequency)
        return entry
    }

    // MARK: - File names

    func testABackupNameIsDatedInTheOtherClientsFormat() {
        // 2026-10-30 12:34:56 UTC. The pattern is `yyyy-MM-dd_HH-mm-ss`, the JVM's
        // `BackupFileNames.TIMESTAMP_PATTERN`, so a file written here sorts with theirs.
        let date = Date(timeIntervalSince1970: 1793363696)
        let name = BackupFileNames.encrypted(date, timeZone: TimeZone(identifier: "UTC")!)
        XCTAssertEqual(name, "otp_accounts_2026-10-30_12-34-56.json.aes")
        XCTAssertTrue(name.hasPrefix(BackupFileNames.encryptedPrefix))
        XCTAssertTrue(name.hasSuffix(BackupFileNames.encryptedSuffix))
    }

    func testTheTimestampIsAsciiWhateverTheDeviceLanguage() {
        // `en_US_POSIX` keeps the digits ASCII; a locale-aware formatter could emit non-ASCII digits
        // in some languages and produce a file name the other clients cannot parse.
        let name = BackupFileNames.encrypted(Date(timeIntervalSince1970: 0),
                                            timeZone: TimeZone(identifier: "UTC")!)
        XCTAssertEqual(name, "otp_accounts_1970-01-01_00-00-00.json.aes")
        XCTAssertTrue(name.unicodeScalars.allSatisfy { $0.isASCII })
    }

    // MARK: - Plain text

    func testPlainTextIsReadStrictly() throws {
        let text = "[{\"secret\":\"\(seed)\"}]"
        XCTAssertEqual(try PlainTextBackupDecoder.decode(Data(text.utf8)), text)
        // One leading byte-order mark is dropped, because an editor may add one.
        XCTAssertEqual(try PlainTextBackupDecoder.decode(Data(("\u{FEFF}" + text).utf8)), text)
    }

    func testPlainTextRefusesWhatItCannotTrust() {
        XCTAssertThrowsError(try PlainTextBackupDecoder.decode(Data())) {
            XCTAssertEqual($0 as? PlainTextBackupError, .invalidSize)
        }
        XCTAssertThrowsError(try PlainTextBackupDecoder.decode(Data(" \n\t ".utf8))) {
            XCTAssertEqual($0 as? PlainTextBackupError, .empty)
        }
        // Invalid UTF-8 is an error rather than replacement characters: a seed that silently changes
        // would produce wrong codes.
        XCTAssertThrowsError(try PlainTextBackupDecoder.decode(Data([0x5B, 0xFF, 0x5D]))) {
            XCTAssertEqual($0 as? PlainTextBackupError, .invalidUTF8)
        }
        let oversized = Data(repeating: 0x20, count: InputPolicy.maxDocumentBytes + 1)
        XCTAssertThrowsError(try PlainTextBackupDecoder.decode(oversized)) {
            XCTAssertEqual($0 as? PlainTextBackupError, .invalidSize)
        }
    }

    // MARK: - Merging

    func testAMergePutsTheImportedEntriesFirstAndKeepsTheRest() throws {
        let kept = try entry("kept")
        let shared = try entry("shared", used: 1_700_000_000, frequency: 7)
        let fresh = try entry("fresh")
        // The same entry in both vaults keeps the counters of the vault it was already in.
        let plan = try VaultRestoreMerge.plan(imported: [shared, fresh], current: [kept, shared],
                                              replace: false)
        XCTAssertEqual(plan.entries.map(\.label), ["fresh", "kept", "shared"])
        XCTAssertEqual(plan.added, 1)
        XCTAssertEqual(plan.entries.first { $0.label == "shared" }?.usedFrequency, 7)
    }

    func testAMergeThatAddsNothingIsNotAnError() throws {
        let existing = try entry("one")
        let plan = try VaultRestoreMerge.plan(imported: [existing], current: [existing],
                                              replace: false)
        XCTAssertEqual(plan.entries, [existing])
        XCTAssertEqual(plan.added, 0)
        XCTAssertEqual(BackupWording.restoreSummary(plan, replace: false),
                       "The backup had no entries the vault does not already hold.")
    }

    func testAReplaceTakesTheImportVerbatim() throws {
        let current = try entry("current")
        let imported = try entry("imported")
        let plan = try VaultRestoreMerge.plan(imported: [imported], current: [current],
                                              replace: true)
        XCTAssertEqual(plan.entries, [imported])
        XCTAssertEqual(plan.added, 1)
        XCTAssertEqual(BackupWording.restoreSummary(plan, replace: true), "Restored 1 entry")
    }

    func testAnEmptyImportIsRefusedEitherWay() throws {
        let current = try entry("current")
        for replace in [false, true] {
            XCTAssertThrowsError(try VaultRestoreMerge.plan(imported: [], current: [current],
                                                           replace: replace)) {
                XCTAssertEqual($0 as? VaultRestoreMergeError, .emptyImport)
            }
        }
    }

    func testDuplicateEntriesInABackupAreRefused() throws {
        let twice = try entry("twice")
        // Replacing: the import is written as it stands, so a repeated entry is refused.
        XCTAssertThrowsError(try VaultRestoreMerge.plan(imported: [twice, twice], current: [],
                                                        replace: true)) {
            XCTAssertEqual($0 as? VaultRestoreMergeError, .duplicateEntries)
        }
        // Merging: a repeated entry the vault does not hold would land twice, so it is refused too.
        XCTAssertThrowsError(try VaultRestoreMerge.plan(imported: [twice, twice], current: [],
                                                        replace: false)) {
            XCTAssertEqual($0 as? VaultRestoreMergeError, .duplicateEntries)
        }
        // Merging over a vault that already holds it: the import's copies are dropped, the vault's
        // copy is kept, and nothing is duplicated — the outcome Android's shared merge produces.
        let plan = try VaultRestoreMerge.plan(imported: [twice, twice], current: [twice],
                                              replace: false)
        XCTAssertEqual(plan.entries, [twice])
        XCTAssertEqual(plan.added, 0)
    }

    func testAMergeCountsOnlyWhatItAdded() {
        let plan = RestorePlan(entries: [Entry](), added: 3)
        XCTAssertEqual(BackupWording.restoreSummary(plan, replace: false),
                       "Merged 3 new entries into the vault")
        XCTAssertEqual(BackupWording.restoreSummary(RestorePlan(entries: [], added: 1),
                                                    replace: false),
                       "Merged 1 new entry into the vault")
    }

    func testTheWordingMatchesTheOtherClients() {
        // Android's sentence, verbatim: the same promise about plain-text files on every platform.
        XCTAssertEqual(BackupWording.plainTextWarning,
                       "Plain-text backups contain your OTP secrets without encryption. Only restore "
                       + "a file you trust. OfflineOTP imports the entries but does not create "
                       + "plain-text backups.")
        XCTAssertEqual(BackupWording.replaceSwitch, "Replace existing entries")
        XCTAssertEqual(BackupWording.duplicateBackup,
                       "The restored entries would duplicate entries already in the vault.")
        XCTAssertEqual(BackupWording.emptyBackup, "The backup contains no entries.")
    }

    // MARK: - The encrypted envelope, as a restore uses it

    func testABackupSealedHereOpensAndMerges() throws {
        let original = [try entry("one"), try entry("two")]
        let document = try VaultDocument.encode(original)
        let sealed = try EncryptedBackup.sealWithFreshParameters(password: "backup pass",
                                                                plain: document)
        let reopened = try VaultDocument.decode(EncryptedBackup.open(password: "backup pass",
                                                                     data: sealed))
        XCTAssertEqual(reopened, original)
        let plan = try VaultRestoreMerge.plan(imported: reopened, current: [], replace: false)
        XCTAssertEqual(plan.entries, original)
    }

    func testAWrongPasswordDoesNotOpenABackup() throws {
        let sealed = try EncryptedBackup.sealWithFreshParameters(
            password: "backup pass", plain: try VaultDocument.encode([try entry("one")]))
        XCTAssertThrowsError(try EncryptedBackup.open(password: "backup pasS", data: sealed))
    }
}
