import SwiftUI

/// The Dark Forest colour tokens, so the iOS client looks like the other three.
///
/// These are the same numbers as `desktop/src/main/kotlin/com/fairuniversal/offlineotp/desktop/ui/
/// ForestTheme.kt`, which itself mirrors the Android resources; `scripts/tests/test_ios_app.py`
/// compares the two files so a theme change cannot land on one platform only.
public enum ForestPalette {
    public static let background = Color(red: 0x10 / 255, green: 0x1A / 255, blue: 0x14 / 255)
    public static let backgroundDeep = Color(red: 0x0A / 255, green: 0x11 / 255, blue: 0x0D / 255)
    public static let surface = Color(red: 0x1A / 255, green: 0x2C / 255, blue: 0x21 / 255)
    public static let surfaceElevated = Color(red: 0x21 / 255, green: 0x37 / 255, blue: 0x2A / 255)
    public static let surfaceVariant = Color(red: 0x26 / 255, green: 0x3F / 255, blue: 0x30 / 255)
    public static let appBar = Color(red: 0x1B / 255, green: 0x4D / 255, blue: 0x2E / 255)
    public static let primary = Color(red: 0x4C / 255, green: 0xAF / 255, blue: 0x50 / 255)
    public static let accent = Color(red: 0x81 / 255, green: 0xC7 / 255, blue: 0x84 / 255)
    public static let textPrimary = Color(red: 0xE6 / 255, green: 0xF0 / 255, blue: 0xE8 / 255)
    public static let textSecondary = Color(red: 0xA3 / 255, green: 0xBC / 255, blue: 0xA9 / 255)
    public static let textHint = Color(red: 0x92 / 255, green: 0xAC / 255, blue: 0x97 / 255)
    public static let onPrimary = Color(red: 0x0D / 255, green: 0x17 / 255, blue: 0x11 / 255)
    public static let divider = Color(red: 0x2B / 255, green: 0x44 / 255, blue: 0x34 / 255)
    public static let warning = Color(red: 0xFF / 255, green: 0x8A / 255, blue: 0x80 / 255)
    public static let thumbnail = Color(red: 0xC8 / 255, green: 0xD8 / 255, blue: 0xCC / 255)
    /// OTP digits: warm amber, kept at AA contrast on every forest surface.
    public static let otpAmber = Color(red: 0xFF / 255, green: 0xC8 / 255, blue: 0x57 / 255)
    /// Muted amber for the countdown track.
    public static let otpAmberMuted = Color(red: 0x7A / 255, green: 0x5C / 255, blue: 0x22 / 255)
}
