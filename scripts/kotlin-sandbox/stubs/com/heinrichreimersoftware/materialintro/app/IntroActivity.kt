// Sandbox-only stand-ins, see ../../../../android/android_content.kt.
//
// The material-intro library the first-run wizard extends. The stand-in records the slides, the
// listeners and the current position; no page is laid out.
package com.heinrichreimersoftware.materialintro.app

import android.content.Intent
import androidx.viewpager.widget.ViewPager
import com.heinrichreimersoftware.materialintro.slide.Slide

/** The wizard activity the intro screen extends. */
open class IntroActivity : androidx.appcompat.app.AppCompatActivity() {
    /** The slides that were added, in order. */
    val addedSlides: MutableList<Slide> = ArrayList()

    /** The back-button behaviour that was set. */
    var buttonBackFunction: Int = BUTTON_BACK_FUNCTION_BACK
        private set

    /** The navigation-blocked listeners that were added, in order. */
    val navigationBlockedListeners: MutableList<OnNavigationBlockedListener> = ArrayList()

    /** The page-change listeners that were added, in order. */
    val pageChangeListeners: MutableList<ViewPager.OnPageChangeListener> = ArrayList()

    /** The position of the current slide. */
    var currentSlidePosition: Int = 0

    /** The result the wizard returned to its caller. */
    var sentResult: Intent? = null
        private set

    fun setButtonBackFunction(function: Int) {
        buttonBackFunction = function
    }

    fun addSlide(slide: Slide) {
        addedSlides.add(slide)
    }

    fun getSlides(): List<Slide> = addedSlides

    fun getCount(): Int = addedSlides.size

    /** Compile-only presentation APIs; the stand-in does not animate or render slides. */
    fun getBackground(position: Int): Int = 0
    fun goToSlide(position: Int): Boolean {
        currentSlidePosition = position
        return true
    }

    fun addOnNavigationBlockedListener(listener: OnNavigationBlockedListener) {
        navigationBlockedListeners.add(listener)
    }

    fun addOnPageChangeListener(listener: ViewPager.OnPageChangeListener) {
        pageChangeListeners.add(listener)
    }

    /** The result the wizard hands back to the activity that started it. */
    open fun onSendActivityResult(result: Int): Intent {
        sentResult = Intent()
        return sentResult!!
    }

    /** The navigation-blocked callback. */
    fun interface OnNavigationBlockedListener {
        fun onNavigationBlocked(position: Int, direction: Int)
    }

    companion object {
        const val BUTTON_BACK_FUNCTION_BACK = 0
        const val BUTTON_BACK_FUNCTION_CROSS = 1
        const val BUTTON_BACK_FUNCTION_SKIP = 2
        const val BUTTON_BACK_FUNCTION_NONE = 3
    }
}
