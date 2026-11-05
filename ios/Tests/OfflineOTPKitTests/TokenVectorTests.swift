import Foundation
import XCTest
@testable import OfflineOTPKit

/// Token vectors produced by `scripts/ios/generate-token-vectors.py`.
///
/// That script checks itself against the published RFC 4226 and RFC 6238 vectors before it writes
/// anything, so these are numbers an independent implementation agrees on — not the answers this
/// implementation happens to give. The seeds are the ones in the shared golden vault, so a seed this
/// client reads out of a real vault is also the seed it uses.
final class TokenVectorTests: XCTestCase {
    // MARK: - Published vectors, inline so the Swift side is checked even without the file

    func testRFC4226HOTPVectors() throws {
        let secret = Array("12345678901234567890".utf8)
        let expected = ["755224", "287082", "359152", "969429", "338314",
                        "254676", "287922", "162583", "399871", "520489"]
        for (counter, code) in expected.enumerated() {
            XCTAssertEqual(try TokenCalculator.hotp(secret: secret, counter: Int64(counter),
                                                    digits: 6, algorithm: .sha1),
                           code, "RFC 4226 counter \(counter)")
        }
    }

    func testRFC6238TOTPVectors() throws {
        let vectors: [(OtpHashAlgorithm, String, [Int64: String])] = [
            (.sha1, "12345678901234567890", [
                59: "94287082", 1111111109: "07081804", 1111111111: "14050471",
                1234567890: "89005924", 2000000000: "69279037", 20000000000: "65353130"]),
            (.sha256, "12345678901234567890123456789012", [
                59: "46119246", 1111111109: "68084774", 1111111111: "67062674",
                1234567890: "91819424", 2000000000: "90698825", 20000000000: "77737706"]),
            (.sha512, "1234567890123456789012345678901234567890123456789012345678901234", [
                59: "90693936", 1111111109: "25091201", 1111111111: "99943326",
                1234567890: "93441116", 2000000000: "38618901", 20000000000: "47863826"]),
        ]
        for (algorithm, secretText, cases) in vectors {
            let secret = Array(secretText.utf8)
            for (unixTime, expected) in cases {
                XCTAssertEqual(try TokenCalculator.totp(secret: secret, period: 30,
                                                        unixTime: unixTime, digits: 8,
                                                        algorithm: algorithm),
                               expected, "RFC 6238 \(algorithm) t=\(unixTime)")
            }
        }
    }

    // MARK: - The shared corpus

    func testTheGeneratorsConstantsAreTheOnesThisClientUses() throws {
        let document = try vectors()
        XCTAssertEqual(document["steamAlphabet"] as? String,
                       String(TokenCalculator.steamAlphabet))
        XCTAssertEqual(document["motpStepSeconds"] as? Int64,
                       TokenCalculator.motpStepSeconds)
    }

    func testTOTPAndSteamVectorsMatchTheGoldenSeed() throws {
        let totp = try object(try vectors(), "totp")
        let secretText = try string(totp, "secretBase32")
        let secret = try XCTUnwrap(Base32.decode(secretText))
        let digits = Int(try XCTUnwrap(totp["digits"] as? Int64))
        let period = Int(try XCTUnwrap(totp["period"] as? Int64))
        let algorithm = try XCTUnwrap(OtpHashAlgorithm(rawValue: try string(totp, "algorithm")))
        let entries = try VaultDocument.decode(try GoldenCorpus.goldenText("expected-vault.json"))
        let vaultEntry = try XCTUnwrap(entries.first(where: { $0.kind == .totp }))
        XCTAssertEqual(vaultEntry.secretBytes, secret,
                       "the vector seed is the seed the shared vault stores")

        let cases = try XCTUnwrap(totp["vectors"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for vector in cases {
            let unixTime = try XCTUnwrap(vector["unixTime"] as? Int64)
            XCTAssertEqual(try TokenCalculator.totp(secret: secret, period: period,
                                                    unixTime: unixTime, digits: digits,
                                                    algorithm: algorithm),
                           try string(vector, "code"), "TOTP at \(unixTime)")
            XCTAssertEqual(try TokenCalculator.steam(secret: secret, period: period,
                                                     unixTime: unixTime,
                                                     digits: TokenCalculator.steamDefaultDigits,
                                                     algorithm: algorithm),
                           try string(vector, "steam"), "Steam at \(unixTime)")
        }
    }

    func testHOTPVectorsMatchTheGoldenCounterSeed() throws {
        let hotp = try object(try vectors(), "hotp")
        let secret = try XCTUnwrap(Base32.decode(try string(hotp, "secretBase32")))
        let digits = Int(try XCTUnwrap(hotp["digits"] as? Int64))
        let algorithm = try XCTUnwrap(OtpHashAlgorithm(rawValue: try string(hotp, "algorithm")))
        let entries = try VaultDocument.decode(try GoldenCorpus.goldenText("expected-vault.json"))
        let vaultEntry = try XCTUnwrap(entries.first(where: { $0.kind == .hotp }))
        XCTAssertEqual(vaultEntry.secretBytes, secret)

        let cases = try XCTUnwrap(hotp["vectors"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for vector in cases {
            let counter = try XCTUnwrap(vector["counter"] as? Int64)
            XCTAssertEqual(try TokenCalculator.hotp(secret: secret, counter: counter,
                                                    digits: digits, algorithm: algorithm),
                           try string(vector, "code"), "HOTP counter \(counter)")
        }
    }

    func testMOTPVectors() throws {
        let motp = try object(try vectors(), "motp")
        let seed = try string(motp, "secret")
        let pin = try string(motp, "pin")
        let cases = try XCTUnwrap(motp["vectors"] as? [[String: Any]])
        XCTAssertFalse(cases.isEmpty)
        for vector in cases {
            let unixTime = try XCTUnwrap(vector["unixTime"] as? Int64)
            let offset = Int(try XCTUnwrap(vector["offset"] as? Int64))
            XCTAssertEqual(TokenCalculator.motp(pin: pin, secret: seed, unixTime: unixTime,
                                                offset: offset),
                           try string(vector, "code"),
                           "mOTP at \(unixTime)/\(offset)")
        }
    }

    func testMOTPIsEmptyWithoutAPin() throws {
        // The phone shows a placeholder instead of a code while the PIN is empty; the wording has to
        // match `Entry.MOTP_NO_PIN_CODE`.
        XCTAssertEqual(Entry.motpNoPinCode, "PINREQ")
    }

    // MARK: - Helpers

    private func vectors() throws -> [String: Any] {
        let data = try GoldenCorpus.fixture("token-vectors.json")
        let parsed = try JSONSerialization.jsonObject(with: data)
        return try XCTUnwrap(parsed as? [String: Any])
    }

    private func object(_ document: [String: Any], _ name: String) throws -> [String: Any] {
        try XCTUnwrap(document[name] as? [String: Any], "missing \(name)")
    }

    private func string(_ object: [String: Any], _ name: String) throws -> String {
        try XCTUnwrap(object[name] as? String, "missing \(name)")
    }
}
