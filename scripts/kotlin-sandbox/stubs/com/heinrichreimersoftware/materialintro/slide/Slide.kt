// Sandbox-only stand-ins, see ../../../../android/android_content.kt.
package com.heinrichreimersoftware.materialintro.slide

import androidx.fragment.app.Fragment

/** The base of the slides the wizard shows. */
open class Slide {
    /** The fragment this slide shows; the pager may replace it with the restored instance. */
    open var fragment: Fragment? = null
}

/** A slide that shows a fragment. */
open class FragmentSlide : Slide() {

    class Builder {
        private var slide = FragmentSlide()

        fun background(color: Int): Builder = this
        fun backgroundDark(color: Int): Builder = this
        fun fragment(fragment: Fragment): Builder {
            slide.fragment = fragment
            return this
        }
        fun build(): FragmentSlide = slide
    }
}

/** A slide with a title and a description. */
open class SimpleSlide : Slide() {
    class Builder {
        private var slide = SimpleSlide()

        fun title(resId: Int): Builder = this
        fun description(resId: Int): Builder = this
        fun background(color: Int): Builder = this
        fun backgroundDark(color: Int): Builder = this
        fun canGoBackward(canGoBackward: Boolean): Builder = this
        fun scrollable(scrollable: Boolean): Builder = this
        fun build(): SimpleSlide = slide
    }
}
