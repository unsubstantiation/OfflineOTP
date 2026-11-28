import Foundation
import XCTest
@testable import OfflineOTPKit

final class VaultContainerTests: XCTestCase {
    private func hex(_ text: String) throws -> Data {
        var bytes = Data(); var index = text.startIndex
        while index < text.endIndex {
            let next = text.index(index, offsetBy: 2)
            bytes.append(try XCTUnwrap(UInt8(text[index..<next], radix: 16))); index = next
        }
        return bytes
    }
    func testIndependentJvmSwiftVectorsAndPortableRestore() throws {
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: GoldenCorpus.golden("vault-container.json")) as? [String: Any])
        for row in try XCTUnwrap(root["vectors"] as? [[String: Any]]) {
            let file = try hex(XCTUnwrap(row["fileHex"] as? String))
            let password = try XCTUnwrap(row["password"] as? String)
            let plaintext = try XCTUnwrap(row["plaintext"] as? String)
            let key = Array(try hex(XCTUnwrap(row["keyHex"] as? String)))
            XCTAssertEqual(Data(plaintext.utf8), try VaultContainer.open(password: password, data: file))
            XCTAssertEqual(Data(plaintext.utf8), try VaultContainer.openWithKey(key, data: file))
            XCTAssertEqual(plaintext, try EncryptedBackup.open(password: password, data: file))
            // Exercise the Swift writer against the same header/key contract as the JVM writer.
            let rewritten = try VaultContainer.seal(key: key, header: VaultContainer.header(file),
                plaintext: Data(plaintext.utf8))
            XCTAssertEqual(rewritten.prefix(VaultContainer.headerBytes), file.prefix(VaultContainer.headerBytes))
            XCTAssertEqual(rewritten.count, Data(plaintext.utf8).count + VaultContainer.overheadBytes)
            XCTAssertEqual(try VaultContainer.open(password: password, data: rewritten), Data(plaintext.utf8))
            XCTAssertEqual(try EncryptedBackup.openSelected(password: password, data: rewritten,
                format: "vault-copy"), plaintext)
            XCTAssertThrowsError(try VaultContainer.open(password: "wrong", data: file))
            for offset in file.indices {
                var changed = file; changed[offset] ^= 1
                XCTAssertThrowsError(try VaultContainer.openWithKey(key, data: changed))
            }
        }
    }
    func testExplicitLegacyMigrationPreservesPortableRecoveryAndOriginalRollback() throws {
        let original = try GoldenCorpus.golden("secrets.dat")
        let (vault, file) = GoldenCorpus.fixtureVault(secrets: original)
        let password = GoldenCorpus.password
        XCTAssertFalse(VaultContainer.isContainer(original))
        XCTAssertNoThrow(try vault.open(password: password))
        XCTAssertEqual(original, file.data)
        XCTAssertThrowsError(try vault.migrateToContainer(password: "wrong password"))
        XCTAssertEqual(original, file.data)
        try vault.migrateToContainer(password: password)
        let migrated = try XCTUnwrap(file.data)
        XCTAssertTrue(VaultContainer.isContainer(migrated))
        XCTAssertEqual(try VaultCrypto.open(original, key: vault.open(password: password).key),
                       try VaultContainer.open(password: password, data: migrated))
        let orphan = PasswordVault(vaultFile: MemoryByteStore(data: migrated), backupFile: MemoryByteStore(data: nil), settings: MemorySettings())
        XCTAssertNoThrow(try orphan.open(password: password))
        XCTAssertTrue(vault.restoreBackup())
        XCTAssertEqual(original, file.data)
        XCTAssertNoThrow(try vault.open(password: password))
    }

    func testCopiedContainerOpensAndSavesWithoutTheOriginalSettings() throws {
        let file = MemoryByteStore(data: nil)
        let engine = PasswordVault(vaultFile: file, backupFile: MemoryByteStore(data: nil), settings: MemorySettings())
        try engine.create(password: "recovery password")
        let opened = try engine.open(password: "recovery password")
        let snapshot = try engine.snapshot(key: opened.key)
        XCTAssertEqual(file.data, snapshot)
        let copied = MemoryByteStore(data: snapshot)
        let restored = PasswordVault(vaultFile: copied, backupFile: MemoryByteStore(data: nil), settings: MemorySettings())
        let recovered = try restored.open(password: "recovery password")
        XCTAssertEqual(engine.vaultIdentifier, restored.vaultIdentifier)
        try restored.save(key: recovered.key, entries: recovered.entries)
        XCTAssertNoThrow(try restored.open(password: "recovery password"))
    }
    func testMalformedVersionsTruncationAndKdfParametersReject() throws {
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: GoldenCorpus.golden("vault-container.json")) as? [String: Any])
        let row = try XCTUnwrap((root["vectors"] as? [[String: Any]])?.first)
        let file = try hex(XCTUnwrap(row["fileHex"] as? String))
        for length in 0...VaultContainer.overheadBytes { XCTAssertThrowsError(try VaultContainer.header(Data(file.prefix(length)))) }
        for offset in [8, 9, 10, 11, 12, 16, 20] {
            var changed = file; changed[offset] = 0x7f
            XCTAssertTrue(VaultContainer.isContainer(changed)); XCTAssertThrowsError(try VaultContainer.header(changed))
        }
    }
}
