import XCTest
@testable import OfflineOTPKit

final class HardwareUnlockEnvelopeTests: XCTestCase {
    private struct Contract: Decodable { let vectors: [Vector] }
    private struct Vector: Decodable {
        let credentialId: Data
        let salt: Data
        let prf: Data
        let nonce: Data
        let vaultKey: Data
        let envelope: Data
    }
    func testSharedSyntheticVectorsAreByteIdenticalAndAuthenticateEveryByte() throws {
        let contract = try JSONDecoder().decode(Contract.self, from: Data(try GoldenCorpus.goldenText("hardware-unlock-envelope.json").utf8))
        for vector in contract.vectors {
            XCTAssertEqual(vector.envelope, try HardwareUnlockEnvelope.seal(credentialID: vector.credentialId,
                salt: vector.salt, prf: vector.prf, vaultKey: vector.vaultKey, nonce: Array(vector.nonce)))
            XCTAssertEqual(vector.vaultKey, HardwareUnlockEnvelope.open(vector.envelope, prf: vector.prf))
            let request = try XCTUnwrap(HardwareUnlockEnvelope.request(vector.envelope))
            XCTAssertEqual(vector.credentialId, request.credentialID); XCTAssertEqual(vector.salt, request.salt)
            XCTAssertEqual("HardwareUnlockRequest(redacted)", request.description)
            for offset in vector.envelope.indices {
                var changed = vector.envelope; changed[offset] ^= 1
                XCTAssertNil(HardwareUnlockEnvelope.open(changed, prf: vector.prf))
            }
            for size in 0..<vector.envelope.count {
                XCTAssertNil(HardwareUnlockEnvelope.open(Data(vector.envelope.prefix(size)), prf: vector.prf))
            }
            XCTAssertNil(HardwareUnlockEnvelope.open(vector.envelope, prf: Data(repeating: 0, count: 32)))
            XCTAssertNil(HardwareUnlockEnvelope.open(vector.envelope, prf: Data(repeating: 0, count: 31)))
        }
    }
    func testInvalidBoundsAndFreshNonces() throws {
        let id = Data([1]); let salt = Data(repeating: 2, count: 32)
        let prf = Data(repeating: 3, count: 32); let key = Data(repeating: 4, count: 32)
        XCTAssertThrowsError(try HardwareUnlockEnvelope.seal(credentialID: Data(), salt: salt, prf: prf, vaultKey: key))
        XCTAssertThrowsError(try HardwareUnlockEnvelope.seal(credentialID: Data(repeating: 0, count: 1025), salt: salt, prf: prf, vaultKey: key))
        XCTAssertThrowsError(try HardwareUnlockEnvelope.seal(credentialID: id, salt: Data(), prf: prf, vaultKey: key))
        XCTAssertThrowsError(try HardwareUnlockEnvelope.seal(credentialID: id, salt: salt, prf: prf, vaultKey: Data(repeating: 0, count: 15)))
        let first = try HardwareUnlockEnvelope.seal(credentialID: id, salt: salt, prf: prf, vaultKey: key)
        let second = try HardwareUnlockEnvelope.seal(credentialID: id, salt: salt, prf: prf, vaultKey: key)
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(key, HardwareUnlockEnvelope.open(first, prf: prf))
        XCTAssertNil(HardwareUnlockEnvelope.request(Data(repeating: 0, count: 3000)))
    }
}
