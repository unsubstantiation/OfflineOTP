import Foundation
import XCTest
@testable import OfflineOTPKit

final class CsvRecordSeparatorTests: XCTestCase {
    func testBothImportersRecogniseEveryRecordSeparator() throws {
        for separator in ["\r\n", "\n", "\r"] {
            for trailingSeparator in ["", separator] {
                let text = "url,username,password" + separator
                    + "https://example.test,alice,first" + separator
                    + "https://other.test,bob,second" + trailingSeparator
                let data = Data(text.utf8)
                let expected = [
                    BrowserPasswordCsv.Row(values: ["https://example.test", "alice", "first"]),
                    BrowserPasswordCsv.Row(values: ["https://other.test", "bob", "second"]),
                ]
                XCTAssertEqual(try BrowserPasswordCsv.parse(data).rows, expected)
                XCTAssertEqual(try ThunderbirdCsv.parse(data).rows, expected)
            }
        }
    }

    func testQuotedNewlinesAndEscapedQuotesArePreserved() throws {
        for newline in ["\r\n", "\n", "\r"] {
            let password = "first" + newline + "second,\"quoted\""
            let encodedPassword = "\"first" + newline + "second,\"\"quoted\"\"\""
            let data = Data(("url,username,password\r\n"
                + "https://example.test,alice," + encodedPassword + "\r\n").utf8)
            XCTAssertEqual(try BrowserPasswordCsv.parse(data).rows[0].value(at: 2), password)
            XCTAssertEqual(try ThunderbirdCsv.parse(data).rows[0].value(at: 2), password)
        }
    }

    func testRecordLimitStillAppliesToCrLfRecords() throws {
        XCTAssertThrowsError(try BrowserPasswordCsv.parseRfc4180(
            "a,b\r\nc,d\r\n", maxColumns: 2, maxRows: 1))
    }
}
