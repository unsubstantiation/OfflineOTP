package com.fairuniversal.offlineotp.ui.vault

import com.fairuniversal.offlineotp.ui.portable.PortableEntryDragBounds
import com.fairuniversal.offlineotp.ui.portable.portableEntryDragDirection

/** Geometry only: never retain a live entry or session authority in a drag gesture. */
data class EntryDragBounds(val id: Long, val top: Float, val height: Float)

/** Move one neighbour at a time, only after the held pointer crosses its midpoint. */
fun entryDragDirection(id: Long, pointerY: Float, order: List<Long>, visible: List<EntryDragBounds>): Int =
    portableEntryDragDirection(
        id = id,
        pointerY = pointerY,
        order = order,
        visible = visible.map { PortableEntryDragBounds(it.id, it.top, it.height) }
    )
