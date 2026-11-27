import Foundation

/// Toolkit-free safety decisions shared by native hardware orchestration and portable tests.
/// Identity is the key's OATH ID plus raw credential fields, never the bounded display label/index.
public enum HardwareOathPolicy {
    public struct Identity: Equatable {
        public let deviceID: String
        public let label: String?
        public let account: String
        public let issuer: String?
        public let type: UInt
        public let period: UInt
        public init(deviceID: String, label: String?, account: String, issuer: String?, type: UInt, period: UInt) {
            self.deviceID = deviceID; self.label = label; self.account = account
            self.issuer = issuer; self.type = type; self.period = period
        }
    }
    public static func permitsCalculation(period: UInt, counterConsent: Bool) -> Bool {
        period != 0 || counterConsent
    }
    public static func uniqueMatch(_ selected: Identity, candidates: [Identity]) -> Int? {
        let indices = candidates.indices.filter { candidates[$0] == selected }
        return indices.count == 1 ? indices.first : nil
    }
    public static func codeExpiry(otp: String, period: UInt, requested: Date, validUntil: Date, now: Date) -> Date? {
        guard (6...8).contains(otp.utf8.count), otp.utf8.allSatisfy({ $0 >= 48 && $0 <= 57 }) else { return nil }
        let end = period == 0 ? requested.addingTimeInterval(30) : validUntil
        // A corrupt or nonsensical validity interval must not retain a time-based code forever.
        guard end > now, period == 0 || (period <= 86_400 && end <= requested.addingTimeInterval(TimeInterval(period))) else { return nil }
        return end
    }
}
