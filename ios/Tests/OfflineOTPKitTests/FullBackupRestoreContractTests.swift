import Foundation
import XCTest
@testable import OfflineOTPKit

final class FullBackupRestoreContractTests: XCTestCase {
    private struct Corpus: Decodable { let vectors: [Vector] }
    private struct Vector: Decodable {
        let format: String
        let variant: String
        let password: String
        let dataHex: String
        let plaintext: String
    }
    private func bytes(_ hex: String) -> Data {
        let chars = Array(hex)
        return Data(stride(from: 0, to: chars.count, by: 2).map {
            UInt8(String(chars[$0...($0 + 1)]), radix: 16)!
        })
    }

    func testIndependentAndOtpVectorsAndExplicitSelection() throws {
        let corpus = try JSONDecoder().decode(Corpus.self,
            from: GoldenCorpus.golden("full-backup-restore.json"))
        for row in corpus.vectors {
            let data = bytes(row.dataHex)
            let text = try EncryptedBackup.openSelected(password: row.password, data: data,
                format: row.format, andOtpVariant: row.variant)
            XCTAssertEqual(text, row.plaintext)
            let entries = try VaultDocument.decode(text)
            XCTAssertEqual(entries.count, 2)
            XCTAssertEqual(entries[1].counter, 9)
            XCTAssertThrowsError(try EncryptedBackup.openSelected(password: row.password,
                data: data, format: "vault-copy"))
            if row.variant != "plain" {
                XCTAssertThrowsError(try EncryptedBackup.openSelected(password: "wrong", data: data,
                    format: row.format, andOtpVariant: row.variant))
                var tampered = data
                tampered[tampered.count - 1] ^= 1
                XCTAssertThrowsError(try EncryptedBackup.openSelected(password: row.password,
                    data: tampered, format: row.format, andOtpVariant: row.variant))
            }
        }
    }

    func testInvalidPlaintextAndUnknownSelectionAreRejected() throws {
        for text in ["[{},{}]", "[] trailing"] {
            XCTAssertThrowsError(try EncryptedBackup.openSelected(password: "",
                data: Data(text.utf8), format: "andotp", andOtpVariant: "plain"))
        }
        XCTAssertThrowsError(try EncryptedBackup.openSelected(password: "",
            data: Data([0xc0, 0xaf]), format: "andotp", andOtpVariant: "plain"))
        XCTAssertThrowsError(try EncryptedBackup.openSelected(password: "",
            data: Data("[]".utf8), format: "unknown"))
        XCTAssertEqual(try EncryptedBackup.openSelected(password: "", data: Data("[]".utf8),
            format: "andotp", andOtpVariant: "plain"), "[]")
    }
}
