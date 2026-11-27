import Foundation
import XCTest
import YubiKit
@testable import OfflineOTP

final class HardwareNativeOperationTests: XCTestCase {
    private actor Connection: SmartCardConnection {
        var closes = 0
        private var waiters: [CheckedContinuation<Error?, Never>] = []
        init() async throws(SmartCardConnectionError) { }
        static func makeConnection() async throws(SmartCardConnectionError) -> Connection { try await Connection() }
        func close(error: Error?) async {
            closes += 1
            let pending = waiters; waiters = []
            for waiter in pending { waiter.resume(returning: error) }
        }
        func waitUntilClosed() async -> Error? {
            if closes > 0 { return nil }
            return await withCheckedContinuation { waiters.append($0) }
        }
        func send(data: Data) async throws(SmartCardConnectionError) -> Data { Data() }
    }
    private actor Gate {
        private var continuation: CheckedContinuation<Void, Never>?
        private var released = false
        func wait() async {
            if released { return }
            await withCheckedContinuation { continuation = $0 }
        }
        func release() { released = true; continuation?.resume(); continuation = nil }
    }
    @MainActor
    func testUnsupportedTransportNeverConnectsOrRunsOperation() {
        let owner = HardwareNativeOperation(supports: { _ in false }, connect: { _ in
            XCTFail("unsupported transport opened"); throw CocoaError(.featureUnsupported)
        })
        var completions = 0
        owner.start(.nfc, operation: { _ -> Data in XCTFail("operation ran"); return Data() }, completion: { result in
            completions += 1
            if case .success = result { XCTFail("unsupported transport succeeded") }
        })
        XCTAssertEqual(1, completions)
    }
    @MainActor
    func testCancellationDuringOperationSuppressesLateSuccessAndDisposesResult() async throws {
        let connection = try await Connection()
        let gate = Gate()
        let entered = expectation(description: "operation entered")
        let disposed = expectation(description: "late sensitive result disposed")
        let owner = HardwareNativeOperation(supports: { _ in true }, connect: { _ in connection })
        owner.start(.usbC, operation: { _ in
            entered.fulfill(); await gate.wait(); return Data([1, 2, 3])
        }, dispose: { data in
            data.resetBytes(in: 0..<data.count); disposed.fulfill()
        }, completion: { _ in XCTFail("cancelled operation delivered") })
        await fulfillment(of: [entered], timeout: 2)
        owner.cancel()
        await gate.release()
        await fulfillment(of: [disposed], timeout: 2)
        let closes = await connection.closes
        XCTAssertGreaterThanOrEqual(closes, 1)
    }
    @MainActor
    func testTimeoutCompletesOnlyOnceAndDisposesLateResult() async throws {
        let connection = try await Connection()
        let gate = Gate()
        let entered = expectation(description: "operation entered")
        let failed = expectation(description: "timeout reported")
        let disposed = expectation(description: "late result disposed")
        let owner = HardwareNativeOperation(timeoutSeconds: 0.05, supports: { _ in true }, connect: { _ in connection })
        var completions = 0
        owner.start(.nfc, operation: { _ in
            entered.fulfill(); await gate.wait(); return Data([9])
        }, dispose: { data in data.resetBytes(in: 0..<data.count); disposed.fulfill() }, completion: { result in
            completions += 1
            if case .success = result { XCTFail("timeout succeeded") }
            failed.fulfill()
        })
        await fulfillment(of: [entered, failed], timeout: 2)
        await gate.release()
        await fulfillment(of: [disposed], timeout: 2)
        XCTAssertEqual(1, completions)
    }
    @MainActor
    func testCancellationWhileConnectingClosesLateConnectionWithoutRunningOperation() async throws {
        let connection = try await Connection()
        let gate = Gate()
        let connecting = expectation(description: "connection requested")
        let closed = expectation(description: "late connection closed")
        let owner = HardwareNativeOperation(supports: { _ in true }, connect: { _ in
            connecting.fulfill(); await gate.wait(); return connection
        })
        owner.start(.nfc, operation: { _ -> Data in
            XCTFail("operation ran after cancellation during connect"); return Data()
        }, completion: { _ in XCTFail("cancelled connection delivered") })
        await fulfillment(of: [connecting], timeout: 2)
        owner.cancel()
        Task { _ = await connection.waitUntilClosed(); closed.fulfill() }
        await gate.release()
        await fulfillment(of: [closed], timeout: 2)
    }
    @MainActor
    func testReplacementRevokesOldResultButDeliversNewOperation() async throws {
        let first = try await Connection()
        let second = try await Connection()
        let gate = Gate()
        let entered = expectation(description: "first operation entered")
        let disposed = expectation(description: "replaced result disposed")
        let delivered = expectation(description: "new operation delivered")
        var connections = 0
        let owner = HardwareNativeOperation(supports: { _ in true }, connect: { _ in
            connections += 1; return connections == 1 ? first : second
        })
        owner.start(.nfc, operation: { _ in
            entered.fulfill(); await gate.wait(); return Data([1])
        }, dispose: { data in data.resetBytes(in: 0..<data.count); disposed.fulfill() }, completion: { _ in
            XCTFail("replaced operation delivered")
        })
        await fulfillment(of: [entered], timeout: 2)
        owner.start(.usbC, operation: { _ in Data([2]) }, completion: { result in
            switch result {
            case .success(let data): XCTAssertEqual(Data([2]), data)
            case .failure: XCTFail("replacement failed")
            }
            delivered.fulfill()
        })
        await gate.release()
        await fulfillment(of: [disposed, delivered], timeout: 2)
    }

}
