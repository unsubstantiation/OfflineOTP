package com.fairuniversal.offlineotp.desktop.control

import com.fairuniversal.offlineotp.Database.Entry
import com.fairuniversal.offlineotp.ui.vault.VaultCardState
import java.util.IdentityHashMap

/** Manual ordering bound to the original entry and adjacent entry, never a stale display index. */
class DesktopEntryMove(private val state: DesktopVaultState, private val saveMove: (Int, Int) -> Boolean) {
    internal class Target(val entry: Entry, val previous: Entry?, val next: Entry?)
    class Frame internal constructor(internal val targets: IdentityHashMap<VaultCardState, Target>)

    fun bind(cards: List<VaultCardState>): Frame {
        val targets = IdentityHashMap<VaultCardState, Target>()
        if (!state.isLocked && state.presentation.canDrag()) {
            val shown = (0 until state.presentation.size()).map { state.presentation.get(it) }
            val byId = cards.associateBy { it.id }
            shown.forEachIndexed { index, entry ->
                byId[entry.getListId()]?.let { card ->
                    targets[card] = Target(entry, shown.getOrNull(index - 1), shown.getOrNull(index + 1))
                }
            }
        }
        return Frame(targets)
    }

    fun move(frame: Frame, card: VaultCardState, delta: Int): Boolean {
        if (state.isLocked || !state.presentation.canDrag() || delta !in listOf(-1, 1)) return false
        val target = frame.targets[card] ?: return false
        val neighbor = (if (delta < 0) target.previous else target.next) ?: return false
        val shown = (0 until state.presentation.size()).map { state.presentation.get(it) }
        val from = shown.indexOfFirst { it === target.entry }
        if (from < 0 || shown.getOrNull(from + delta) !== neighbor) return false
        return try { saveMove(from, from + delta) } catch (_: Exception) { false }
    }
}
