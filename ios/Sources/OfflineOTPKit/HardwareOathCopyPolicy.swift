import Foundation

/// YKOATH naming/parameter limits, matching the JVM copy-to-key adapter. No native SDK dependency.
public enum HardwareOathCopyPolicy {
    public struct Names: Equatable {
        public let account: String
        public let issuer: String?
        public let identifier: String
    }
    public static func names(for entry: Entry) -> Names? {
        guard entry.kind == .totp || entry.kind == .hotp,
              (6...8).contains(entry.digits), (1...InputPolicy.maxPeriodSeconds).contains(entry.period),
              entry.kind != .hotp || (0...Int64(Int32.max)).contains(entry.counter) else { return nil }
        var account = entry.label
        var issuer: String? = entry.issuer.isEmpty ? nil : entry.issuer
        guard !account.isEmpty, issuer?.contains(":") != true, issuer != nil || !account.contains(":") else { return nil }
        let scalars = (account + (issuer ?? "")).unicodeScalars
        guard !scalars.contains(where: {
            CharacterSet.controlCharacters.contains($0) || $0.value == 0x2028 || $0.value == 0x2029 || $0.properties.generalCategory == .format
        }) else { return nil }
        // Avoid interpreting a literal leading "60/" as a non-default TOTP period.
        if entry.kind == .totp, entry.period == 30,
           (issuer ?? account).range(of: "^[0-9]+/", options: .regularExpression) != nil {
            if let value = issuer { issuer = "30/" + value } else { account = "30/" + account }
        }
        let prefix = entry.kind == .totp && entry.period != 30 ? "\(entry.period)/" : ""
        let identifier = prefix + (issuer.map { $0 + ":" } ?? "") + account
        guard identifier.utf8.count <= 64 else { return nil }
        return Names(account: account, issuer: issuer, identifier: identifier)
    }
}
