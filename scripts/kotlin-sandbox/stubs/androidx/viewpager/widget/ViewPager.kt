// Sandbox-only stand-ins, see ../../../android/android_content.kt.
//
// The pager surface the converted about screen wires its adapter into. `setAdapter` records the
// adapter instead of laying out pages, so the checks can drive the two about tabs.
package androidx.viewpager.widget

import android.view.ViewGroup

/** The adapter base class the fragment pager adapter extends. */
abstract class PagerAdapter {
    abstract fun getCount(): Int

    open fun getPageTitle(position: Int): CharSequence? = null
}

/** Records the installed adapter; no paging, measurement or page layout happens here. */
open class ViewPager : ViewGroup() {
    /** The page-change callbacks of the wizard. */
    interface OnPageChangeListener {
        fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int)
        fun onPageSelected(position: Int)
        fun onPageScrollStateChanged(state: Int)
    }

    /** The listeners that were added, in order. */
    val pageChangeListeners: MutableList<OnPageChangeListener> = ArrayList()

    fun addOnPageChangeListener(listener: OnPageChangeListener) {
        pageChangeListeners.add(listener)
    }

    private var installedAdapter: PagerAdapter? = null

    open fun setAdapter(adapter: PagerAdapter?) {
        installedAdapter = adapter
    }

    open fun getAdapter(): PagerAdapter? = installedAdapter
}
