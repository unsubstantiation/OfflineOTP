/// Presentation only: never rounds or changes a stored timeout.
public enum AutoLockOptions {
    public static let seconds = [0, 30, 60, 120, 300, 900]

    public static func including(_ current: Int) -> [Int] {
        let value = max(0, current)
        return seconds.contains(value) ? seconds : seconds + [value]
    }

    public static func label(_ seconds: Int) -> String {
        if seconds <= 0 { return "Disabled" }
        if seconds == 60 { return "1 minute" }
        if seconds % 60 == 0 { return "\(seconds / 60) minutes" }
        if seconds == 1 { return "1 second" }
        return "\(seconds) seconds"
    }
}
