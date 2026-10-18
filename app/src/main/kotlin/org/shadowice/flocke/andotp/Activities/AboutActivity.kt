/*
 * Copyright (C) 2017-2020 Jakob Nixdorf
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package org.shadowice.flocke.andotp.Activities

import android.os.Bundle
import android.view.ViewStub
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentPagerAdapter
import androidx.viewpager.widget.ViewPager

import com.google.android.material.tabs.TabLayout
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.LibsBuilder

import org.shadowice.flocke.andotp.Fragments.AboutFragment
import org.shadowice.flocke.andotp.R

import java.io.InputStream
import java.util.Scanner

/** About screen hosting the app overview and open-source library license tabs. */
open class AboutActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setTitle(R.string.about_activity_title)
        setContentView(R.layout.activity_container)

        val toolbar = requireNotNull(findViewById<Toolbar>(R.id.container_toolbar))
        setSupportActionBar(toolbar)

        val stub = requireNotNull(findViewById<ViewStub>(R.id.container_stub))
        stub.setLayoutResource(R.layout.component_about)

        val v = stub.inflate()

        val viewPager = requireNotNull(v.findViewById<ViewPager>(R.id.viewPager))
        val tabLayout = requireNotNull(v.findViewById<TabLayout>(R.id.tabLayout))
        val aboutPageAdapter = AboutPageAdapter(getSupportFragmentManager())

        viewPager.setAdapter(aboutPageAdapter)
        tabLayout.setupWithViewPager(viewPager)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onBackPressed() {
        finish()
        super.onBackPressed()
    }

    /** Two tabs: the app's about page and the generated library list. */
    private inner class AboutPageAdapter(fm: FragmentManager) :
        FragmentPagerAdapter(fm, FragmentPagerAdapter.BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT) {

        override fun getItem(pos: Int): Fragment = when (pos) {
            0 -> AboutFragment()
            1 -> LibsBuilder()
                .withLibs(loadLibs())
                .withLicenseShown(true)
                .withVersionShown(true)
                .supportFragment()
            else -> throw IndexOutOfBoundsException("Invalid about page: $pos")
        }

        /** Loads the library list generated at build time by the AboutLibraries plugin. */
        private fun loadLibs(): Libs {
            val input: InputStream = resources.openRawResource(R.raw.aboutlibraries)
            input.use { stream ->
                Scanner(stream, "UTF-8").useDelimiter("\\A").use { scanner ->
                    return Libs.Builder().withJson(if (scanner.hasNext()) scanner.next() else "{}").build()
                }
            }
        }

        override fun getCount(): Int = 2

        override fun getPageTitle(pos: Int): CharSequence? = when (pos) {
            0 -> getString(R.string.about_tab_about)
            1 -> getString(R.string.about_tab_libraries)
            else -> null
        }
    }
}
