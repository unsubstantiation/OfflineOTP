import Foundation
import XCTest
import OfflineOTPKit
@testable import OfflineOTP

final class SharedVaultProtocolAdapterTests: XCTestCase {
    private struct Corpus: Decodable { let headers: [Header]; let routes: [Route] }
    private struct Header: Decodable {
        let name: String; let family: String; let prefixHex: String; let totalBytes: Int; let error: String?
        let memory: Int?; let iterations: Int?; let lanes: Int?; let saltHex: String?
    }
    private struct Route: Decodable {
        let name: String; let prefixHex: String; let totalBytes: Int; let format: String; let variant: String; let route: String?
    }
    private func hex(_ text: String) throws -> Data {
        var out = Data(); var index = text.startIndex
        while index < text.endIndex {
            let end = text.index(index, offsetBy: 2)
            out.append(try XCTUnwrap(UInt8(text[index..<end], radix: 16))); index = end
        }
        return out
    }
    private func corpus() throws -> Corpus {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "portable-vault-protocol", withExtension: "json"))
        return try JSONDecoder().decode(Corpus.self, from: Data(contentsOf: url))
    }
    func testSharedAndStandalonePoliciesAgreeWithTheIndependentHeaderCorpus() throws {
        let shared = SharedVaultProtocolAdapter(), native = NativeVaultProtocolAdapter()
        for row in try corpus().headers {
            let prefix = try hex(row.prefixHex)
            // A short bridge prefix is not a short file. Padding it would invent missing salt bytes.
            if prefix.count < (row.family == "vault" ? 40 : 30), row.error == "SIZE", row.totalBytes > prefix.count { continue }
            var full = Data(prefix.prefix(row.totalBytes))
            if full.count < row.totalBytes { full.append(Data(repeating: 0, count: row.totalBytes - full.count)) }
            if row.error != nil {
                if row.family == "vault" {
                    XCTAssertThrowsError(try shared.vaultHeader(full), row.name)
                    XCTAssertThrowsError(try try native.vaultHeader(full), row.name)
                } else {
                    XCTAssertThrowsError(try shared.backupHeader(full), row.name)
                    XCTAssertThrowsError(try native.backupHeader(full), row.name)
                }
            } else if row.family == "vault" {
                XCTAssertEqual(try shared.vaultHeader(full), try native.vaultHeader(full), row.name)
                let header = try VaultContainer.header(full, protocolPolicy: shared)
                XCTAssertEqual(header.memoryKiB, row.memory); XCTAssertEqual(header.iterations, row.iterations)
                XCTAssertEqual(header.parallelism, row.lanes); XCTAssertEqual(Data(header.salt), try hex(XCTUnwrap(row.saltHex)))
                XCTAssertEqual(try shared.makeVaultHeader(kdf: header.kdf, salt: header.salt, memoryKiB: header.memoryKiB,
                    iterations: header.iterations, parallelism: header.parallelism), Data(full.prefix(40)))
            } else {
                let header = try shared.backupHeader(full)
                XCTAssertEqual(header, try native.backupHeader(full), row.name)
                XCTAssertEqual(header.memoryKiB, row.memory); XCTAssertEqual(header.iterations, row.iterations)
                if header.kdf == .argon2id {
                    XCTAssertEqual(try shared.makeBackupHeader(salt: header.salt, memoryKiB: header.memoryKiB,
                        iterations: header.iterations, parallelism: header.parallelism), Data(full.prefix(30)))
                }
            }
        }
    }
    func testExplicitAndAutomaticRoutesNeverFallBack() throws {
        let names = ["VAULT_COPY": "vault-copy", "OFFLINEOTP": "offlineotp", "ANDOTP_PBKDF2": "andotp-pbkdf2",
                     "ANDOTP_PRE063": "pre063", "PLAIN": "plain", "LINSCP": "linscp"]
        for row in try corpus().routes {
            let prefix = try hex(row.prefixHex)
            var full = Data(prefix.prefix(row.totalBytes))
            if full.count < row.totalBytes { full.append(Data(repeating: 0, count: row.totalBytes - full.count)) }
            let policies: [VaultProtocolAdapter] = [SharedVaultProtocolAdapter(), NativeVaultProtocolAdapter()]
            for policy in policies {
                if let route = row.route { XCTAssertEqual(try policy.route(full, format: row.format, variant: row.variant), names[route], row.name) }
                else { XCTAssertThrowsError(try policy.route(full, format: row.format, variant: row.variant), row.name) }
            }
        }
    }
    func testBackupWriterAndNativeOracleRemainByteIdenticalWithTheSharedPolicy() throws {
        let shared = SharedVaultProtocolAdapter()
        let salt = Array(UInt8(0)...UInt8(15)), nonce = Array(UInt8(32)...UInt8(43))
        let password = "synthetic-shared-backup", plain = "[]"
        let written = try EncryptedBackup.sealArgon2id(password: password, plain: plain, memoryKiB: 32,
            iterations: 1, parallelism: 1, salt: salt, nonce: nonce, protocolPolicy: shared)
        XCTAssertEqual(written, try EncryptedBackup.sealArgon2id(password: password, plain: plain, memoryKiB: 32,
            iterations: 1, parallelism: 1, salt: salt, nonce: nonce))
        XCTAssertEqual(try EncryptedBackup.openSelected(password: password, data: written, format: "offlineotp", protocolPolicy: shared), plain)
        XCTAssertThrowsError(try EncryptedBackup.openSelected(password: password, data: written, format: "andotp", protocolPolicy: shared))
        XCTAssertThrowsError(try EncryptedBackup.openSelected(password: "wrong synthetic", data: written, format: "offlineotp", protocolPolicy: shared))
        var unsupported = written; unsupported[4] = 2
        XCTAssertThrowsError(try EncryptedBackup.open(password: password, data: unsupported, protocolPolicy: shared))
        var tampered = written; tampered[tampered.count - 1] ^= 1
        XCTAssertThrowsError(try EncryptedBackup.openSelected(password: password, data: tampered, format: "offlineotp", protocolPolicy: shared))
    }

    func testActualCryptoProvidersOpenTheIndependentVaultVectorsWithTheSharedPolicy() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "vault-container", withExtension: "json"))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let shared = SharedVaultProtocolAdapter()
        for row in try XCTUnwrap(root["vectors"] as? [[String: Any]]) {
            let full = try hex(XCTUnwrap(row["fileHex"] as? String)); let key = Array(try hex(XCTUnwrap(row["keyHex"] as? String)))
            let password = try XCTUnwrap(row["password"] as? String), plain = try XCTUnwrap(row["plaintext"] as? String)
            XCTAssertEqual(try VaultContainer.open(password: password, data: full, protocolPolicy: shared), Data(plain.utf8))
            let header = try VaultContainer.header(full, protocolPolicy: shared)
            let sealed = try VaultContainer.seal(key: key, header: header, plaintext: Data(plain.utf8), protocolPolicy: shared)
            XCTAssertEqual(Data(sealed.prefix(40)), Data(full.prefix(40)))
            XCTAssertEqual(try EncryptedBackup.openSelected(password: password, data: sealed, format: "vault-copy", protocolPolicy: shared), plain)
            XCTAssertThrowsError(try VaultContainer.open(password: "wrong synthetic", data: full, protocolPolicy: shared))
            var tampered = full; tampered[24] ^= 1
            XCTAssertThrowsError(try VaultContainer.openWithKey(key, data: tampered, protocolPolicy: shared))
        }
    }
}
