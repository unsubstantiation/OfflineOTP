import Foundation
import XCTest
import OfflineOTPKit
import OfflineOTPPresentation
@testable import OfflineOTP

final class SharedInterchangeAdapterTests: XCTestCase {
    private let policy = SharedInterchangeAdapter()

    func testSharedProvisioningRoundTripsTheNativeEntryWithoutLosingTagsOrCounter() throws {
        let source = "otpauth://hotp/Acme:alice%2Bwork?secret=JBSWY3DPEHPK3PXP&issuer=Acme&counter=42&tags=work&tags=mail"
        let parsed = try OtpUri.parse(source, interchange: policy)
        XCTAssertEqual(parsed.label, "alice+work"); XCTAssertEqual(parsed.counter, 42)
        let entry = try parsed.entry()
        let exported = try XCTUnwrap(OtpUri.uri(for: entry, interchange: policy))
        let reopened = try OtpUri.parse(exported, interchange: policy)
        XCTAssertEqual(reopened, parsed)
        XCTAssertFalse(exported.contains("alice+work"))
    }

    func testSharedCsvPreservesEveryColumnAndRejectsPartialOrAmbiguousRows() throws {
        let input = Data("name,url,username,password,note\r\nExample,example.invalid,alice,\"p,\"\"w\"\"\",\"café\nline\"\r\n".utf8)
        let table = try policy.csv(input, thunderbird: false)
        XCTAssertEqual(table.rows[0][3], "p,\"w\"")
        XCTAssertEqual(table.rows[0][4], "café\nline")
        let encoded = try policy.exportCsv(columns: table.columns, rows: table.rows)
        XCTAssertEqual(try policy.csv(encoded, thunderbird: false).rows, table.rows)
        XCTAssertThrowsError(try policy.csv(Data("name,url,username,password\nx,y,z".utf8), thunderbird: false))
        XCTAssertThrowsError(try policy.csv(Data([0xc3, 0x28]), thunderbird: false))
        XCTAssertNil(policy.browserDialect(["url", "URL", "username", "password"]))
        XCTAssertFalse(policy.browserCommonEdit(["url", "username", "password", "login_uri"]))
    }

    func testSharedBatchAuthorityRejectsStaleDuplicateAndForeignResultsWithoutOwningSeeds() throws {
        let parsed = try policy.otp("otpauth://totp/x?secret=JBSWY3DPEHPK3PXP")
        let account = MigrationAccount(kind: parsed.kind, secret: parsed.secret, digits: parsed.digits,
                                       algorithm: parsed.algorithm, counter: parsed.counter, issuer: parsed.issuer, label: parsed.label)
        let first = MigrationPayload(version: 1, batchSize: 2, batchIndex: 1, batchId: -42, accounts: [account])
        let second = MigrationPayload(version: 1, batchSize: 2, batchIndex: 0, batchId: -42, accounts: [account])
        var collector = MigrationBatchCollector(generation: 7, authority: policy.migrationBatch(generation: 7))
        XCTAssertEqual(collector.add(first, generation: 7, sessionReady: true), .added)
        XCTAssertEqual(collector.add(first, generation: 7, sessionReady: true), .duplicate)
        XCTAssertEqual(collector.add(second, generation: 7, sessionReady: true), .complete)
        XCTAssertEqual(collector.takeAccounts(generation: 7, sessionReady: true)?.count, 2)
        XCTAssertNil(collector.takeAccounts(generation: 7, sessionReady: true))
        var stale = MigrationBatchCollector(generation: 7, authority: policy.migrationBatch(generation: 7))
        XCTAssertEqual(stale.add(first, generation: 8, sessionReady: true), .stale)
        XCTAssertFalse(stale.isOpen)
    }

    func testSharedPreferencesAndLinScpRejectWrongDocumentsBeforeNativeInterpretation() throws {
        let preferences = Data("{\"format\":\"offlineotp.preferences\",\"version\":1,\"platform\":\"desktop\",\"settings\":{}}".utf8)
        XCTAssertThrowsError(try policy.preferences(preferences, platform: "ios"))
        XCTAssertThrowsError(try policy.linScp("[]", bundle: false))
        XCTAssertThrowsError(try policy.linScp("{\"version\":2,\"sessions_ini\":\"[x]\"}", bundle: true))
        let sections = try policy.linScpIni("[First]\npassword=old\n[Second]\nhost=server\n[First]\npassword=new\\nline\n")
        XCTAssertEqual(sections.map { $0.name }, ["First", "Second"])
        XCTAssertEqual(sections[0].fields["password"], "new\nline")
    }

    func testMaterialBridgeRejectsTrailingBytesAndMalformedSecretFraming() {
        let material = CredentialMaterialCodec()
        let bad = KotlinByteArray(size: 8)
        XCTAssertNil(material.passwordDecode(bytes: bad))
        XCTAssertNil(material.sshDecode(bytes: bad))
        XCTAssertFalse(material.validateEncoded(kind: "PASSWORD", base32: "AAAA"))
    }
}
