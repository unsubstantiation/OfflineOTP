// Sandbox-only stand-ins, see ../../../../../android/android_content.kt.
package com.google.android.material.tabs

import android.view.ViewGroup
import androidx.viewpager.widget.ViewPager

/** Records the pager the tabs were wired to; no tab strip is laid out here. */
open class TabLayout(context: android.content.Context) : ViewGroup() {
    private var wiredPager: ViewPager? = null

    open fun setupWithViewPager(viewPager: ViewPager?) {
        wiredPager = viewPager
    }

    open fun getWiredPager(): ViewPager? = wiredPager
}
