import Foundation
import XCTest
@testable import OfflineOTPKit

final class HardwareOathCopyPolicyTests: XCTestCase {
    func testSharedRenameNamingContract() throws {
        let data = try GoldenCorpus.golden("hardware-oath-rename.json")
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        for row in try XCTUnwrap(root["vectors"] as? [[String: Any]]) {
            let name = try XCTUnwrap(row["name"] as? String)
            let names = HardwareOathRenamePolicy.names(counterBased: try XCTUnwrap(row["counterBased"] as? Bool),
                period: try XCTUnwrap(row["period"] as? UInt), issuer: try XCTUnwrap(row["issuer"] as? String),
                account: try XCTUnwrap(row["account"] as? String))
            XCTAssertEqual(row["identifier"] as? String, names?.identifier, name)
        }
    }
    func testSdkOldIdentifierMustBeComparedWithRawIdBeforeRename() {
        // Explicit default prefix is a noncanonical OLD ID: SDK would target a different ID.
        XCTAssertEqual("alice", HardwareOathRenamePolicy.sdkIdentifier(counterBased: false, period: 30, issuer: nil, account: "alice"))
        XCTAssertNotEqual("30/alice", HardwareOathRenamePolicy.sdkIdentifier(counterBased: false, period: 30, issuer: nil, account: "alice"))
        XCTAssertEqual("60/example:alice", HardwareOathRenamePolicy.sdkIdentifier(counterBased: false, period: 60, issuer: "example", account: "alice"))
    }
    func testSharedNamingAndParameterContract() throws {
        let data = try GoldenCorpus.golden("hardware-oath-copy.json")
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        for row in try XCTUnwrap(root["vectors"] as? [[String: Any]]) {
            let name = try XCTUnwrap(row["name"] as? String)
            let object = try XCTUnwrap(row["entry"] as? [String: Any])
            let document = try JSONSerialization.data(withJSONObject: [object])
            let entry = try XCTUnwrap(VaultDocument.decode(document).first)
            XCTAssertEqual(row["identifier"] as? String, HardwareOathCopyPolicy.names(for: entry)?.identifier, name)
        }
    }
    func testNonOtpKindsCannotExportSeed() {
        for kind in [EntryKind.note, .password, .email, .steam, .motp, .sshKey, .passwordArchive] {
            let entry = Entry(fields: ["type": .string(kind.rawValue), "label": .string("alice")])
            XCTAssertNil(HardwareOathCopyPolicy.names(for: entry))
        }
    }
}
