import Foundation
import XCTest
@testable import OfflineOTPKit

final class LinScpVaultTests: XCTestCase {
    func testPlainKeySessionRoundTripPreservesOpaqueSessionMetadata() throws {
        let session = "{\"protocol\":\"webdavs\",\"host\":\"dav.example\",\"port\":443,\"key_password\":\"key-passphrase\",\"proxy_password\":\"proxy-secret\",\"remote_directory\":\"/files\",\"future_setting\":{\"keep\":42}}"
        let entry = try Entry.passwordFields(label: "WebDAV", issuer: "dav.example", fields: [
            .init(name: "url", value: "webdavs://dav.example:443"),
            .init(name: "username", value: "alice"), .init(name: "password", value: "server-secret"),
            .init(name: "linscp_session_json", value: session), .init(name: "linscp_future", value: "opaque")])
        let bytes = try LinScpVault.exportPlain(entries: [entry])
        XCTAssertFalse(LinScpVault.isEncryptedVault(bytes))
        let entries = try LinScpVault.importData(bytes)
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(try entries[0].passwordFields().value("linscp_session_json"), session)
        XCTAssertEqual(try entries[0].passwordFields().value("linscp_future"), "opaque")
        let encrypted = try LinScpVault.sealWithFreshParameters(entries: entries, password: "export-password")
        XCTAssertEqual(try LinScpVault.open(password: "export-password", data: encrypted)[0].passwordFields().value("linscp_session_json"), session)
        XCTAssertThrowsError(try LinScpVault.sealWithFreshParameters(entries: entries, password: ""))
        XCTAssertEqual(LinScpVault.exportFilename(Date(timeIntervalSince1970: 0), plainText: true), "key_session_1970-01-01_00-00-00.json")
        XCTAssertThrowsError(try LinScpVault.importData(Data("[]".utf8)))
        XCTAssertThrowsError(try LinScpVault.importData(Data("[1]".utf8)))
    }

    func testLscp1MatchesRustReferenceVector() throws {
        let masterKey = (0..<32).map(UInt8.init)
        let ciphertext =
            "lscp1$EBESExQVFhcYGRobHB0eH2O4ezzmYH370G39JNEWV3UKmNy5iaxBwNro1exoTvVdvdwtDnFxSYetybMyM3aAIA=="
        XCTAssertEqual(
            try LinScpVault.decryptLscp1Password(ciphertext, masterKey: masterKey),
            "S3cr3t!Pass#2026"
        )
        let wrongKey = [UInt8](repeating: 0x22, count: 32)
        XCTAssertThrowsError(try LinScpVault.decryptLscp1Password(ciphertext, masterKey: wrongKey))
    }

    func testLinScpSessionsIniAndLscpvlt2RoundTripThroughEncryptedBackup() throws {
        let masterKey = (0..<32).map(UInt8.init)
        let masterB64 = Data(masterKey).base64EncodedString()
        let encryptedPass =
            "lscp1$EBESExQVFhcYGRobHB0eH2O4ezzmYH390G39JNEWV3UKmNy5iaxBwNro1exoTvVdvdwtDnFxSYetybMyM3aAIA=="
        _ = encryptedPass

        let validCipher =
            "lscp1$EBESExQVFhcYGRobHB0eH2O4ezzmYH370G39JNEWV3UKmNy5iaxBwNro1exoTvVdvdwtDnFxSYetybMyM3aAIA=="
        let ini = """
        [Prod SFTP]
        protocol=sftp
        host=prod.example.org
        port=2222
        username=deploy
        password=\(validCipher)
        """
        let iniData = Data(ini.utf8)
        XCTAssertTrue(LinScpVault.needsSessionMasterKey(iniData))
        let imported = try LinScpVault.importData(iniData, masterKeyData: Data(masterB64.utf8))
        XCTAssertEqual(imported.count, 1)
        XCTAssertEqual(imported[0].kind, .password)
        XCTAssertEqual(imported[0].label, "Prod SFTP")
        XCTAssertEqual(imported[0].issuer, "prod.example.org")

        let sealed = try LinScpVault.seal(
            entries: imported,
            password: "linscp-backup-password",
            salt: Array(0..<16).map(UInt8.init),
            nonce: Array(16..<28).map(UInt8.init)
        )
        XCTAssertTrue(LinScpVault.isEncryptedVault(sealed))

        // Standard EncryptedBackup.open transparently decrypts LSCPVLT2 vaults too!
        let doc = try EncryptedBackup.open(password: "linscp-backup-password", data: sealed)
        let reopened = try VaultDocument.decode(doc)
        XCTAssertEqual(reopened.count, 1)
        XCTAssertEqual(reopened[0].label, "Prod SFTP")

        XCTAssertThrowsError(try LinScpVault.open(password: "wrong-password", data: sealed))
    }
}
