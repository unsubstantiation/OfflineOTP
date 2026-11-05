import XCTest
import OfflineOTPKit
@testable import OfflineOTP

/// The app's wiring around the vault: which folder it uses, what a wrong password says, and that a
/// save is a real file another instance can open.
final class VaultStoreTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("offlineotp-ios-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private func makeStore() -> VaultStore {
        VaultStore(directory: directory)
    }

    func testTheFolderIsTheDocumentsDataDirectory() {
        let fileManager = FileManager.default
        let documents = fileManager.urls(for: .documentDirectory, in: .userDomainMask).first
        XCTAssertEqual(VaultStore.defaultDirectory(fileManager: fileManager),
                       documents?.appendingPathComponent("data", isDirectory: true))
    }

    func testAFreshFolderHasNoVaultAndCreatingOneUnlocksIt() {
        let store = makeStore()
        XCTAssertFalse(store.hasVault)
        XCTAssertFalse(store.isUnlocked)

        store.unlock(password: "correct horse", confirmation: "correct horse", creating: true)
        XCTAssertNil(store.message)
        XCTAssertTrue(store.hasVault)
        XCTAssertTrue(store.isUnlocked)
        XCTAssertTrue(store.entries.isEmpty)
        XCTAssertTrue(FileManager.default.fileExists(
            atPath: directory.appendingPathComponent(VaultStore.vaultFilename).path))
    }

    func testTheShortPasswordAndTheMismatchAreExplainedRatherThanRefusedSilently() {
        let store = makeStore()
        store.unlock(password: "short", confirmation: "short", creating: true)
        XCTAssertFalse(store.isUnlocked)
        XCTAssertEqual(store.message, "Use at least 6 characters.")

        store.unlock(password: "correct horse", confirmation: "correct hors", creating: true)
        XCTAssertFalse(store.isUnlocked)
        XCTAssertEqual(store.message, "The two passwords do not match.")
        XCTAssertFalse(store.hasVault)
    }

    func testAWrongPasswordSaysTheSameThingWhateverTheReason() {
        let store = makeStore()
        store.unlock(password: "correct horse", confirmation: "correct horse", creating: true)
        XCTAssertTrue(store.isUnlocked)

        let second = makeStore()
        second.unlock(password: "wrong horse")
        XCTAssertFalse(second.isUnlocked)
        XCTAssertEqual(second.message, "That password does not open this vault.")
        // A failed unlock must not reveal as much as an error name would.
        XCTAssertFalse(second.message?.contains("authentication") ?? true)
    }

    func testLockingForgetsTheEntriesAndAnEmptyFolderSaysSo() {
        let store = makeStore()
        store.unlock(password: "correct horse", confirmation: "correct horse", creating: true)
        store.lock()
        XCTAssertFalse(store.isUnlocked)
        XCTAssertTrue(store.entries.isEmpty)
        XCTAssertTrue(store.hasVault)

        store.unlock(password: "correct horse")
        XCTAssertTrue(store.isUnlocked)

        let empty = VaultStore(directory: directory.appendingPathComponent("nowhere"))
        empty.unlock(password: "correct horse")
        XCTAssertEqual(empty.message, "There is no vault in this folder yet.")
    }

    func testASavedEntryIsVisibleToASecondStoreOverTheSameFolder() throws {
        let store = makeStore()
        store.unlock(password: "correct horse", confirmation: "correct horse", creating: true)
        let entry = try Entry.parse(.object([
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
            "tags": .array([]),
        ]))
        store.add(entry)
        XCTAssertEqual(store.entries.count, 1)

        let reopened = makeStore()
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.entries.count, 1)
        XCTAssertEqual(reopened.entries.first?.issuer, "Issuer")
        XCTAssertEqual(reopened.code(for: entry, at: Date(timeIntervalSince1970: 1_700_000_000)),
                       "324550")
    }

    func testAnEntryWithoutACodeReturnsNothingInsteadOfThrowing() throws {
        let store = makeStore()
        store.unlock(password: "correct horse", confirmation: "correct horse", creating: true)
        let note = try Entry.parse(.object([
            "secret": .string(Base32.encode(Array("a note".utf8))),
            "issuer": .string(""),
            "label": .string("Recovery"),
            "digits": .int(6),
            "type": .string("NOTE"),
            "algorithm": .string("SHA1"),
            "thumbnail": .string("Default"),
            "last_used": .int(0),
            "used_frequency": .int(0),
            "tags": .array([]),
        ]))
        XCTAssertNil(store.code(for: note, at: Date(timeIntervalSince1970: 1_700_000_000)))
    }

    func testSaveIsRefusedWhileLocked() {
        let store = makeStore()
        XCTAssertFalse(store.save())
    }

    func testCopyingSetsTheClipboardAndAnExpiry() {
        // The pasteboard is a system service; this only checks the app's own arithmetic around it.
        XCTAssertEqual(Clipboard.expirySeconds, 30)
        XCTAssertTrue(Clipboard.copy("324550", now: Date(timeIntervalSince1970: 1_700_000_000)))
    }
}
