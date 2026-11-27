import XCTest
@testable import OfflineOTPKit

final class AutomaticBackupPolicyTests: XCTestCase {
    private struct Contract: Decodable {
        let changeDelayMillis: Int
        let defaultMinutes: Int
        let defaultRetention: Int
        let cases: [Vector]
    }
    private struct Vector: Decodable {
        let name: String
        let minutes: Int
        let retention: Int
        let passwordUtf16Units: Int
        let passwordsMatch: Bool
        let valid: Bool
    }
    func testCanonicalBoundaryVectorsMatchJVMPolicy() throws {
        let data = Data(try GoldenCorpus.goldenText("automatic-backup-options.json").utf8)
        let contract = try JSONDecoder().decode(Contract.self, from: data)
        XCTAssertEqual(TimeInterval(contract.changeDelayMillis) / 1000, AutomaticBackupPolicy.changeDelay)
        XCTAssertEqual(contract.defaultMinutes, AutomaticBackupPolicy.defaultMinutes)
        XCTAssertEqual(contract.defaultRetention, AutomaticBackupPolicy.defaultRetention)
        for vector in contract.cases {
            XCTAssertEqual(vector.valid, AutomaticBackupPolicy.valid(minutes: vector.minutes, retention: vector.retention,
                passwordUTF16Units: vector.passwordUtf16Units, passwordsMatch: vector.passwordsMatch), vector.name)
        }
    }
    func testPasswordLimitsUseUTF16UnitsNotGraphemeClusters() {
        let unicode = String(repeating: "🔐", count: 513)
        XCTAssertFalse(AutomaticBackupPolicy.valid(minutes: 15, retention: 10,
            passwordUTF16Units: unicode.utf16.count, passwordsMatch: true))
    }
}
