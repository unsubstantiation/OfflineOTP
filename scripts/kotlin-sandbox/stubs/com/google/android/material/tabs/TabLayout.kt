// Sandbox-only stand-ins, see ../../../../../android/android_content.kt.
package com.google.android.material.tabs

import android.view.ViewGroup
import androidx.viewpager.widget.ViewPager

/** Records the pager the tabs were wired to; no tab strip is laid out here. */
open class TabLayout(context: android.content.Context) : ViewGroup() {
    private var wiredPager: ViewPager? = null
    private val tabs = ArrayList<Tab>()
    private var listener: OnTabSelectedListener? = null
    var selectedTabPosition: Int = 0
    inner class Tab(val position: Int) {
        fun setText(value: CharSequence): Tab = this
        fun select() { selectedTabPosition = position; listener?.onTabSelected(this) }
    }
    interface OnTabSelectedListener {
        fun onTabSelected(tab: Tab)
        fun onTabUnselected(tab: Tab)
        fun onTabReselected(tab: Tab)
    }
    fun newTab(): Tab = Tab(tabs.size)
    fun addTab(tab: Tab) { tabs.add(tab) }
    fun addOnTabSelectedListener(value: OnTabSelectedListener) { listener = value }
    fun getTabAt(index: Int): Tab? = tabs.getOrNull(index)

    open fun setupWithViewPager(viewPager: ViewPager?) {
        wiredPager = viewPager
    }

    open fun getWiredPager(): ViewPager? = wiredPager
}
