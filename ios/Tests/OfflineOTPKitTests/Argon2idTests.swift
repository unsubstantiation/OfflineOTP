import Foundation
import XCTest
@testable import OfflineOTPKit

/// RFC 7693 (BLAKE2b) and RFC 9106 (Argon2id v1.3) known-answer vectors plus cross-client parity
/// vectors shared with `core/src/test/kotlin/com/fairuniversal/offlineotp/Utilities/Argon2idTest.kt`.
final class Argon2idTests: XCTestCase {
    private func hex(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    func testBlake2bMatchesRfc7693Vectors() throws {
        XCTAssertEqual(
            hex(try Blake2b.hash([], outLen: 64)),
            "786a02f742015903c6c6fd852552d272912f4740e15847618a86e217f71f5419"
                + "d25e1031afee585313896444934eb04b903a685b1448b755d56f701afe9be2ce"
        )
        XCTAssertEqual(
            hex(try Blake2b.hash(Array("abc".utf8), outLen: 64)),
            "ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1"
                + "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923"
        )
    }

    func testArgon2idMatchesRfc9106Section5Point3Vector() throws {
        let derived = try Argon2id.derive(
            password: [UInt8](repeating: 0x01, count: 32),
            salt: [UInt8](repeating: 0x02, count: 16),
            memoryKiB: 32,
            iterations: 3,
            parallelism: 4,
            tagLength: 32,
            secret: [UInt8](repeating: 0x03, count: 8),
            associatedData: [UInt8](repeating: 0x04, count: 12)
        )
        XCTAssertEqual(
            hex(derived),
            "0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"
        )
    }

    func testArgon2idMatchesUnkeyedRfc9106Vector() throws {
        let derived = try Argon2id.derive(
            password: [UInt8](repeating: 0x01, count: 32),
            salt: [UInt8](repeating: 0x02, count: 16),
            memoryKiB: 32,
            iterations: 3,
            parallelism: 4,
            tagLength: 32
        )
        XCTAssertEqual(
            hex(derived),
            "03aab965c12001c9d7d0d2de33192c0494b684bb148196d73c1df1acaf6d0c2e"
        )
    }

    func testArgon2idMatchesProductionProfileAndParallelPath() throws {
        let salt = Array(UInt8(0x00)...UInt8(0x0F))
        let medium = try VaultCrypto.argon2id(
            password: GoldenCorpus.password,
            salt: salt,
            memoryKiB: 2048,
            iterations: 2,
            parallelism: 4
        )
        XCTAssertEqual(
            hex(medium),
            "53c3ec347d3dc57858378a850756dfbeebd9000cf7b1cbbe695cf4e4b7543196"
        )

        let production = try VaultCrypto.argon2id(
            password: GoldenCorpus.password,
            salt: salt,
            memoryKiB: 65_536,
            iterations: 3,
            parallelism: 4
        )
        XCTAssertEqual(
            hex(production),
            "e95b05c8927f84f547af8fb2f233b07e79d60e858534be027971f57fd99d6f03"
        )

        let credentials = try VaultCrypto.argon2idCredentials(
            password: GoldenCorpus.password,
            salt: salt,
            memoryKiB: 65_536,
            iterations: 3,
            parallelism: 4
        )
        XCTAssertEqual(
            hex(credentials.key),
            "931bc0ec226b1e7407439637dfa1b3e8b319b1193da7f3df11f13be52c540c5b"
        )
        XCTAssertEqual(
            hex(credentials.verifier),
            "5527fcaf7633c24e07a358b501003a1ecb5232698349fee4144714f93d31c399"
        )
    }

    func testArgon2idRejectsOutOfBoundsParameters() {
        let salt = Array(UInt8(0x00)...UInt8(0x0F))
        XCTAssertThrowsError(try Argon2id.derive(
            password: [1], salt: [1, 2, 3], memoryKiB: 64, iterations: 1, parallelism: 1, tagLength: 32
        ))
        XCTAssertThrowsError(try Argon2id.derive(
            password: [1], salt: salt, memoryKiB: 16, iterations: 1, parallelism: 4, tagLength: 32
        ))
        XCTAssertThrowsError(try Argon2id.derive(
            password: [1], salt: salt, memoryKiB: 524_288, iterations: 1, parallelism: 4, tagLength: 32
        ))
        XCTAssertThrowsError(try Argon2id.derive(
            password: [1], salt: salt, memoryKiB: 65_536, iterations: 32, parallelism: 4, tagLength: 32
        ))
    }
}
