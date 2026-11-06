import XCTest
import OfflineOTPKit
@testable import OfflineOTP

/// The quick unlock: what turning it on costs, what it stores, and what happens when the device or
/// the vault stops agreeing with it.
///
/// The Keychain itself cannot be driven from a unit test — a biometric prompt needs a face — so the
/// store's rules are exercised through `BiometricKeyStore`, and the sentences and the security
/// properties of the real Keychain item are pinned by `scripts/tests/test_ios_app.py`.
final class BiometricUnlockTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("offlineotp-bio-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    private let password = "correct horse"

    private func makeStore(biometrics: BiometricKeyStore) -> VaultStore {
        VaultStore(directory: directory, biometrics: biometrics)
    }

    private func unlockedStore(_ biometrics: BiometricKeyStore) -> VaultStore {
        let store = makeStore(biometrics: biometrics)
        store.unlock(password: password, confirmation: password, creating: true)
        return store
    }

    private func entry(_ label: String) throws -> Entry {
        try EntryDraft.create(kind: .totp, issuer: "Issuer", label: label,
                              secret: "JBSWY3DPEHPK3PXP", digits: "6", timing: "30",
                              algorithm: .sha1, tags: [], newEntry: true)
    }

    // MARK: - Turning it on

    func testTheQuickUnlockIsOffUntilItIsTurnedOn() {
        let fake = FakeBiometricKeyStore()
        let store = unlockedStore(fake)
        XCTAssertFalse(store.biometricUnlockEnabled)
        XCTAssertFalse(store.canUnlockWithBiometrics)
        XCTAssertEqual(store.biometry, .available(.faceID))
        XCTAssertEqual(store.biometryTitle, "Face ID")
        // Nothing is asked of the device while the switch is off.
        store.lock()
        store.unlockWithBiometrics()
        XCTAssertFalse(fake.didLoad)
        XCTAssertFalse(store.isUnlocked)
    }

    func testTurningItOnNeedsTheVaultPassword() throws {
        let fake = FakeBiometricKeyStore()
        let store = unlockedStore(fake)
        XCTAssertTrue(store.add(try entry("alice")))

        XCTAssertFalse(store.enableBiometricUnlock(password: "wrong horse"))
        XCTAssertFalse(store.biometricUnlockEnabled)
        XCTAssertEqual(store.message, BiometricWording.wrongPassword)
        // A refused attempt files nothing.
        XCTAssertEqual(fake.saveCount, 0)

        XCTAssertTrue(store.enableBiometricUnlock(password: password))
        XCTAssertTrue(store.biometricUnlockEnabled)
        XCTAssertEqual(store.message, BiometricWording.turnedOn)
        XCTAssertEqual(fake.saveCount, 1)
        XCTAssertTrue(store.canUnlockWithBiometrics)
    }

    func testTheKeyItKeepsIsTheVaultKeyAndNeverThePassword() throws {
        let fake = FakeBiometricKeyStore()
        let store = unlockedStore(fake)
        XCTAssertTrue(store.enableBiometricUnlock(password: password))

        let saved = try XCTUnwrap(fake.savedKeys.last)
        XCTAssertEqual(saved.count, 32, "the 256-bit AES-256-GCM vault session key, as the JVM derives it")
        XCTAssertNotEqual(saved, Array(password.utf8))
        // It is the key the password derives: that is what makes it open the file below.
        let second = makeStore(biometrics: FakeBiometricKeyStore())
        second.unlock(password: password)
        XCTAssertEqual(second.entries.count, store.entries.count)
    }

    func testTheKeyIsFiledUnderTheVaultItBelongsTo() throws {
        let first = FakeBiometricKeyStore()
        XCTAssertTrue(unlockedStore(first).enableBiometricUnlock(password: password))
        let account = try XCTUnwrap(first.storedAccount)

        // The same vault, opened again: the same slot, so a copy of a vault and the original share
        // one key and a different vault does not.
        let again = FakeBiometricKeyStore()
        let reopened = makeStore(biometrics: again)
        reopened.unlock(password: password)
        XCTAssertTrue(reopened.enableBiometricUnlock(password: password))
        XCTAssertEqual(again.storedAccount, account)

        let otherDirectory = directory.appendingPathComponent("another", isDirectory: true)
        try FileManager.default.createDirectory(at: otherDirectory, withIntermediateDirectories: true)
        let elsewhere = FakeBiometricKeyStore()
        let other = VaultStore(directory: otherDirectory, biometrics: elsewhere)
        other.unlock(password: password, confirmation: password, creating: true)
        XCTAssertTrue(other.enableBiometricUnlock(password: password))
        XCTAssertNotEqual(elsewhere.storedAccount, account)
    }

    func testAnUnavailableDeviceSaysWhyAndFilesNothing() {
        let fake = FakeBiometricKeyStore()
        fake.availabilityValue = .notEnrolled(.faceID)
        let store = unlockedStore(fake)
        XCTAssertFalse(store.canUnlockWithBiometrics)
        XCTAssertFalse(store.enableBiometricUnlock(password: password))
        XCTAssertFalse(store.biometricUnlockEnabled)
        XCTAssertEqual(store.message, "Set up Face ID on this device first.")
        XCTAssertEqual(fake.saveCount, 0)
    }

    func testAvailabilityIsAskedAgainSoAnEnrolledFaceShowsUp() {
        let fake = FakeBiometricKeyStore()
        fake.availabilityValue = .notEnrolled(.faceID)
        let store = unlockedStore(fake)
        XCTAssertFalse(store.biometry.isAvailable)
        // The person enrolls a face in Settings and comes back.
        fake.availabilityValue = .available(.faceID)
        store.refreshBiometry()
        XCTAssertTrue(store.biometry.isAvailable)
        XCTAssertTrue(store.enableBiometricUnlock(password: password))
    }

    func testAFailedSaveLeavesTheSwitchOff() {
        let fake = FakeBiometricKeyStore()
        fake.saveError = .failed
        let store = unlockedStore(fake)
        XCTAssertFalse(store.enableBiometricUnlock(password: password))
        XCTAssertFalse(store.biometricUnlockEnabled)
        XCTAssertEqual(store.message, BiometricWording.saveFailed)
    }

    // MARK: - Unlocking

    func testTheQuickUnlockOpensTheVaultAfterALock() throws {
        let fake = FakeBiometricKeyStore()
        let store = unlockedStore(fake)
        XCTAssertTrue(store.add(try entry("alice")))
        XCTAssertTrue(store.enableBiometricUnlock(password: password))

        store.lock()
        XCTAssertFalse(store.isUnlocked)
        // A locked vault offers the quick unlock even though nothing is in memory.
        XCTAssertTrue(store.canUnlockWithBiometrics)

        store.unlockWithBiometrics()
        XCTAssertTrue(store.isUnlocked)
        XCTAssertEqual(store.entries.map(\.label), ["alice"])
        XCTAssertNil(store.message)
        XCTAssertEqual(fake.lastReason, BiometricWording.reason)

        // The password still opens the same vault: the shortcut changed nothing about the file.
        let reopened = makeStore(biometrics: FakeBiometricKeyStore())
        reopened.unlock(password: password)
        XCTAssertEqual(reopened.entries.map(\.label), ["alice"])
    }

    func testACancelledPromptLeavesTheVaultLockedAndUsable() {
        let fake = FakeBiometricKeyStore()
        let store = unlockedStore(fake)
        XCTAssertTrue(store.enableBiometricUnlock(password: password))
        store.lock()
        fake.loadResult = .failure(.cancelled)

        store.unlockWithBiometrics()
        XCTAssertFalse(store.isUnlocked)
        XCTAssertEqual(store.message, BiometricWording.cancelled)
        // Cancelling costs one prompt, not the switch.
        XCTAssertTrue(store.biometricUnlockEnabled)

        // ... and the password is right there.
        store.unlock(password: password)
        XCTAssertTrue(store.isUnlocked)
    }

    func testAKeyThatNoLongerOpensTheVaultIsForgotten() throws {
        let fake = FakeBiometricKeyStore()
        let store = unlockedStore(fake)
        XCTAssertTrue(store.add(try entry("alice")))
        XCTAssertTrue(store.enableBiometricUnlock(password: password))
        // Where it was filed, remembered before the failed unlock — dropping the key clears it.
        let account = try XCTUnwrap(fake.storedAccount)
        // The enrolled face changed, or the vault was re-created: the key the device hands back is
        // not this vault's key.
        fake.storedKey = [UInt8](repeating: 7, count: 32)
        store.lock()

        store.unlockWithBiometrics()
        XCTAssertFalse(store.isUnlocked)
        XCTAssertEqual(store.message, BiometricWording.stale)
        XCTAssertFalse(store.biometricUnlockEnabled)
        XCTAssertFalse(store.canUnlockWithBiometrics)
        // The key is dropped rather than left to fail again on the next tap.
        XCTAssertTrue(fake.removedAccounts.contains(account))
        XCTAssertNil(fake.storedKey)
        // And the password still opens the vault, with the entries intact.
        store.unlock(password: password)
        XCTAssertTrue(store.isUnlocked)
        XCTAssertEqual(store.entries.map(\.label), ["alice"])
    }

    func testAMissingKeyIsTheSameStory() {
        let fake = FakeBiometricKeyStore()
        let store = unlockedStore(fake)
        XCTAssertTrue(store.enableBiometricUnlock(password: password))
        store.lock()
        // The item was invalidated with the enrolled face, or deleted.
        fake.storedKey = nil
        store.unlockWithBiometrics()
        XCTAssertFalse(store.isUnlocked)
        XCTAssertEqual(store.message, BiometricWording.stale)
        XCTAssertFalse(store.biometricUnlockEnabled)
    }

    func testALockoutSaysToUseThePassword() {
        let fake = FakeBiometricKeyStore()
        let store = unlockedStore(fake)
        XCTAssertTrue(store.enableBiometricUnlock(password: password))
        store.lock()
        fake.loadResult = .failure(.lockedOut)
        store.unlockWithBiometrics()
        XCTAssertFalse(store.isUnlocked)
        XCTAssertEqual(store.message, BiometricWording.lockedOut)
        XCTAssertTrue(store.biometricUnlockEnabled)
    }

    // MARK: - Turning it off

    func testTurningItOffForgetsTheKeyAndThePreference() throws {
        let fake = FakeBiometricKeyStore()
        let store = unlockedStore(fake)
        XCTAssertTrue(store.enableBiometricUnlock(password: password))
        let account = try XCTUnwrap(fake.storedAccount)

        store.disableBiometricUnlock()
        XCTAssertFalse(store.biometricUnlockEnabled)
        XCTAssertEqual(store.message, BiometricWording.turnedOff)
        XCTAssertTrue(fake.removedAccounts.contains(account))
        XCTAssertNil(fake.storedKey)

        // The preference is beside the vault, so it survives a relaunch — and so does the answer.
        let reopened = makeStore(biometrics: fake)
        XCTAssertFalse(reopened.biometricUnlockEnabled)
        // Nothing is left for a later unlock to find, so the lock screen stays a password prompt.
        reopened.unlockWithBiometrics()
        XCTAssertFalse(reopened.isUnlocked)
        // Turning it on again is a fresh decision, and asks for the password like the first time.
        reopened.unlock(password: password)
        XCTAssertTrue(reopened.enableBiometricUnlock(password: password))
        XCTAssertTrue(reopened.biometricUnlockEnabled)
    }

    func testThePreferenceSurvivesARelaunch() {
        let fake = FakeBiometricKeyStore()
        let store = unlockedStore(fake)
        XCTAssertTrue(store.enableBiometricUnlock(password: password))
        // A second store over the same folder is the same vault with the same switch.
        XCTAssertTrue(makeStore(biometrics: fake).biometricUnlockEnabled)
    }
}

