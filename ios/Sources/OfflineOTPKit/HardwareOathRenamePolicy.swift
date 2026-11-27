import Foundation

/// Naming-only changes. The native command preserves seed, period, counter and algorithm.
public enum HardwareOathRenamePolicy {
    public struct Names: Equatable {
        public let account: String
        public let issuer: String?
        public let identifier: String
    }
    public static func names(counterBased: Bool, period: UInt, issuer: String, account: String) -> Names? {
        guard !account.isEmpty, account.utf16.count <= InputPolicy.maxTextChars,
              issuer.utf16.count <= InputPolicy.maxTextChars,
              counterBased || (1...UInt(InputPolicy.maxPeriodSeconds)).contains(period),
              !issuer.contains(":"), !issuer.isEmpty || !account.contains(":") else { return nil }
        guard !(account + issuer).unicodeScalars.contains(where: {
            CharacterSet.controlCharacters.contains($0) || $0.value == 0x2028 || $0.value == 0x2029 || $0.properties.generalCategory == .format
        }) else { return nil }
        var name = account
        var source: String? = issuer.isEmpty ? nil : issuer
        if !counterBased, period == 30,
           (source ?? name).range(of: "^[0-9]+/", options: .regularExpression) != nil {
            if let value = source { source = "30/" + value } else { name = "30/" + name }
        }
        let id = (!counterBased && period != 30 ? "\(period)/" : "") + (source.map { $0 + ":" } ?? "") + name
        guard id.utf8.count <= 64 else { return nil }
        return Names(account: name, issuer: source, identifier: id)
    }

    /// The SDK reconstructs the OLD ID instead of using raw bytes for rename. Refuse an old
    /// noncanonical ID rather than risk renaming another credential with the reconstructed ID.
    public static func sdkIdentifier(counterBased: Bool, period: UInt, issuer: String?, account: String) -> String {
        (!counterBased && period != 30 ? "\(period)/" : "") + (issuer.map { $0 + ":" } ?? "") + account
    }
}
