package org.shadowice.flocke.andotp.View

/** Shared bounds check for delayed/list callbacks that may outlive an entry-list update. */
object EntryIndexGuard {
    @JvmStatic
    fun isValid(index: Int, size: Int): Boolean = index >= 0 && index < size
}