/// The device, stood in for: it can claim any availability, keeps whatever key it is given, and can
/// be told to fail the next prompt.
final class FakeBiometricKeyStore: BiometricKeyStore {
    var availabilityValue: BiometryAvailability = .available(.faceID)
    var storedKey: [UInt8]?
    var storedAccount: String?
    var saveError: BiometricKeyError?
    /// What the next `load` answers, instead of the stored key.
    var loadResult: Result<[UInt8], BiometricKeyError>?

    private(set) var savedKeys: [[UInt8]] = []
    private(set) var removedAccounts: [String] = []
    private(set) var didLoad = false
    private(set) var lastReason: String?

    var saveCount: Int { savedKeys.count }

    func availability() -> BiometryAvailability { availabilityValue }

    func save(key: [UInt8], account: String) throws {
        if let saveError { throw saveError }
        savedKeys.append(key)
        storedKey = key
        storedAccount = account
    }

    func load(account: String, reason: String,
              completion: @escaping (Result<[UInt8], BiometricKeyError>) -> Void) {
        didLoad = true
        lastReason = reason
        if let loadResult {
            completion(loadResult)
            return
        }
        if let storedKey, storedAccount == account {
            completion(.success(storedKey))
        } else {
            completion(.failure(.missing))
        }
    }

    func remove(account: String) {
        removedAccounts.append(account)
        if storedAccount == account {
            storedKey = nil
            storedAccount = nil
        }
    }
}
