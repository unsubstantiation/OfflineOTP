package com.fairuniversal.offlineotp.Utilities

import com.fairuniversal.offlineotp.Database.Entry

/**
 * The entry mutations both clients perform when an entry is used, added from a form or imported.
 *
 * Android and the desktop client each own their own persistence and threading, but the *policy*
 * must not drift: an entry copied on the phone and the same entry copied on the desktop have to end
 * up with the same last-used stamp and the same usage counter, a new entry has to start at the same
 * point, and an HOTP counter has to advance by exactly one step until it reaches the same ceiling.
 * Keeping those rules here means a change is made once, for both, and is covered by the shared
 * tests instead of two client suites.
 */
object EntryUsage {

    /**
     * One step above the highest counter a client will advance to. Android's counter chip and the
     * desktop counter action both stop here instead of overflowing into a negative counter.
     */
    const val MAX_COUNTER: Long = Long.MAX_VALUE - 1

    /**
     * Records one use of [entry]: the last-used stamp and the usage frequency the **Last used** and
     * **Most used** orders read. [now] is passed in so a caller that touches a display copy and the
     * stored entry can stamp both with one instant.
     */
    @JvmStatic
    @JvmOverloads
    fun recordUse(entry: Entry, now: Long = System.currentTimeMillis()) {
        entry.setLastUsed(now)
        entry.setUsedFrequency(entry.getUsedFrequency() + 1)
    }

    /**
     * Marks a newly created or imported entry as just used: it computes its first token and gets a
     * fresh last-used stamp, so it leads a **Last used** list instead of sorting below everything
     * that has ever been copied.
     */
    @JvmStatic
    @JvmOverloads
    fun markImported(entry: Entry, now: Long = System.currentTimeMillis()) {
        entry.updateOTP(false)
        entry.setLastUsed(now)
    }

    /**
     * Advances a counter-based (HOTP) entry by one step and recomputes its token. Returns false
     * when the entry has no counter or has reached [MAX_COUNTER], in which case nothing changed.
     */
    @JvmStatic
    fun advanceHotp(entry: Entry): Boolean {
        if (!entry.isCounterBased() || entry.getCounter() >= MAX_COUNTER) return false
        entry.setCounter(entry.getCounter() + 1)
        entry.updateOTP(false)
        return true
    }
}
