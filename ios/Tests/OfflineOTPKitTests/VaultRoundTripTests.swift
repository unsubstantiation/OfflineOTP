import XCTest
@testable import OfflineOTPKit

/// Creating, saving and reopening a vault that no other client has ever seen.
///
/// The golden tests prove this client can *read* the shared format. These prove it can *write* one:
/// a vault created here must reopen here, survive a backup/restore, and keep the entries it cannot
/// display.
final class VaultRoundTripTests: XCTestCase {
    private func makeVault() throws -> (PasswordVault, MemoryByteStore, MemoryByteStore) {
        let vaultStore = MemoryByteStore(data: nil)
        let backupStore = MemoryByteStore(data: nil)
        let settings = MemorySettings()
        let vault = PasswordVault(vaultFile: vaultStore, backupFile: backupStore, settings: settings)
        try vault.create(password: "correct horse battery")
        return (vault, vaultStore, backupStore)
    }

    private func totpEntry() throws -> Entry {
        try Entry.parse(.object([
            "secret": .string("JBSWY3DPEHPK3PXP"),
            "issuer": .string("Issuer"),
            "label": .string("alice@example.com"),
            "digits": .int(6),
            "type": .string("TOTP"),
            "algorithm": .string("SHA1"),
            "thumbnail": .string("Default"),
            "last_used": .int(0),
            "used_frequency": .int(0),
            "period": .int(30),
            "tags": .array([.string("work")]),
        ]))
    }

    func testANewVaultIsEmptyAndUnlocksWithItsPassword() throws {
        let (vault, _, _) = try makeVault()
        XCTAssertTrue(vault.isInitialized)
        let unlocked = try vault.open(password: "correct horse battery")
        XCTAssertTrue(unlocked.entries.isEmpty)
        XCTAssertEqual(unlocked.key.count, 16)
    }

    func testTheProvisionedSettingsAreTheSharedOnes() throws {
        let settings = MemorySettings()
        let vault = PasswordVault(vaultFile: MemoryByteStore(data: nil),
                                  backupFile: MemoryByteStore(data: nil),
                                  settings: settings)
        try vault.create(password: "correct horse battery")

        XCTAssertEqual(settings.string(VaultSettingsKeys.authMethod), "password")
        XCTAssertEqual(settings.string(VaultSettingsKeys.encryption), "password")
        XCTAssertEqual(settings.integer(VaultSettingsKeys.authIterations),
                       VaultCrypto.minAuthIterations)
        let salt = VaultCrypto.base64URLDecode(settings.string(VaultSettingsKeys.authSalt) ?? "")
        XCTAssertEqual(salt?.count, VaultCrypto.saltBytes)
        let verifier = VaultCrypto.base64URLDecode(
            settings.string(VaultSettingsKeys.authCredentials) ?? "")
        XCTAssertEqual(verifier?.count, VaultCrypto.halfBytes)
    }

    func testSavingAndReopeningKeepsEveryEntry() throws {
        let (vault, _, _) = try makeVault()
        let unlocked = try vault.open(password: "correct horse battery")
        let entries = [try totpEntry()]
        try vault.save(key: unlocked.key, entries: entries)

        let reopened = try vault.open(password: "correct horse battery")
        XCTAssertEqual(reopened.entries, entries)
        XCTAssertEqual(reopened.entries.first?.issuer, "Issuer")
        XCTAssertEqual(reopened.entries.first?.tags, ["work"])
    }

    func testTheVaultFileIsNoncePrefixedCiphertextWithNoPlaintext() throws {
        let (vault, store, _) = try makeVault()
        let unlocked = try vault.open(password: "correct horse battery")
        try vault.save(key: unlocked.key, entries: [try totpEntry()])
        let bytes = [UInt8](try XCTUnwrap(store.data))
        XCTAssertGreaterThan(bytes.count, VaultCrypto.nonceBytes + VaultCrypto.tagBytes)
        let text = String(decoding: bytes, as: UTF8.self)
        XCTAssertFalse(text.contains("JBSWY3DPEHPK3PXP"))
        XCTAssertFalse(text.contains("alice@example.com"))
    }

    func testTwoSavesOfTheSameEntriesDifferOnlyInTheNonce() throws {
        let (vault, store, _) = try makeVault()
        let unlocked = try vault.open(password: "correct horse battery")
        try vault.save(key: unlocked.key, entries: [try totpEntry()])
        let first = try XCTUnwrap(store.data)
        try vault.save(key: unlocked.key, entries: [try totpEntry()])
        let second = try XCTUnwrap(store.data)
        XCTAssertNotEqual(first, second, "a fresh nonce is written every time")
        XCTAssertEqual(try VaultCrypto.open(first, key: unlocked.key),
                       try VaultCrypto.open(second, key: unlocked.key))
    }

