import Foundation

/// How a code is displayed and how long its time step has left.
///
/// Deliberately pure: no view, no clock, no vault. The app tests cover it on a simulator without a
/// stored vault, and it keeps the display rules in one place instead of spread through the views.
public enum CodeFormat {
    /// A code with a space every `groupSize` characters, for readability.
    ///
    /// The characters and their order never change — only spaces are inserted — because a code the
    /// user types is read from this string. `mOTP` codes are hexadecimal and `Steam` codes are
    /// letters, so this must not assume decimal digits.
    public static func grouped(_ code: String, groupSize: Int = 3) -> String {
        guard groupSize > 0, code.count > groupSize else { return code }
        var out = ""
        for (index, character) in code.enumerated() {
            if index > 0, index % groupSize == 0 { out.append(" ") }
            out.append(character)
        }
        return out
    }

    /// What a locked entry shows instead of its code.
    public static let concealedPlaceholder = "•" + String(repeating: "•", count: 5)

    /// Seconds left in the current time step, or nil for an entry that does not roll over.
    ///
    /// HOTP entries have no clock, so they return nil and the UI shows no countdown. mOTP always
    /// rolls over every ten seconds whatever period the entry stores — the same rule `:core`
    /// applies, and the reason this is computed here rather than read from `period`.
    public static func secondsRemaining(kind: String, period: Int, now: Date) -> Int? {
        guard period > 0 else { return nil }
        switch kind {
        case "HOTP":
            return nil
        case "MOTP":
            let step = 10
            let elapsed = Int(now.timeIntervalSince1970) % step
            return step - elapsed
        case "TOTP", "STEAM":
            let elapsed = Int(now.timeIntervalSince1970) % period
            return period - elapsed
        default:
            return nil
        }
    }

    /// Fraction of the time step still to run, for the countdown ring: 1 at the start, 0 at the end.
    public static func fractionRemaining(kind: String, period: Int, now: Date) -> Double {
        guard let remaining = secondsRemaining(kind: kind, period: period, now: now),
              let step = stepSeconds(kind: kind, period: period) else { return 0 }
        return min(1, max(0, Double(remaining) / Double(step)))
    }

    /// The length of the time step the entry actually uses.
    public static func stepSeconds(kind: String, period: Int) -> Int? {
        switch kind {
        case "TOTP", "STEAM": return period > 0 ? period : nil
        case "MOTP": return 10
        default: return nil
        }
    }
}
