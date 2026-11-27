import Foundation
import XCTest
@testable import OfflineOTP

final class NativeFileReaderTests: XCTestCase {
    func testQueuedCancellationNeverReadsOrDeliversAndReplacementIsIdentityBound() {
        var workers: [() -> Void] = []; var deliveries: [() -> Void] = []
        var reads = 0; var delivered = 0
        let reader = NativeFileReader(enqueue: { workers.append($0) }, dispatch: { deliveries.append($0) }, read: { _, _ in
            reads += 1; return Data([1, 2])
        })
        let url = URL(fileURLWithPath: "/synthetic/import")
        reader.start(url) { _ in XCTFail("cancelled callback") }
        reader.cancel(); workers.removeFirst()()
        XCTAssertEqual(0, reads)
        reader.start(url) { _ in XCTFail("replaced callback") }
        workers.removeFirst()()
        reader.start(url) { data in XCTAssertEqual(Data([1, 2]), data); delivered += 1 }
        deliveries.removeFirst()(); XCTAssertEqual(0, delivered)
        workers.removeFirst()(); deliveries.removeFirst()()
        XCTAssertEqual(1, delivered)
    }
    func testReaderFailuresAndOversizedResultsCannotPublishBytesAndRetryWorks() {
        var workers: [() -> Void] = []; var deliveries: [() -> Void] = []
        var oversized = true; var callbacks = 0
        let reader = NativeFileReader(enqueue: { workers.append($0) }, dispatch: { deliveries.append($0) }, read: { _, _ in
            oversized ? Data(repeating: 1, count: 3) : Data([1])
        })
        let url = URL(fileURLWithPath: "/synthetic/import")
        reader.start(url, maxBytes: 2) { data in XCTAssertNil(data); callbacks += 1 }
        workers.removeFirst()(); deliveries.removeFirst()(); XCTAssertEqual(1, callbacks)
        oversized = false
        reader.start(url, maxBytes: 2) { data in XCTAssertEqual(Data([1]), data); callbacks += 1 }
        workers.removeFirst()(); deliveries.removeFirst()(); XCTAssertEqual(2, callbacks)
    }
    func testCancelledOrDeallocatedOwnerDoesNotDeliverCompletedPrivateKeyRead() {
        var workers: [() -> Void] = []; var deliveries: [() -> Void] = []
        var reader: NativeFileReader? = NativeFileReader(enqueue: { workers.append($0) }, dispatch: { deliveries.append($0) },
            read: { _, _ in Data("synthetic private key".utf8) })
        reader?.start(URL(fileURLWithPath: "/synthetic/private")) { _ in XCTFail("retired owner delivered") }
        workers.removeFirst()(); reader = nil; deliveries.removeFirst()()
    }
}
