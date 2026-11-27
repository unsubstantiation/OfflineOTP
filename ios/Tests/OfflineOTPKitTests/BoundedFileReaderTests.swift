import Foundation
import XCTest
@testable import OfflineOTPKit

final class BoundedFileReaderTests: XCTestCase {
    func testShortReadsContinueUntilEOFAndExactlyFullInputIsAccepted() throws {
        var parts: [Data?] = [Data([1]), Data([2, 3]), Data([4]), nil]
        var requested: [Int] = []
        XCTAssertEqual(Data([1, 2, 3, 4]), try BoundedFileReader.read(maxBytes: 4) { count in
            requested.append(count); return parts.removeFirst()
        })
        XCTAssertEqual([5, 4, 2, 1], requested)
    }
    func testGrowthAfterPreflightCannotExceedBoundOrReturnTruncatedSuccess() {
        var parts: [Data?] = [Data([1, 2]), Data([3, 4]), Data([5]), nil]
        XCTAssertThrowsError(try BoundedFileReader.read(maxBytes: 4) { _ in parts.removeFirst() }) {
            XCTAssertEqual(.tooLarge, $0 as? BoundedFileReader.Failure)
        }
        XCTAssertThrowsError(try BoundedFileReader.read(maxBytes: -1) { _ in XCTFail("invalid read"); return nil })
    }
    func testRealFileReadsRespectLimitIncludingEmptyAndOversizedFiles() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        try Data().write(to: file)
        XCTAssertEqual(Data(), try BoundedFileReader.read(file, maxBytes: 0))
        try Data([1, 2, 3, 4]).write(to: file)
        XCTAssertEqual(Data([1, 2, 3, 4]), try BoundedFileReader.read(file, maxBytes: 4))
        XCTAssertThrowsError(try BoundedFileReader.read(file, maxBytes: 3))
    }
}
