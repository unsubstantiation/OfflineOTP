import XCTest
@testable import OfflineOTPKit

/// The vault fixture: the same bytes the Android app and the desktop client open.
///
/// These tests are the reason the plan claims a Swift client can share the format without sharing
/// code. If any of them fails, the iOS client would not be able to open a real vault.
final class GoldenVaultTests: XCTestCase {
    func testTheGoldenVaultUnlocksToTheSharedDocument() throws {
        let (vault, _) = GoldenCorpus.fixtureVault(secrets: try GoldenCorpus.golden("secrets.dat"))
        let unlocked = try vault.open(password: GoldenCorpus.password)

        XCTAssertEqual(unlocked.entries.count, 2)
        XCTAssertEqual(unlocked.entries[0].kind, .totp)
        XCTAssertEqual(unlocked.entries[0].issuer, "Golden Issuer")
        XCTAssertEqual(unlocked.entries[0].label, "alice@example.com")
        XCTAssertEqual(unlocked.entries[0].digits, 6)
        XCTAssertEqual(unlocked.entries[0].period, 30)
        XCTAssertEqual(unlocked.entries[0].algorithm, .sha1)
        XCTAssertEqual(unlocked.entries[0].secretBase32, "JBSWY3DPEHPK3PXP")

        XCTAssertEqual(unlocked.entries[1].kind, .hotp)
        XCTAssertEqual(unlocked.entries[1].digits, 8)
        XCTAssertEqual(unlocked.entries[1].algorithm, .sha256)
        XCTAssertEqual(unlocked.entries[1].counter, 9)
    }

    func testTheDerivedVerifierMatchesTheGoldenSettings() throws {
        let credentials = try VaultCrypto.credentials(password: GoldenCorpus.password,
                                                     salt: GoldenCorpus.vaultSalt,
                                                     iterations: GoldenCorpus.vaultIterations)
        XCTAssertEqual(credentials.key.count, 16)
        XCTAssertEqual(credentials.verifier.count, 16)
        XCTAssertEqual(VaultCrypto.base64URLEncode(credentials.verifier),
                       GoldenCorpus.vaultVerifierBase64URL)
    }

    func testDecodingAndReencodingReproducesTheGoldenBytesExactly() throws {
        let expected = try GoldenCorpus.goldenText("expected-vault.json")
        let entries = try VaultDocument.decode(expected)
        XCTAssertEqual(try VaultDocument.encode(entries), expected,
                       "a re-encode must reproduce the shared document byte for byte")
    }

    func testReencodingIsStableForEveryFixtureBackup() throws {
        // decode → encode → decode → encode must settle immediately, or a save would keep rewriting
        // the vault with different bytes each time.
        for name in ["otp_accounts.json.aes", "desktop-note-export.json.aes",
                     "desktop-ssh-export.json.aes", "desktop-privacy-export.json.aes"] {
            let text = try EncryptedBackup.open(password: GoldenCorpus.password,
                                                data: try GoldenCorpus.golden(name))
            let once = try VaultDocument.encode(try VaultDocument.decode(text))
            let twice = try VaultDocument.encode(try VaultDocument.decode(once))
            XCTAssertEqual(once, twice, "\(name) does not round-trip stably")
        }
    }

    func testAWrongPasswordIsRejected() throws {
        let (vault, _) = GoldenCorpus.fixtureVault(secrets: try GoldenCorpus.golden("secrets.dat"))
        XCTAssertThrowsError(try vault.open(password: GoldenCorpus.password + "-wrong")) { error in
            XCTAssertEqual(error as? VaultOpenError, .authenticationFailed)
        }
    }

    func testATamperedVaultIsRejected() throws {
        var secrets = [UInt8](try GoldenCorpus.golden("secrets.dat"))
        secrets[secrets.count - 1] ^= 0xFF
        let (vault, _) = GoldenCorpus.fixtureVault(secrets: Data(secrets))
        XCTAssertThrowsError(try vault.open(password: GoldenCorpus.password)) { error in
            XCTAssertEqual(error as? VaultOpenError, .authenticationFailed)
        }
    }

    func testAMissingVaultIsReportedAsMissing() throws {
        let (vault, store) = GoldenCorpus.fixtureVault(secrets: try GoldenCorpus.golden("secrets.dat"))
        store.delete()
        XCTAssertFalse(vault.isInitialized)
        XCTAssertThrowsError(try vault.open(password: GoldenCorpus.password)) { error in
            XCTAssertEqual(error as? VaultOpenError, .missing)
        }
    }
}
