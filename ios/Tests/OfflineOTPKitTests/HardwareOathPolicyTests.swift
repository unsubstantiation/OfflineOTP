import XCTest
@testable import OfflineOTPKit

final class HardwareOathPolicyTests: XCTestCase {
    private func identity(device: String = "key-a", account: String = "alice", period: UInt = 30) -> HardwareOathPolicy.Identity {
        .init(deviceID: device, label: account, account: account, issuer: "issuer", type: 1, period: period)
    }
    func testReconnectMatchesRawIdentityAndRejectsAmbiguityOrDifferentKey() {
        let selected = identity()
        XCTAssertEqual(1, HardwareOathPolicy.uniqueMatch(selected, candidates: [identity(account: "other"), selected]))
        XCTAssertNil(HardwareOathPolicy.uniqueMatch(selected, candidates: [identity(device: "key-b")]))
        XCTAssertNil(HardwareOathPolicy.uniqueMatch(selected, candidates: [identity(period: 60)]))
        XCTAssertNil(HardwareOathPolicy.uniqueMatch(selected, candidates: [selected, selected]))
        XCTAssertNil(HardwareOathPolicy.uniqueMatch(selected, candidates: []))
    }
    func testCounterConsentDoesNotApplyToTimeBasedCalculations() {
        XCTAssertFalse(HardwareOathPolicy.permitsCalculation(period: 0, counterConsent: false))
        XCTAssertTrue(HardwareOathPolicy.permitsCalculation(period: 0, counterConsent: true))
        XCTAssertTrue(HardwareOathPolicy.permitsCalculation(period: 30, counterConsent: false))
    }
    func testExpiryAndOtpValidationFailClosed() {
        let requested = Date(timeIntervalSince1970: 1_000)
        let end = requested.addingTimeInterval(20)
        XCTAssertEqual(end, HardwareOathPolicy.codeExpiry(otp: "012345", period: 30, requested: requested, validUntil: end, now: requested))
        XCTAssertNil(HardwareOathPolicy.codeExpiry(otp: "012345", period: 30, requested: requested, validUntil: end, now: end))
        XCTAssertNil(HardwareOathPolicy.codeExpiry(otp: "012345", period: 30, requested: requested, validUntil: .distantFuture, now: requested))
        for otp in ["12345", "123456789", "１２３４５６", "12345x", "12345\n"] {
            XCTAssertNil(HardwareOathPolicy.codeExpiry(otp: otp, period: 30, requested: requested, validUntil: end, now: requested))
        }
        XCTAssertEqual(requested.addingTimeInterval(30), HardwareOathPolicy.codeExpiry(otp: "12345678", period: 0, requested: requested, validUntil: .distantFuture, now: requested))
    }
}
