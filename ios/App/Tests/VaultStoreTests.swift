import XCTest
import OfflineOTPKit
@testable import OfflineOTP

/// The app's wiring around the vault: which folder it uses, what a wrong password says, what a save
/// writes, and that the list rules reach the files instead of only the screen.
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

    private func unlockedStore() -> VaultStore {
        let store = makeStore()
        store.unlock(password: "correct horse", confirmation: "correct horse", creating: true)
        return store
    }

    private func draft(_ label: String, issuer: String = "Issuer", kind: EntryKind = .totp,
                       tags: [String] = []) throws -> Entry {
        try EntryDraft.create(kind: kind, issuer: issuer, label: label, secret: "JBSWY3DPEHPK3PXP",
                             digits: "6", timing: "30", algorithm: .sha1, tags: tags)
    }

    private func rawEntry(_ label: String) throws -> Entry {
        try Entry.parse(.object([
            "secret": .string("JBSWY3DPEHPK3PXP"),
            "issuer": .string("Issuer"),
            "label": .string(label),
            "digits": .int(6),
            "type": .string("TOTP"),
            "algorithm": .string("SHA1"),
            "thumbnail": .string("Default"),
            "last_used": .int(0),
            "used_frequency": .int(0),
            "period": .int(30),
            "tags": .array([]),
        ]))
    }

    // MARK: - Unlocking

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
        let store = unlockedStore()
        XCTAssertTrue(store.isUnlocked)

        let second = makeStore()
        second.unlock(password: "wrong horse")
        XCTAssertFalse(second.isUnlocked)
        XCTAssertEqual(second.message, "That password does not open this vault.")
        // A failed unlock must not reveal as much as an error name would.
        XCTAssertFalse(second.message?.contains("authentication") ?? true)
    }

    func testLockingForgetsTheEntriesAndAnEmptyFolderSaysSo() {
        let store = unlockedStore()
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

    func testSaveIsRefusedWhileLocked() {
        let store = makeStore()
        XCTAssertFalse(store.save())
    }

    // MARK: - Entries

    func testASavedEntryIsVisibleToASecondStoreOverTheSameFolder() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("alice@example.com")))
        XCTAssertEqual(store.entries.count, 1)
        XCTAssertTrue(store.setDetailsVisible(at: 0, true))

        let reopened = makeStore()
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.entries.count, 1)
        XCTAssertEqual(reopened.entries.first?.issuer, "Issuer")
        XCTAssertEqual(reopened.code(for: reopened.entries[0],
                                     at: Date(timeIntervalSince1970: 1_700_000_000)),
                       "324550")
    }

    func testAnEntryWithoutACodeReturnsNothingInsteadOfThrowing() throws {
        let store = unlockedStore()
        let note = try draft("Recovery", kind: .note)
        XCTAssertTrue(store.add(note))
        XCTAssertTrue(store.setDetailsVisible(at: 0, true))
        XCTAssertNil(store.code(for: store.entries[0],
                                at: Date(timeIntervalSince1970: 1_700_000_000)))
    }

    func testAConcealedEntryShowsNoCodeAndTheRevealIsStored() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("alice")))
        let date = Date(timeIntervalSince1970: 1_700_000_000)
        // Concealed is the default a document without `details_visible` means, and both other
        // clients redact the token in that state.
        XCTAssertEqual(store.entries[0].detailsVisible, false)
        XCTAssertNil(store.code(for: store.entries[0], at: date))

        XCTAssertTrue(store.setDetailsVisible(at: 0, true))
        XCTAssertEqual(store.code(for: store.entries[0], at: date), "324550")

        // Persisted as vault data, so a second instance over the same folder agrees.
        let reopened = makeStore()
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.entries[0].detailsVisible, true)

        // Concealing writes the field away again, keeping the legacy bytes for the default state.
        XCTAssertTrue(store.setDetailsVisible(at: 0, false))
        XCTAssertNil(store.entries[0].fields["details_visible"])
        XCTAssertFalse(store.setDetailsVisible(at: 99, true))
    }

    func testAddingAnEntrySelectsItsTabAndRefusesAnIdenticalOne() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("alice")))
        XCTAssertEqual(store.entryCount(for: .otp), 1)
        XCTAssertTrue(store.add(try draft("Recovery", kind: .note)))
        // Both other clients select the tab of the entry they just added.
        XCTAssertEqual(store.category, .notes)

        // The same entry twice is refused; a second hand-typed entry is not a duplicate, because it
        // carries its own usage stamp (the same rule `EntryList.addEntry` follows).
        let again = try rawEntry("alice")
        XCTAssertTrue(store.add(again))
        XCTAssertFalse(store.add(again))
        XCTAssertEqual(store.message, "This vault already holds that entry.")
        XCTAssertEqual(store.entries.count, 3)
    }

    func testDeletingAndEditingPersist() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("alice")))
        XCTAssertTrue(store.add(try draft("bob")))

        var edited = store.entries[1]
        edited.setUsedFrequency(4)
        XCTAssertTrue(store.update(at: 1, with: edited))
        XCTAssertFalse(store.remove(at: 9))

        let reopened = makeStore()
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.entries.map(\.label), ["alice", "bob"])
        XCTAssertEqual(reopened.entries[1].usedFrequency, 4)

        XCTAssertTrue(store.remove(at: 0))
        XCTAssertEqual(store.entries.map(\.label), ["bob"])
        XCTAssertEqual(VaultStore.displayName(store.entries[0]), "bob")
    }

    // MARK: - Usage

    func testCopyingRecordsUseAndTheStepAdvancesTheCounter() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("alice")))
        XCTAssertTrue(store.recordUse(at: 0, now: Date(timeIntervalSince1970: 1_700_000_000)))
        XCTAssertEqual(store.entries[0].usedFrequency, 1)
        XCTAssertEqual(store.entries[0].lastUsed, 1_700_000_000_000)

        // A time-based entry has no step to take.
        XCTAssertFalse(store.advanceCounter(at: 0))

        let hotp = try Entry.parse(.object([
            "secret": .string("KRSXG5A="),
            "issuer": .string("Issuer"),
            "label": .string("counter"),
            "digits": .int(8),
            "type": .string("HOTP"),
            "algorithm": .string("SHA256"),
            "thumbnail": .string(""),
            "last_used": .int(0),
            "used_frequency": .int(0),
            "counter": .int(0),
            "tags": .array([]),
        ]))
        XCTAssertTrue(store.add(hotp))
        XCTAssertTrue(store.advanceCounter(at: 1))
        XCTAssertEqual(store.entries[1].counter, 1)
        XCTAssertEqual(store.entries[1].usedFrequency, 1)

        let reopened = makeStore()
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.entries[1].counter, 1)
        XCTAssertEqual(reopened.entries[1].usedFrequency, 1)
    }

    // MARK: - The list rules reach the files

    func testTheTabsSearchAndTagsComposeOverTheStoredEntries() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("alice-work", tags: ["work"])))
        XCTAssertTrue(store.add(try draft("alice-home", tags: ["home"])))
        XCTAssertTrue(store.add(try draft("bob", tags: ["work"])))
        XCTAssertTrue(store.add(try draft("Recovery", kind: .note)))

        store.category = .otp
        XCTAssertEqual(store.visibleIndices.map { store.entries[$0].label },
                       ["alice-work", "alice-home", "bob"])

        store.query = "alice"
        XCTAssertEqual(store.visibleIndices.map { store.entries[$0].label },
                       ["alice-work", "alice-home"])

        // A live search survives a tab change, and the tab still narrows it.
        store.category = .notes
        XCTAssertEqual(store.visibleIndices.count, 0)
        store.category = .otp

        // Emptying the search brings the tag filter back.
        store.query = ""
        store.toggleTag("work")
        XCTAssertEqual(store.visibleIndices.map { store.entries[$0].label }, ["alice-work", "bob"])
        store.toggleTag("home")
        XCTAssertEqual(store.visibleIndices.count, 3)
        store.setTagFunctionality(.and)
        // Both tags are now selected and no entry carries both, so the intersection is empty.
        XCTAssertEqual(store.visibleIndices.count, 0)
        store.toggleTag("home")
        XCTAssertEqual(store.visibleIndices.map { store.entries[$0].label }, ["alice-work", "bob"])

        // The chip list, ordered case-insensitively and without duplicates.
        XCTAssertEqual(store.tags(for: .otp), ["home", "work"])
        store.clearFilters()
        XCTAssertEqual(store.visibleIndices.count, 3)
        XCTAssertEqual(store.entryCount(for: .notes), 1)
    }

    func testTheSortModeIsRememberedBesideTheVaultAndNeverInsideIt() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try draft("bob", issuer: "Zeta")))
        XCTAssertTrue(store.add(try draft("alice", issuer: "Alpha")))

        let vaultFile = directory.appendingPathComponent(VaultStore.vaultFilename)
        let before = try Data(contentsOf: vaultFile)

        store.setSortMode(.issuer)
        XCTAssertEqual(store.visibleIndices.map { store.entries[$0].label }, ["alice", "bob"])

        // A client preference, not vault data: the vault's bytes are untouched, so an older build
        // still opens it and sorting never rewrites the document.
        XCTAssertEqual(try Data(contentsOf: vaultFile), before)
        let settings = PropertyListSettings(
            url: directory.appendingPathComponent(VaultStore.settingsFilename))
        XCTAssertEqual(settings.string(SortMode.settingsKey), "ISSUER")

        // A second session over the same folder restores the order.
        let reopened = makeStore()
        XCTAssertEqual(reopened.sortMode, .issuer)
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.visibleIndices.map { reopened.entries[$0].label }, ["alice", "bob"])

        store.setSortMode(.unsorted)
        XCTAssertEqual(store.visibleIndices.map { store.entries[$0].label }, ["bob", "alice"])
    }

    func testAMissingSortPreferenceFallsBackToTheVaultOrder() throws {
        let store = unlockedStore()
        XCTAssertEqual(store.sortMode, .unsorted)
        // An unreadable value must not fail the unlock — the desktop client degrades the same way.
        let settings = PropertyListSettings(
            url: directory.appendingPathComponent(VaultStore.settingsFilename))
        settings.setString(SortMode.settingsKey, "nonsense")
        XCTAssertEqual(makeStore().sortMode, .unsorted)
    }

    func testCopyingSetsTheClipboardAndAnExpiry() {
        // The pasteboard is a system service; this only checks the app's own arithmetic around it.
        XCTAssertEqual(Clipboard.expirySeconds, 30)
        XCTAssertTrue(Clipboard.copy("324550", now: Date(timeIntervalSince1970: 1_700_000_000)))
    }
}
