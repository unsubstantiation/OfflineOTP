import Foundation

/// Secret-free configuration contract checked against the same golden vectors as the JVM.
public enum AutomaticBackupPolicy {
    public static let changeDelay: TimeInterval = 1
    public static let defaultMinutes = 15
    public static let defaultRetention = 10
    public static func valid(minutes: Int, retention: Int, passwordUTF16Units: Int, passwordsMatch: Bool) -> Bool {
        (1...1440).contains(minutes) && (1...100).contains(retention) &&
            (1...InputPolicy.maxTextChars).contains(passwordUTF16Units) && passwordsMatch
    }
}
