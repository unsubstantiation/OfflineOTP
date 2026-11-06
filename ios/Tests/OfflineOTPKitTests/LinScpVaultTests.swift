import XCTest
@testable import OfflineOTPKit

final class LinScpVaultTests: XCTestCase {
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
