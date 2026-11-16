package com.fairuniversal.offlineotp.ui.vault

/** Geometry only: never retain a live entry or session authority in a drag gesture. */
data class EntryDragBounds(val id: Long, val top: Float, val height: Float)

/** Move one neighbour at a time, only after the held pointer crosses its midpoint. */
fun entryDragDirection(id: Long, pointerY: Float, order: List<Long>, visible: List<EntryDragBounds>): Int {
    val index = order.indexOf(id)
    if (index < 0) return 0
    val previous = order.getOrNull(index - 1)?.let { neighbor -> visible.firstOrNull { it.id == neighbor } }
    val next = order.getOrNull(index + 1)?.let { neighbor -> visible.firstOrNull { it.id == neighbor } }
    return when {
        previous != null && pointerY < previous.top + previous.height / 2 -> -1
        next != null && pointerY > next.top + next.height / 2 -> 1
        else -> 0
    }
}
