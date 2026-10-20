package org.shadowice.flocke.andotp.View

import org.junit.Assert.assertEquals
import org.junit.Test

class TapSequenceControllerTest {
    private class FakeScheduler {
        data class Pending(val target: Any, val runnable: Runnable, val at: Long)

        var now = 0L
        val pending = mutableListOf<Pending>()

        fun post(target: Any, runnable: Runnable, delay: Long) {
            pending.add(Pending(target, runnable, now + delay))
        }

        fun remove(target: Any, runnable: Runnable) {
            pending.removeAll { it.target === target && it.runnable === runnable }
        }

        fun advance(milliseconds: Long) {
            val targetTime = now + milliseconds
            while (true) {
                val next = pending.minByOrNull { it.at } ?: break
                if (next.at > targetTime) break
                pending.remove(next)
                now = next.at
                next.runnable.run()
            }
            now = targetTime
        }
    }

    private class Results {
        val singles = mutableListOf<Any>()
        val doubles = mutableListOf<Any>()
    }

    private fun controller(scheduler: FakeScheduler, results: Results) = TapSequenceController<Any>(
        nowMillis = { scheduler.now },
        postDelayed = scheduler::post,
        removeCallbacks = scheduler::remove,
        onSingleClick = { results.singles.add(it) },
        onDoubleClick = { results.doubles.add(it) }
    )

    @Test
    fun singleClickRunsAfterTheDoubleTapWindow() {
        val scheduler = FakeScheduler()
        val results = Results()
        val target = Any()
        val controller = controller(scheduler, results)

        controller.onClick(target)
        scheduler.advance(299)
        assertEquals(emptyList<Any>(), results.singles)
        scheduler.advance(1)
        assertEquals(listOf(target), results.singles)
        assertEquals(emptyList<Any>(), results.doubles)
    }

    @Test
    fun twoClicksOnTheSameTargetCancelThePendingSingle() {
        val scheduler = FakeScheduler()
        val results = Results()
        val target = Any()
        val controller = controller(scheduler, results)

        controller.onClick(target)
        scheduler.advance(100)
        controller.onClick(target)
        scheduler.advance(500)

        assertEquals(emptyList<Any>(), results.singles)
        assertEquals(listOf(target), results.doubles)
    }

    @Test
    fun clickOnAnotherTargetDoesNotSwallowTheFirstSingle() {
        val scheduler = FakeScheduler()
        val results = Results()
        val first = Any()
        val second = Any()
        val controller = controller(scheduler, results)

        controller.onClick(first)
        scheduler.advance(100)
        controller.onClick(second)
        assertEquals(listOf(first), results.singles)
        scheduler.advance(300)
        assertEquals(listOf(first, second), results.singles)
        assertEquals(emptyList<Any>(), results.doubles)
    }

    @Test
    fun rapidThirdClickAfterDoubleIsIgnoredUntilTheSequenceEnds() {
        val scheduler = FakeScheduler()
        val results = Results()
        val target = Any()
        val controller = controller(scheduler, results)

        controller.onClick(target)
        scheduler.advance(100)
        controller.onClick(target)
        controller.onClick(target)

        assertEquals(emptyList<Any>(), results.singles)
        assertEquals(listOf(target), results.doubles)
        assertEquals(0, scheduler.pending.size)

        scheduler.advance(300)
        controller.onClick(target)
        scheduler.advance(300)

        assertEquals(listOf(target), results.singles)
        assertEquals(listOf(target), results.doubles)
    }

    @Test
    fun clickOutsideTheWindowDeliversBothSingles() {
        val scheduler = FakeScheduler()
        val results = Results()
        val target = Any()
        val controller = controller(scheduler, results)

        controller.onClick(target)
        scheduler.advance(300)
        controller.onClick(target)
        scheduler.advance(300)

        assertEquals(listOf(target, target), results.singles)
        assertEquals(emptyList<Any>(), results.doubles)
    }

    @Test
    fun canceledStaleCallbackCannotDeliverASingle() {
        val scheduler = FakeScheduler()
        val results = Results()
        val target = Any()
        val controller = controller(scheduler, results)

        controller.onClick(target)
        val stale = scheduler.pending.single().runnable
        controller.cancelPending()
        stale.run()

        assertEquals(emptyList<Any>(), results.singles)
        assertEquals(emptyList<Any>(), results.doubles)
    }
}
