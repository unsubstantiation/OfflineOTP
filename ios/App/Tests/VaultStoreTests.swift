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
                             digits: "6", timing: "30", algorithm: .sha1, tags: tags,
                             newEntry: true)
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

        // The same account twice is refused even though the copy carries its own usage stamp:
        // identity is `Entry.equals`' — issuer, label, seed, kind and token parameters — not the
        // metadata, which is the rule `EntryList.addEntry` applies on the phone and the desktop.
        XCTAssertFalse(store.add(try draft("alice")))
        XCTAssertEqual(store.message, "This vault already holds that entry.")
        XCTAssertEqual(store.entries.count, 2)

        // A different account from the same issuer is a new row, and its tab is selected again.
        XCTAssertTrue(store.add(try draft("bob")))
        XCTAssertEqual(store.entries.count, 3)
        XCTAssertEqual(store.category, .otp)
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

    // MARK: - Backups, restores and links

    func testAnExportedBackupRestoresIntoAnotherVault() throws {
        let source = unlockedStore()
        XCTAssertTrue(source.add(try rawEntry("alice@example.com")))
        XCTAssertTrue(source.add(try draft("bob", issuer: "Acme", tags: ["work"])))

        let exported = try XCTUnwrap(source.exportBackup(password: "backup pass",
                                                        now: Date(timeIntervalSince1970: 0)))
        // The file is dated, so two exports never overwrite each other, and it is the encrypted
        // container the other clients write.
        XCTAssertTrue(exported.name.hasPrefix("otp_accounts_"))
        XCTAssertTrue(exported.name.hasSuffix(".json.aes"))
        XCTAssertTrue(exported.data.count > 44)

        // Another vault, in another folder: a second store over the same folder would be the same
        // vault, and a merge into it would correctly add nothing.
        let elsewhere = directory.appendingPathComponent("second", isDirectory: true)
        try FileManager.default.createDirectory(at: elsewhere, withIntermediateDirectories: true)
        let other = VaultStore(directory: elsewhere)
        other.unlock(password: "correct horse", confirmation: "correct horse", creating: true)
        XCTAssertTrue(other.isUnlocked)
        let plan = try XCTUnwrap(other.restore(encrypted: exported.data, password: "backup pass",
                                              replace: false))
        XCTAssertEqual(plan.added, 2)
        XCTAssertEqual(other.entries.map(\.label).sorted(), ["alice@example.com", "bob"])
        XCTAssertEqual(other.entries.first { $0.label == "bob" }?.tags, ["work"])
        // The restore reaches the file, not only the screen.
        let reopened = VaultStore(directory: elsewhere)
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.entries.count, 2)
    }

    func testARestoreMergesByDefaultAndKeepsTheExistingEntries() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("kept")))

        // The same entry plus one the vault does not have yet.
        let imported = try rawEntry("kept")
        let extra = try rawEntry("extra")
        let document = try VaultDocument.encode([imported, extra])
        let plan = try XCTUnwrap(store.restore(encrypted:
            EncryptedBackup.sealWithFreshParameters(password: "backup pass", plain: document),
            password: "backup pass", replace: false))
        XCTAssertEqual(plan.added, 1)
        XCTAssertEqual(store.entries.map(\.label), ["extra", "kept"])
    }

    func testReplacingTakesTheBackupVerbatim() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("current")))
        let document = try VaultDocument.encode([try rawEntry("imported")])
        let data = try EncryptedBackup.sealWithFreshParameters(password: "pw", plain: document)
        let plan = try XCTUnwrap(store.restore(encrypted: data, password: "pw", replace: true))
        XCTAssertEqual(plan.entries.map(\.label), ["imported"])
        XCTAssertEqual(store.entries.map(\.label), ["imported"])
    }

    func testAWrongBackupPasswordLeavesTheVaultAlone() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("kept")))
        let exported = try XCTUnwrap(store.exportBackup(password: "backup pass"))

        XCTAssertNil(store.restore(encrypted: exported.data, password: "wrong", replace: false))
        XCTAssertEqual(store.message, BackupWording.wrongPassword)
        XCTAssertEqual(store.entries.map(\.label), ["kept"])
    }

    func testAPlainTextBackupIsReadAndAnEmptyOneIsRefused() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("kept")))

        let document = try VaultDocument.encode([try rawEntry("plain")])
        let plan = try XCTUnwrap(store.restore(plainText: Data(document.utf8), replace: false))
        XCTAssertEqual(plan.added, 1)
        XCTAssertEqual(store.entries.map(\.label), ["plain", "kept"])

        XCTAssertNil(store.restore(plainText: Data(), replace: false))
        XCTAssertEqual(store.message, BackupWording.plainTextInvalid)
        XCTAssertNil(store.restore(plainText: Data("[]".utf8), replace: false))
        XCTAssertEqual(store.message, BackupWording.emptyBackup)
        // The import-only rule: nothing this client writes is plain text.
        let exported = try XCTUnwrap(store.exportBackup(password: "backup pass"))
        XCTAssertNotEqual(String(data: exported.data, encoding: .utf8), document)
    }

    func testAPastedLinkBecomesAnEntry() throws {
        let store = unlockedStore()
        XCTAssertEqual(store.importUri("otpauth://totp/Example:alice@example.com?"
                                       + "secret=JBSWY3DPEHPK3PXP&issuer=Example&tags=work"), .added)
        XCTAssertEqual(store.entries.count, 1)
        XCTAssertEqual(store.entries[0].issuer, "Example")
        XCTAssertEqual(store.entries[0].tags, ["work"])
        // An imported account is concealed like any other, so it shows no code until it is revealed.
        XCTAssertNil(store.code(for: store.entries[0],
                                at: Date(timeIntervalSince1970: 1_700_000_000)))
        XCTAssertTrue(store.setDetailsVisible(at: 0, true))
        XCTAssertEqual(store.code(for: store.entries[0],
                                 at: Date(timeIntervalSince1970: 1_700_000_000)), "324550")

        XCTAssertEqual(store.importUri("otpauth://totp/x?secret=not-a-seed"),
                       .rejected("INVALID_SECRET"))
        XCTAssertEqual(store.message, OtpParseError.invalidSecret.message)
        XCTAssertEqual(store.entries.count, 1)
        // A link pasted with a trailing newline still imports; the clipboard adds one often.
        XCTAssertEqual(store.importUri("otpauth://totp/bob?secret=JBSWY3DPEHPK3PXP\n"), .added)
    }

    func testASecondAccountFromAnIssuerIsAQuestionNotAFailure() throws {
        let store = unlockedStore()
        let moment = Date(timeIntervalSince1970: 1_700_000_000)
        XCTAssertEqual(store.importUri("otpauth://totp/Acme:one?secret=JBSWY3DPEHPK3PXP"
                                       + "&issuer=Acme", now: moment), .added)
        // Same issuer, different account: ask first, as both other clients do on a QR import.
        XCTAssertEqual(store.importUri("otpauth://totp/Acme:two?secret=JBSWY3DPEHPK3PXP"
                                       + "&issuer=acme "), .duplicateIssuer("acme"))
        XCTAssertEqual(store.entries.count, 1)
        XCTAssertEqual(store.importUri("otpauth://totp/Acme:two?secret=JBSWY3DPEHPK3PXP"
                                       + "&issuer=acme ", allowDuplicateIssuer: true), .added)
        XCTAssertEqual(store.entries.count, 2)
        XCTAssertEqual(VaultStore.ImportOutcome.duplicateIssuerMessage("Acme"),
                       "An account for Acme is already in your vault. Add another account from this "
                       + "issuer anyway?")
        // The identical account is refused: the vault would hold it twice.
        XCTAssertEqual(store.importUri("otpauth://totp/Acme:one?secret=JBSWY3DPEHPK3PXP"
                                       + "&issuer=Acme", allowDuplicateIssuer: true,
                                       now: moment), .duplicateEntry)
        XCTAssertEqual(store.entries.count, 2)
        XCTAssertTrue(store.hasIssuer("  ACME  "))
        XCTAssertFalse(store.hasIssuer("   "))
        XCTAssertFalse(store.hasIssuer("Github"))
    }

    func testAGenericOrUnsupportedCodeIsExplained() throws {
        let store = unlockedStore()
        // A Wi-Fi joining code is a real QR payload that is not an account: the parser says so, the
        // same way `QrImport` reports `Outcome.Invalid` rather than "nothing recognised".
        XCTAssertEqual(store.importPayload("WIFI:S:net;T:WPA;P:pass;;"), .rejected("INVALID_URI"))
        XCTAssertEqual(store.message, OtpParseError.invalidUri.message)
        // A Google Authenticator export is collected rather than added as one entry; a code that
        // cannot be decoded is explained in the export's own words, as on Android.
        guard case .migration(let step) = store.importPayload("otpauth-migration://offline?data=abc")
        else { return XCTFail("a migration code must be routed to the export import") }
        XCTAssertNil(step.entries)
        XCTAssertEqual(step.message, MigrationWording.invalid)
        XCTAssertEqual(store.message, MigrationWording.invalid)
        XCTAssertEqual(store.importPayload("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP"), .added)
        XCTAssertEqual(store.entries.count, 1)
    }

    func testOnlyOtpEntriesHaveALinkToShowAsACode() throws {
        let store = unlockedStore()
        XCTAssertTrue(store.add(try rawEntry("alice@example.com")))
        XCTAssertTrue(store.add(try draft("Recovery", kind: .note)))
        XCTAssertEqual(store.otpUri(for: 0),
                       "otpauth://totp/alice%40example.com?secret=JBSWY3DPEHPK3PXP&issuer=Issuer")
        XCTAssertNil(store.otpUri(for: 1))
        XCTAssertNil(store.otpUri(for: 9))
    }

    // MARK: - Google Authenticator exports

    /// One account, one code: `Example:alice` with the RFC 6238 seed.
    private let migrationSingle = "otpauth-migration://offline?data=CjYKFDEyMzQ1Njc4OTAxMjM0NTY3ODkw"
        + "Eg1FeGFtcGxlOmFsaWNlGgdFeGFtcGxlIAEoATACOAAQAQ=="
    /// The first and second code of one two-code export.
    private let migrationFirst = "otpauth-migration://offline?data=CjYKFDEyMzQ1Njc4OTAxMjM0NTY3ODkw"
        + "Eg1FeGFtcGxlOmFsaWNlGgdFeGFtcGxlIAEoATACOAAQARgCKAc="
    private let migrationSecond = "otpauth-migration://offline?data=CjQKFDIzNDU2Nzg5MDEyMzQ1Njc4OTAx"
        + "EgtFeGFtcGxlOmJvYhoHRXhhbXBsZSABKAEwAjgAEAEYAiABKAc="
    /// A code from a different export, offered while the two-code one is open.
    private let migrationForeign = "otpauth-migration://offline?data=CjIKFDM0NTY3ODkwMTIzNDU2Nzg5MDEy"
        + "EgtPdGhlcjpjYXJvbBoFT3RoZXIgASgBMAI4ABABGAIgASgI"

    func testAnExportIsCollectedUntilTheLastCodeArrives() {
        let store = unlockedStore()
        let first = store.addMigrationImportCode(migrationFirst)
        XCTAssertNil(first.entries)
        XCTAssertEqual(first.received, 1)
        XCTAssertEqual(first.expected, 2)
        XCTAssertEqual(first.message,
                       "Scanned 1 of 2 QR codes. Scan or choose the next code from the same export.")
        // Nothing is written before the whole export is present.
        XCTAssertTrue(store.entries.isEmpty)

        let second = store.addMigrationImportCode(migrationSecond)
        XCTAssertEqual(second.received, 2)
        XCTAssertEqual(second.expected, 2)
        XCTAssertEqual(second.entries?.map(\.label), ["alice", "bob"])
        XCTAssertEqual(second.entries?.map(\.issuer), ["Example", "Example"])
        XCTAssertEqual(store.entries.count, 0)
        // Asking for the export again would start over: it was handed to the confirmation.
        XCTAssertNil(store.openMigrationImport)
    }

    func testAnExportIsWrittenOnlyOnceItIsConfirmed() throws {
        let store = unlockedStore()
        let step = store.addMigrationImportCode(migrationSingle)
        let entries = try XCTUnwrap(step.entries)
        XCTAssertEqual(entries.count, 1)
        XCTAssertTrue(store.entries.isEmpty)

        let result = store.confirmMigrationImport(entries)
        XCTAssertEqual(result, .imported(added: 1, skipped: 0))
        XCTAssertEqual(result.message, "Imported 1 accounts; skipped 0 identical accounts.")
        XCTAssertEqual(store.entries.count, 1)
        XCTAssertEqual(store.entries[0].label, "alice")
        XCTAssertEqual(store.entries[0].issuer, "Example")
        // A code from the import can be read at once, and the account is concealed like every other.
        XCTAssertFalse(store.entries[0].detailsVisible)
        XCTAssertEqual(store.category, .otp)

        let reopened = makeStore()
        reopened.unlock(password: "correct horse")
        XCTAssertEqual(reopened.entries.count, 1)
        XCTAssertEqual(reopened.entries[0].label, "alice")
    }

    func testAnIdenticalAccountIsSkippedRatherThanImportedTwice() throws {
        let store = unlockedStore()
        let first = try XCTUnwrap(store.addMigrationImportCode(migrationSingle).entries)
        XCTAssertEqual(store.confirmMigrationImport(first), .imported(added: 1, skipped: 0))
        // The same export again: the account is the same account even though its last-used stamp
        // has moved, which is what Android's `Entry.equals` decides.
        let again = try XCTUnwrap(store.addMigrationImportCode(migrationSingle).entries)
        let result = store.confirmMigrationImport(again)
        XCTAssertEqual(result, .imported(added: 0, skipped: 1))
        XCTAssertEqual(store.entries.count, 1)
    }

    func testAForeignOrRepeatedCodeKeepsTheExportOpen() {
        let store = unlockedStore()
        _ = store.addMigrationImportCode(migrationFirst)
        XCTAssertEqual(store.addMigrationImportCode(migrationForeign).message,
                       "This code belongs to a different export. Choose a code from the original "
                       + "export.")
        XCTAssertEqual(store.addMigrationImportCode(migrationFirst).message,
                       "This export QR code was already scanned.")
        // The export still completes with the code it was waiting for.
        XCTAssertNotNil(store.addMigrationImportCode(migrationSecond).entries)
    }

    func testALockEndsAnExportInProgress() {
        let store = unlockedStore()
        let generation = store.generation
        _ = store.addMigrationImportCode(migrationFirst)
        XCTAssertNotNil(store.openMigrationImport)
        store.lock()
        XCTAssertNil(store.openMigrationImport)
        XCTAssertGreaterThan(store.generation, generation)

        store.unlock(password: "correct horse")
        // The other half of an export from the previous session completes nothing.
        let step = store.addMigrationImportCode(migrationSecond)
        XCTAssertNil(step.entries)
        XCTAssertEqual(step.received, 1)
        XCTAssertTrue(store.entries.isEmpty)
    }

    func testTheConfirmationNamesTheAccountsAndNeverTheSecrets() throws {
        let store = unlockedStore()
        let entries = try XCTUnwrap(store.addMigrationImportCode(migrationSingle).entries)
        let preview = MigrationWording.preview(entries)
        XCTAssertTrue(preview.hasPrefix("Import 1 accounts from this export?\n\n"))
        XCTAssertTrue(preview.contains("Example — alice"))
        XCTAssertTrue(preview.hasSuffix(MigrationWording.duplicateNotice))
        // The seed behind the account is not in the confirmation.
        XCTAssertFalse(preview.contains("12345678901234567890"))
        XCTAssertFalse(preview.contains(entries[0].secretBase32 ?? "\u{0}"))
    }
}
