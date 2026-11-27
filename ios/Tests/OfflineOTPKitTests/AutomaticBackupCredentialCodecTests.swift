import XCTest
@testable import OfflineOTPKit

final class AutomaticBackupCredentialCodecTests: XCTestCase {
    private struct Contract: Decodable { let vectors: [Vector] }
    private struct Vector: Decodable { let vaultKey: Data; let nonce: Data; let plaintext: Data; let encrypted: Data }
    func testIndependentSharedVectorsAndTampering() throws {
        let contract = try JSONDecoder().decode(Contract.self, from: Data(try GoldenCorpus.goldenText("automatic-backup-credentials.json").utf8))
        for vector in contract.vectors {
            XCTAssertEqual(vector.encrypted, try AutomaticBackupCredentialCodec.seal(key: Array(vector.vaultKey), plaintext: vector.plaintext, nonce: Array(vector.nonce)))
            XCTAssertEqual(vector.plaintext, try AutomaticBackupCredentialCodec.open(key: Array(vector.vaultKey), encrypted: vector.encrypted))
            XCTAssertThrowsError(try AutomaticBackupCredentialCodec.open(key: [UInt8](repeating: 9, count: 32), encrypted: vector.encrypted))
            for index in vector.encrypted.indices {
                var changed = vector.encrypted; changed[index] ^= 1
                XCTAssertThrowsError(try AutomaticBackupCredentialCodec.open(key: Array(vector.vaultKey), encrypted: changed))
            }
        }
    }
}
