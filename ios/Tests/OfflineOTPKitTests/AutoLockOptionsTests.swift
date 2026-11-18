import XCTest
@testable import OfflineOTPKit

final class AutoLockOptionsTests: XCTestCase {
    func testLabelsAndCustomTimeoutsMatchJvmContract() throws {
        let contract = try XCTUnwrap(try JSONValue.decode(GoldenCorpus.goldenText("auto-lock-options.json")).objectValue)
        let options = try XCTUnwrap(contract["options"]?.arrayValue).map { Int($0.intValue!) }
        XCTAssertEqual(options, AutoLockOptions.seconds)
        for item in try XCTUnwrap(contract["cases"]?.arrayValue) {
            let test = try XCTUnwrap(item.objectValue)
            let seconds = Int(try XCTUnwrap(test["seconds"]?.intValue))
            XCTAssertEqual(AutoLockOptions.label(seconds), test["label"]?.stringValue)
            let choices = AutoLockOptions.including(seconds)
            XCTAssertTrue(choices.contains(seconds))
            XCTAssertEqual(choices.filter { $0 == seconds }.count, 1)
        }
        XCTAssertEqual(AutoLockOptions.including(-1), AutoLockOptions.seconds)
    }
}
