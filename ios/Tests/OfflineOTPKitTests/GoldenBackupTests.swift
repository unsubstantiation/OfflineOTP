import Foundation
import XCTest
@testable import OfflineOTPKit

/// The encrypted-backup fixture and the three real desktop exports that go with it.
///
/// A restore path is where data loss happens, so these tests use blobs the desktop client actually
/// produced: a Unicode note, an SSH key larger than the OTP seed limit, and entries whose details are
/// marked visible.
final class GoldenBackupTests: XCTestCase {
    func testTheGoldenBackupOpensToTheSharedDocument() throws {
        let opened = try EncryptedBackup.open(password: GoldenCorpus.password,
                                              data: try GoldenCorpus.golden("otp_accounts.json.aes"))
        XCTAssertEqual(opened, try GoldenCorpus.goldenText("expected-vault.json"))
    }

    func testTheGoldenBackupReframesToTheSameBytes() throws {
        // Deterministic re-seal with the fixture's own salt, nonce and iteration count. This is the
        // test that proves the header framing and the full-key (not halved) derivation.
        let resealed = try EncryptedBackup.seal(
            password: GoldenCorpus.password,
            plain: try GoldenCorpus.goldenText("expected-vault.json"),
            iterations: GoldenCorpus.backupIterations,
            salt: GoldenCorpus.backupSalt,
            nonce: GoldenCorpus.backupNonce)
        XCTAssertEqual(Array(resealed), Array(try GoldenCorpus.golden("otp_accounts.json.aes")))
    }

    func testTheHeaderParsesTheDocumentedFraming() throws {
        let data = try GoldenCorpus.golden("otp_accounts.json.aes")
        let header = try EncryptedBackup.parse(data)
        XCTAssertEqual(header.iterations, GoldenCorpus.backupIterations)
        XCTAssertEqual(header.salt, GoldenCorpus.backupSalt)
        XCTAssertEqual(header.encrypted.count,
                       data.count - EncryptedBackup.iterationBytes - EncryptedBackup.saltBytes,
                       "everything after the salt is nonce + ciphertext + tag")
        XCTAssertGreaterThanOrEqual(header.encrypted.count,
                                    EncryptedBackup.nonceBytes + EncryptedBackup.tagBytes)
    }

    func testARealDesktopNoteExportKeepsItsUnicodeNote() throws {
        let entries = try EncryptedBackup.openEntries(
            password: GoldenCorpus.password,
            data: try GoldenCorpus.golden("desktop-note-export.json.aes"))
        guard let note = entries.first(where: { $0.kind == .note }) else {
            return XCTFail("the note export lost its note")
        }
        XCTAssertEqual(note.label, "Recovery")
        XCTAssertEqual(note.tags, ["private"])
        guard let bytes = note.secretBytes, let text = String(bytes: bytes, encoding: .utf8) else {
            return XCTFail("the note text is not readable UTF-8")
        }
        XCTAssertFalse(text.isEmpty)
        XCTAssertTrue(text.contains("\n"), "the fixture note is multiline")
        XCTAssertLessThanOrEqual(text.unicodeScalars.count, InputPolicy.maxNoteCharacters)
    }

    func testARealDesktopSshExportKeepsItsKeyMaterial() throws {
        let entries = try EncryptedBackup.openEntries(
            password: GoldenCorpus.password,
            data: try GoldenCorpus.golden("desktop-ssh-export.json.aes"))
        guard let key = entries.first(where: { $0.kind == .sshKey }) else {
            return XCTFail("the SSH export lost its key")
        }
        XCTAssertEqual(key.label, "SSH server")
        // The framed key material is far larger than an OTP seed: the per-kind limit has to allow it.
        XCTAssertGreaterThan(key.secretBytes?.count ?? 0, InputPolicy.maxSecretBytes)
        XCTAssertLessThanOrEqual(key.secretBytes?.count ?? 0, SecretLimit.sshKey)
    }

    func testARealDesktopPrivacyExportKeepsHiddenAndRevealedEntries() throws {
        let entries = try EncryptedBackup.openEntries(
            password: GoldenCorpus.password,
            data: try GoldenCorpus.golden("desktop-privacy-export.json.aes"))
        XCTAssertEqual(entries.count, 4)
        // The fixture reveals an OTP account and an SSH key, and conceals a HOTP account and a note.
        let revealed = entries.filter { $0.detailsVisible }.map(\.label).sorted()
        XCTAssertEqual(revealed, ["Key", "alice@example.com"])
    }

    func testAWrongBackupPasswordIsRejected() throws {
        let data = try GoldenCorpus.golden("otp_accounts.json.aes")
        XCTAssertThrowsError(try EncryptedBackup.open(password: GoldenCorpus.password + "-wrong",
                                                      data: data))
    }

    func testATruncatedBackupIsRejectedBeforeDeriving() throws {
        let data = try GoldenCorpus.golden("otp_accounts.json.aes")
        let truncated = Data(data.prefix(EncryptedBackup.minBytes - 1))
        XCTAssertThrowsError(try EncryptedBackup.parse(truncated)) { error in
            XCTAssertEqual(error as? EncryptedBackupError, .truncated)
        }
    }

    func testAnAbsurdIterationCountIsRefusedBeforeAnyDerivation() throws {
        var data = Data([0x7F, 0xFF, 0xFF, 0xFF])
        let rest = try GoldenCorpus.golden("otp_accounts.json.aes").dropFirst(EncryptedBackup.iterationBytes)
        data.append(contentsOf: rest)
        XCTAssertThrowsError(try EncryptedBackup.parse(data)) { error in
            XCTAssertEqual(error as? EncryptedBackupError, .unsupportedIterations)
        }
    }
}
