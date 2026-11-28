import Foundation
import XCTest
@testable import OfflineOTPKit

final class PersistenceInterruptionTests: XCTestCase {
    private enum Failure: Error { case interrupted }
    private final class Store: AtomicByteStore {
        var bytes: Data?
        var failBefore = false
        var failAfter = false
        func exists() -> Bool { bytes != nil }
        func read() throws -> Data { guard let bytes else { throw Failure.interrupted }; return bytes }
        func writeAtomic(_ data: Data) throws {
            if failBefore { throw Failure.interrupted }
            bytes = data
            if failAfter { throw Failure.interrupted }
        }
        func delete() { bytes = nil }
    }

    func testFailedCreateNeverPublishesCredentials() {
        let file = Store(); file.failBefore = true
        let settings = MemorySettings()
        let vault = PasswordVault(vaultFile: file, backupFile: Store(), settings: settings)
        XCTAssertThrowsError(try vault.create(password: "original-password"))
        XCTAssertNil(settings.string(VaultSettingsKeys.authSalt))
        XCTAssertFalse(file.exists())
    }

    func testLegacyRekeyKeepsOriginalSettingsUntilPromotion() throws {
        let file = Store(), backup = Store(), settings = MemorySettings()
        let vault = PasswordVault(vaultFile: file, backupFile: backup, settings: settings)
        try vault.create(password: "original-password")
        let opened = try vault.open(password: "original-password")
        file.bytes = try VaultCrypto.sealWithRandomNonce(Data("[]".utf8), key: opened.key)
        let prior = try file.read(), salt = settings.string(VaultSettingsKeys.authSalt)
        file.failBefore = true
        XCTAssertThrowsError(try vault.changePassword(currentPassword: "original-password", newPassword: "replacement-password"))
        XCTAssertEqual(try file.read(), prior); XCTAssertEqual(try backup.read(), prior)
        XCTAssertEqual(settings.string(VaultSettingsKeys.authSalt), salt)
        XCTAssertNoThrow(try vault.open(password: "original-password"))
    }

    func testAcknowledgmentFailureAfterRekeyDoesNotOverwriteRecoverableCandidate() throws {
        let file = Store(), backup = Store(), settings = MemorySettings()
        let vault = PasswordVault(vaultFile: file, backupFile: backup, settings: settings)
        try vault.create(password: "original-password")
        let prior = try file.read(), salt = settings.string(VaultSettingsKeys.authSalt)
        file.failAfter = true
        XCTAssertThrowsError(try vault.changePassword(currentPassword: "original-password", newPassword: "replacement-password"))
        XCTAssertEqual(try backup.read(), prior)
        XCTAssertEqual(settings.string(VaultSettingsKeys.authSalt), salt)
        let recovered = PasswordVault(vaultFile: file, backupFile: Store(), settings: MemorySettings())
        XCTAssertNoThrow(try recovered.open(password: "replacement-password"))
    }

    func testMigrationBackupFailureNeverPromotes() throws {
        let file = Store(), backup = Store(), settings = MemorySettings()
        let vault = PasswordVault(vaultFile: file, backupFile: backup, settings: settings)
        try vault.create(password: "original-password")
        let opened = try vault.open(password: "original-password")
        file.bytes = try VaultCrypto.sealWithRandomNonce(Data("[]".utf8), key: opened.key)
        let prior = try file.read(); backup.failBefore = true
        XCTAssertThrowsError(try vault.migrateToContainer(password: "original-password"))
        XCTAssertEqual(try file.read(), prior)
        XCTAssertNoThrow(try vault.open(password: "original-password"))
    }
}
