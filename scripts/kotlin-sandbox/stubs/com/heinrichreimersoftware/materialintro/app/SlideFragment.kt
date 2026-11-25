// Sandbox-only stand-ins, see ../../../../android/android_content.kt.
package com.heinrichreimersoftware.materialintro.app

import androidx.fragment.app.Fragment

/** One slide of the wizard; the platform renders it into a pager page. */
open class SlideFragment : Fragment() {
    /** The wizard this slide belongs to. */
    var wizard: IntroActivity? = null

    open fun getIntroActivity(): IntroActivity? = wizard

    open fun nextSlide() {}

    /** Refreshes the navigation buttons of the wizard. */
    protected open fun updateNavigation() {}

    open fun canGoBackward(): Boolean = true
    open fun canGoForward(): Boolean = true
}
