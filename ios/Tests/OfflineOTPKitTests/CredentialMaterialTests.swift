import Foundation
import XCTest
@testable import OfflineOTPKit

final class CredentialMaterialTests: XCTestCase {
    func testSshMaterialAndConnectionRoundTrip() throws {
        let encoded = try SshKeyMaterial.encode(
            publicKey: "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest root@host\n",
            privateKey: "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----\n"
        )
        let decoded = try SshKeyMaterial.decode(encoded)
        XCTAssertEqual(decoded.publicKey, "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITest root@host")
        XCTAssertTrue(decoded.privateKey.hasPrefix("-----BEGIN OPENSSH PRIVATE KEY-----"))

        let conn = try SshConnection.of(host: "srv.example.com", port: 22, username: "ops", password: "pw")
        XCTAssertEqual(conn?.summary, "ops@srv.example.com")
        XCTAssertEqual(conn?.description, "SshConnection.Details(redacted)")
    }

    func testBrowserPasswordCsvAndArchiveRoundTrip() throws {
        let csv = Data("name,url,username,password,note\r\nGitHub,https://github.com,alice,\"s3cr,et\",dev\r\n".utf8)
        let entries = try PasswordImport.entries(filename: "chrome.csv", data: csv, mode: .individual)
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entries[0].kind, .password)
        XCTAssertEqual(entries[0].issuer, "GitHub")
        XCTAssertEqual(entries[0].label, "alice")
        let fields = try entries[0].passwordFields()
        XCTAssertEqual(fields.value("password"), "s3cr,et")
        XCTAssertEqual(fields.value("note"), "dev")

        let archive = try PasswordImport.entries(filename: "safari.csv", data: csv, mode: .archive)
        XCTAssertEqual(archive.count, 1)
        XCTAssertEqual(archive[0].kind, .passwordArchive)
        XCTAssertEqual(archive[0].passwordArchiveBytes, csv)
    }

    func testFairEmailAndThunderbirdInterchange() throws {
        let account = try EmailMaterial.create(
            name: "Work Mail",
            address: "alice@example.com",
            incomingHost: "imap.example.com",
            incomingPort: 993,
            incomingEncryption: 0,
            incomingProtocol: 0,
            incomingUser: "alice@example.com",
            incomingPassword: "imap-secret",
            outgoingHost: "smtp.example.com",
            outgoingPort: 465,
            outgoingEncryption: 0,
            outgoingUser: "alice@example.com",
            outgoingPassword: "smtp-secret"
        )
        let emailEntry = try Entry.email(account: account)
        XCTAssertEqual(emailEntry.kind, .email)
        let masked = EmailMaterial.details(account: try emailEntry.emailAccount(), revealSecrets: false)
        XCTAssertTrue(masked.contains { $0.value == EmailMaterial.secretMask })

        let exportedFairEmail = try EmailMaterial.exportBackup([account])
        let reimportedFairEmail = try EmailMaterial.importBackup(exportedFairEmail)
        XCTAssertEqual(reimportedFairEmail.count, 1)
        XCTAssertEqual(reimportedFairEmail[0].address, "alice@example.com")

        let tbCsv = try ThunderbirdImport.exportCsv(entries: [emailEntry])
        let tbEntries = try ThunderbirdImport.entries(from: tbCsv)
        XCTAssertEqual(tbEntries.count, 1)
        XCTAssertEqual(try tbEntries[0].emailAccount().address, "alice@example.com")
    }
}
