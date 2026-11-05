import Foundation

/// The entry mutations both existing clients perform when an entry is used — the port of
/// `core/src/main/kotlin/org/shadowice/flocke/andotp/Utilities/EntryUsage.kt`.
///
/// The rules are shared for the same reason they are shared on the JVM: an entry copied on the phone
/// and the same entry copied here have to end up with the same last-used stamp and the same usage
/// counter, a new entry has to start at the same point, and an HOTP counter has to advance by exactly
/// one step until it reaches the same ceiling.
public enum EntryUsage {
    /// One step above the highest counter a client will advance to (`EntryUsage.MAX_COUNTER`).
    /// The counter chip stops here instead of overflowing into a negative value.
    public static let maxCounter: Int64 = Int64.max - 1

    /// The stamp unit both clients store: milliseconds since the epoch (`System.currentTimeMillis`).
    public static func nowMillis(_ date: Date = Date()) -> Int64 {
        Int64((date.timeIntervalSince1970 * 1000).rounded(.down))
    }

    /// Records one use: the stamp **Last used** reads and the counter **Most used** reads.
    public static func recordUse(_ entry: inout Entry, now: Int64 = EntryUsage.nowMillis()) {
        entry.setLastUsed(now)
        entry.setUsedFrequency(entry.usedFrequency + 1)
    }

    /// Marks a newly created or imported entry as just used, so it leads a **Last used** list
    /// instead of sorting below everything that has ever been copied.
    public static func markImported(_ entry: inout Entry, now: Int64 = EntryUsage.nowMillis()) {
        entry.setLastUsed(now)
    }

    /// Advances a counter-based (HOTP) entry by one step. Returns false — and changes nothing — when
    /// the entry has no counter, is not HOTP, or has reached `maxCounter`.
    @discardableResult
    public static func advanceHotp(_ entry: inout Entry) -> Bool {
        guard entry.kind == .hotp, entry.counter < maxCounter else { return false }
        entry.setCounter(entry.counter + 1)
        return true
    }
}
