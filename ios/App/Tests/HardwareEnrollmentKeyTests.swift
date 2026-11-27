import Foundation
import XCTest
@testable import OfflineOTP

final class HardwareEnrollmentKeyTests: XCTestCase {
    func testEnrollmentSnapshotIsOneUse() {
        let material = HardwareEnrollmentKey(Data([1, 2, 3]))
        XCTAssertEqual(Data([1, 2, 3]), material.take())
        XCTAssertNil(material.take())
        material.clear()
        XCTAssertNil(material.take())
    }
    func testCancellationRevokesSnapshotBeforeLateConnectionCanConsumeIt() {
        let material = HardwareEnrollmentKey(Data(repeating: 7, count: 32))
        material.clear()
        XCTAssertNil(material.take())
    }
}
