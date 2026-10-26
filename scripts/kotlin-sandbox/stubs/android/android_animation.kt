// Sandbox-only stand-ins, see android_content.kt.
package android.animation

import android.view.animation.Interpolator

/**
 * The property animator of the progress bars. The stand-in records the animation instead of
 * running it: no frames, no values, no timing.
 */
open class ObjectAnimator {
    /** The property that was animated. */
    var propertyName: String? = null
        private set

    /** The values the animation runs through. */
    var values: IntArray = intArrayOf()
        private set

    /** The duration that was set. */
    var duration: Long = 0
        private set

    /** The interpolator that was set. */
    var interpolator: Interpolator? = null
        private set

    /** How often the animation was started. */
    var starts = 0
        private set

    fun setDuration(duration: Long): ObjectAnimator {
        this.duration = duration
        return this
    }

    fun setInterpolator(interpolator: Interpolator?): ObjectAnimator {
        this.interpolator = interpolator
        return this
    }

    /** How often the animation repeats. */
    var repeatCount: Int = 0
        private set

    /** The evaluator that was set. */
    var evaluator: ArgbEvaluator? = null
        private set

    fun setRepeatCount(repeatCount: Int): ObjectAnimator {
        this.repeatCount = repeatCount
        return this
    }

    fun setEvaluator(evaluator: ArgbEvaluator?): ObjectAnimator {
        this.evaluator = evaluator
        return this
    }

    fun start() {
        starts++
    }

    /** How often the animation was cancelled. */
    var cancels = 0
        private set

    fun cancel() {
        cancels++
    }

    companion object {
        @JvmStatic
        fun ofInt(target: Any, propertyName: String, vararg values: Int): ObjectAnimator {
            val animator = ObjectAnimator()
            animator.propertyName = propertyName
            animator.values = values
            return animator
        }
    }
}

/** The colour evaluator of the intro warning flash. */
class ArgbEvaluator
