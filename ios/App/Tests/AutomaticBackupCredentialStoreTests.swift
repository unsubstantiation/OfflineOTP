import XCTest
import OfflineOTPKit
import OfflineOTPPresentation
@testable import OfflineOTP

final class AutomaticBackupCredentialStoreTests: XCTestCase {
    func testSharedValidationRejectsDuplicateFieldsAndPersistentSigningMaterial() throws {
        let codec = AutomaticBackupConfigurationCodec()
        let good = #"{"version":1,"folder":"/synthetic","password":"secret","minutes":15,"keep":10}"#
        XCTAssertNotNil(codec.canonical(text: good))
        XCTAssertNil(codec.canonical(text: good.replacingOccurrences(of: "\"version\":1", with: "\"version\":1,\"version\":1")))
        XCTAssertNil(codec.canonical(text: String(good.dropLast()) + #","signingKey":"private"}"#))
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let key = [UInt8](repeating: 3, count: 32)
        let malformed = String(good.dropLast()) + #","requiresSigning":true}"#
        let record = try AutomaticBackupCredentialCodec.seal(key: key, plaintext: Data(malformed.utf8))
        try record.write(to: root.appendingPathComponent(AutomaticBackupCredentialStore.filename))
        let store = AutomaticBackupCredentialStore(directory: root)
        XCTAssertThrowsError(try store.load(key: key)); XCTAssertTrue(store.exists)
    }

    func testSnapshotConfigurationV3HasNoExportCredentialsAndLegacyRemainsAnExport() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = AutomaticBackupCredentialStore(directory: root)
        let key = [UInt8](repeating: 7, count: 32)
        let config = try AutomaticBackupConfiguration(folder: root, password: "", minutes: 15, keep: 4, vaultCopy: true)
        try store.save(config, key: key)
        let restored = try XCTUnwrap(store.load(key: key))
        XCTAssertTrue(restored.vaultCopy); XCTAssertEqual(3, restored.version)
        XCTAssertTrue(restored.password.isEmpty); XCTAssertNil(restored.recipientKeys); XCTAssertEqual(false, restored.requiresSigning)
        XCTAssertThrowsError(try AutomaticBackupConfiguration(folder: root, password: "not-allowed", minutes: 15, keep: 4, vaultCopy: true))
        let old = try AutomaticBackupConfiguration(folder: root, password: "export-password", minutes: 1, keep: 2)
        try store.save(old, key: key)
        XCTAssertFalse(try XCTUnwrap(store.load(key: key)).vaultCopy)
        let schema = Data(#"{"version":3,"mode":"vault-copy","folder":"/synthetic/backups","password":"","minutes":15,"keep":4}"#.utf8)
        let shared = try JSONDecoder().decode(AutomaticBackupConfiguration.self, from: schema)
        XCTAssertTrue(shared.vaultCopy); XCTAssertEqual("/synthetic/backups", try shared.destination().path)
    }

    func testRecordIsEncryptedBoundToVaultKeyAndNotDeletedOnWrongKey() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = AutomaticBackupCredentialStore(directory: root)
        let key = [UInt8](repeating: 7, count: 32)
        let config = try AutomaticBackupConfiguration(folder: root, password: "backup-test-secret", minutes: 15, keep: 10)
        try store.save(config, key: key)
        let raw = try Data(contentsOf: root.appendingPathComponent(AutomaticBackupCredentialStore.filename))
        XCTAssertNil(raw.range(of: Data("backup-test-secret".utf8)))
        let loaded = try XCTUnwrap(AutomaticBackupCredentialStore(directory: root).load(key: key))
        XCTAssertEqual("backup-test-secret", loaded.password); XCTAssertEqual(15, loaded.minutes)
        XCTAssertEqual("AutomaticBackupConfiguration(redacted)", loaded.description)
        XCTAssertThrowsError(try store.load(key: [UInt8](repeating: 8, count: 32)))
        XCTAssertTrue(store.exists)
        try store.forget(); XCTAssertFalse(store.exists)
    }
    func testRecipientConfigurationIsEncryptedAndReopensWithoutPasswordOrFileGrants() throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: root) }
        let store = AutomaticBackupCredentialStore(directory: root)
        let key = [UInt8](repeating: 7, count: 32)
        let certificate = Data("synthetic public certificate".utf8)
        let config = try AutomaticBackupConfiguration(folder: root, password: "", minutes: 15, keep: 3, recipientKeys: [certificate])
        try store.save(config, key: key)
        let raw = try Data(contentsOf: root.appendingPathComponent(AutomaticBackupCredentialStore.filename))
        XCTAssertNil(raw.range(of: certificate))
        let loaded = try XCTUnwrap(store.load(key: key))
        XCTAssertEqual(2, loaded.version); XCTAssertEqual("openpgp", loaded.mode)
        XCTAssertEqual("", loaded.password); XCTAssertEqual([certificate], loaded.recipientKeys)
        XCTAssertEqual(root.path, try loaded.destination().path)
        XCTAssertFalse(AutomaticBackupConfiguration.validRecipients([certificate, certificate]))
        XCTAssertFalse(AutomaticBackupConfiguration.validRecipients([Data()]))
        XCTAssertFalse(AutomaticBackupConfiguration.validRecipients([Data(repeating: 1, count: 32769)]))
    }

    func testSharedJvmConfigurationSchemaDecodesWithoutBookmarkAndRoundTrips() throws {
        // Mirrors the JVM schema vector without requiring simulator filesystem fixtures.
        let bytes = Data(#"{"version":2,"mode":"openpgp","folder":"/synthetic/automatic-backups","password":"","minutes":15,"keep":3,"recipientKeys":["cHVibGljLW9uZQ==","cHVibGljLXR3bw=="]}"#.utf8)
        let config = try JSONDecoder().decode(AutomaticBackupConfiguration.self, from: bytes)
        XCTAssertEqual(2, config.version); XCTAssertEqual("openpgp", config.mode)
        XCTAssertEqual("/synthetic/automatic-backups", try config.destination().path)
        XCTAssertEqual([Data("public-one".utf8), Data("public-two".utf8)], config.recipientKeys)
        let reloaded = try JSONDecoder().decode(AutomaticBackupConfiguration.self, from: JSONEncoder().encode(config))
        XCTAssertEqual(config.recipientKeys, reloaded.recipientKeys)
        XCTAssertTrue(reloaded.password.isEmpty)
    }

    func testSigningRequirementPersistsWithoutPrivateKeyOrPassphraseFields() throws {
        let config = try AutomaticBackupConfiguration(folder: FileManager.default.temporaryDirectory, password: "", minutes: 15, keep: 3,
            recipientKeys: [Data([1])], requiresSigning: true)
        let bytes = try JSONEncoder().encode(config)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        XCTAssertEqual(true, json["requiresSigning"] as? Bool)
        XCTAssertNil(json["signingKey"]); XCTAssertNil(json["signingPassphrase"])
        XCTAssertEqual(true, try JSONDecoder().decode(AutomaticBackupConfiguration.self, from: bytes).requiresSigning)
    }

}
