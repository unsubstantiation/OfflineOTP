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

    func testMultiIdentityEmailEditPasswordAndSettingsParity() throws {
        let base = try EmailMaterial.create(
            name: "Primary Mail",
            address: "alice@example.com",
            incomingHost: "imap.example.com",
            incomingPort: 993,
            incomingEncryption: 0,
            incomingProtocol: 0,
            incomingUser: "alice",
            incomingPassword: "imap-old",
            outgoingHost: "smtp.example.com",
            outgoingPort: 465,
            outgoingEncryption: 0,
            outgoingUser: "alice",
            outgoingPassword: "smtp1-old"
        )
        var acct = base.account
        acct["poll_interval"] = .int(15)
        acct["pop"] = .bool(false)
        var id1 = base.identity
        var id2 = base.identity
        id2["primary"] = .bool(false)
        id2["name"] = .string("Alias Sender")
        id2["email"] = .string("alias@example.com")
        id2["host"] = .string("smtp2.example.com")
        id2["password"] = .string("smtp2-old")
        id1["primary"] = .bool(true)
        let multiAccount = EmailMaterial.Account(account: acct, identities: [id1, id2])
        let entry = try Entry.email(account: multiAccount, tags: ["ops,team"])

        // 1. Comprehensive multi-identity edit preserves extensions, updates artwork/issuer/tags, syncs legacy flags.
        var fullEdit = try EmailEditInput.fromEntry(entry)
        fullEdit.issuer = "Custom Mail Issuer"
        fullEdit.label = "Ops Mailbox"
        fullEdit.hiddenLabel = "Hidden Ops"
        fullEdit.thumbnail = "Fastmail"
        fullEdit.tags = "ops,team\nprod"
        if let protoIdx = fullEdit.account.firstIndex(where: { $0.name == "protocol" }) {
            fullEdit.account = EmailFieldInput.updated(fullEdit.account, index: protoIdx, value: "1")
        }
        if let pw2Idx = fullEdit.identities[1].firstIndex(where: { $0.name == "password" }) {
            fullEdit.identities[1] = EmailFieldInput.updated(fullEdit.identities[1], index: pw2Idx, value: "smtp2-edited")
        }
        let editedEntry = try fullEdit.build(existing: entry)
        XCTAssertEqual(editedEntry.issuer, "Custom Mail Issuer")
        XCTAssertEqual(editedEntry.label, "Ops Mailbox")
        XCTAssertEqual(editedEntry.hiddenLabel, "Hidden Ops")
        XCTAssertEqual(editedEntry.thumbnail, "Fastmail")
        XCTAssertEqual(editedEntry.tags, ["ops,team", "prod"])
        let decodedEdited = try editedEntry.emailAccount()
        XCTAssertEqual(decodedEdited.account["pop"]?.boolValue, true)
        XCTAssertEqual(decodedEdited.account["poll_interval"]?.intValue, 15)
        XCTAssertEqual(decodedEdited.identities[1]["password"]?.stringValue, "smtp2-edited")

        // 2. Masked password-only rotation keeps blank fields untouched and preserves Entry metadata.
        var pwEdit = try EmailPasswordInput.fromEntry(editedEntry)
        XCTAssertEqual(pwEdit.outgoing, ["", ""])
        pwEdit.incoming = ""
        pwEdit.outgoing = ["smtp1-rotated", ""]
        let rotatedEntry = try pwEdit.build(existing: editedEntry)
        XCTAssertEqual(rotatedEntry.issuer, "Custom Mail Issuer")
        XCTAssertEqual(rotatedEntry.thumbnail, "Fastmail")
        let decodedRotated = try rotatedEntry.emailAccount()
        XCTAssertEqual(decodedRotated.account["password"]?.stringValue, "imap-old")
        XCTAssertEqual(decodedRotated.identities[0]["password"]?.stringValue, "smtp1-rotated")
        XCTAssertEqual(decodedRotated.identities[1]["password"]?.stringValue, "smtp2-edited")

        // 3. Connection settings edit removes non-primary SMTP #2 while keeping primary credentials & metadata.
        var settingsEdit = try EmailSettingsInput.fromEntry(rotatedEntry)
        XCTAssertEqual(settingsEdit.removableIdentities, [1])
        settingsEdit.accountName = "Updated Account Name"
        settingsEdit.removedIdentities = [1]
        let trimmedEntry = try settingsEdit.build(existing: rotatedEntry)
        XCTAssertEqual(trimmedEntry.issuer, "Custom Mail Issuer")
        let decodedTrimmed = try trimmedEntry.emailAccount()
        XCTAssertEqual(decodedTrimmed.identities.count, 1)

        // 4. Account and password copy helpers resolve credentials across entry kinds without reveal.
        XCTAssertEqual(rotatedEntry.accountToCopy, "alice@example.com")
        XCTAssertEqual(rotatedEntry.passwordChoicesToCopy.map(\.secret),
                       ["imap-old", "smtp1-rotated", "smtp2-edited", "Hidden Ops"])
        let noteEntry = try EntryDraft.create(kind: .note, issuer: "Notes", label: "Memo", secret: "private")
        XCTAssertEqual(noteEntry.accountToCopy, "")
        XCTAssertTrue(noteEntry.passwordChoicesToCopy.isEmpty)
        XCTAssertEqual(decodedTrimmed.account["name"]?.stringValue, "Updated Account Name")
        XCTAssertEqual(decodedTrimmed.identities[0]["password"]?.stringValue, "smtp1-rotated")
    }
}