    func testBackupAndRestoreRoundTrip() throws {
        let (vault, store, backupStore) = try makeVault()
        let unlocked = try vault.open(password: "correct horse battery")
        try vault.save(key: unlocked.key, entries: [try totpEntry()])
        let original = try XCTUnwrap(store.data)

        XCTAssertTrue(vault.backup())
        XCTAssertEqual(backupStore.data, original)

        // Replace the vault with an empty one, then promote the backup back.
        try vault.save(key: unlocked.key, entries: [])
        XCTAssertNotEqual(store.data, original)
        XCTAssertTrue(vault.restoreBackup())
        XCTAssertEqual(store.data, original)
        XCTAssertEqual(try vault.open(password: "correct horse battery").entries.count, 1)
    }

    func testAnEntryThisBuildCannotDisplaySurvivesASave() throws {
        // The data-loss rule: a vault written by a client with more entry kinds must not lose them.
        let preserved = try Entry.parse(.object([
            "secret": .string("MZXW6==="),
            "issuer": .string(""),
            "label": .string("Mail"),
            "digits": .int(6),
            "type": .string("EMAIL"),
            "algorithm": .string("SHA1"),
            "thumbnail": .string("Default"),
            "last_used": .int(0),
            "used_frequency": .int(0),
            "tags": .array([]),
        ]))
        let (vault, _, _) = try makeVault()
        let unlocked = try vault.open(password: "correct horse battery")
        try vault.save(key: unlocked.key, entries: [preserved, try totpEntry()])

        let reopened = try vault.open(password: "correct horse battery")
        XCTAssertEqual(reopened.entries.count, 2)
        XCTAssertEqual(reopened.entries[0].kind, .email)
        XCTAssertEqual(reopened.entries[0].label, "Mail")
        XCTAssertEqual(reopened.entries[1].kind, .totp)
    }

    func testCreatingTwiceIsRefused() throws {
        let (vault, _, _) = try makeVault()
        XCTAssertThrowsError(try vault.create(password: "another password")) { error in
            XCTAssertEqual(error as? VaultWriteError, .alreadyExists)
        }
    }

    func testAnEmptyPasswordIsRefused() {
        let vault = PasswordVault(vaultFile: MemoryByteStore(data: nil),
                                  backupFile: MemoryByteStore(data: nil),
                                  settings: MemorySettings())
        XCTAssertThrowsError(try vault.create(password: "")) { error in
            XCTAssertEqual(error as? VaultWriteError, .invalidInput)
        }
    }

    func testAKeyThatIsNotSixteenOrThirtyTwoBytesIsRefused() throws {
        let (vault, _, _) = try makeVault()
        let unlocked = try vault.open(password: "correct horse battery")
        XCTAssertThrowsError(try vault.save(key: [0, 1, 2], entries: [])) { error in
            XCTAssertEqual(error as? VaultWriteError, .ioFailure)
        }
        XCTAssertNoThrow(try vault.save(key: unlocked.key, entries: []))
    }

    func testMissingCredentialsAreReportedAsCorruptRatherThanUnauthorized() {
        // A settings store without a salt is a broken vault, not a wrong password: reporting it as a
        // failed authentication would make the user retype a password that was never the problem.
        let store = MemoryByteStore(data: Data([0, 1, 2, 3]))
        let vault = PasswordVault(vaultFile: store, backupFile: MemoryByteStore(data: nil),
                                  settings: MemorySettings())
        XCTAssertThrowsError(try vault.open(password: "anything")) { error in
            XCTAssertEqual(error as? VaultOpenError, .corrupt)
        }
    }

    func testAFileStorePromotesAtomicallyAndVerifiesItsBytes() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("offlineotp-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = FileByteStore(url: directory.appendingPathComponent("secrets.dat"))

        XCTAssertFalse(store.exists())
        try store.writeAtomic(Data([9, 8, 7]))
        XCTAssertTrue(store.exists())
        XCTAssertEqual(try store.read(), Data([9, 8, 7]))

        try store.writeAtomic(Data([1]))
        XCTAssertEqual(try store.read(), Data([1]))
        store.delete()
        XCTAssertFalse(store.exists())
    }

    func testARealFileBackedVaultReopensAcrossInstances() throws {
        let directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("offlineotp-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: directory) }

        let first = PasswordVault(
            vaultFile: FileByteStore(url: directory.appendingPathComponent("secrets.dat")),
            backupFile: FileByteStore(url: directory.appendingPathComponent("secrets.dat.bck")),
            settings: PropertyListSettings(url: directory.appendingPathComponent("vault.plist")))
        try first.create(password: "correct horse battery")
        let unlocked = try first.open(password: "correct horse battery")
        try first.save(key: unlocked.key, entries: [try totpEntry()])

        // A second PasswordVault over the same directory, as after a relaunch.
        let second = PasswordVault(
            vaultFile: FileByteStore(url: directory.appendingPathComponent("secrets.dat")),
            backupFile: FileByteStore(url: directory.appendingPathComponent("secrets.dat.bck")),
            settings: PropertyListSettings(url: directory.appendingPathComponent("vault.plist")))
        let reopened = try second.open(password: "correct horse battery")
        XCTAssertEqual(reopened.entries.count, 1)
        XCTAssertEqual(reopened.entries[0].code(unixTime: 1700000000).isEmpty, false)
    }
}
