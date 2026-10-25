// Sandbox-only stand-ins, see ../../../android/android_content.kt.
//
// The fragment surface the converted about screens use. The host is a settable stand-in (the
// platform attaches and detaches a real FragmentActivity), so the checks can drive the fragment's
// view creation, string lookup and activity starts against a recording fake. No fragment
// lifecycle, child fragments, view tree or fragment transactions are modelled.
package androidx.fragment.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.viewpager.widget.PagerAdapter

/** Stand-in for the AndroidX fragment. */
open class Fragment {
    /** The attached host; the platform sets it on attach and clears it on detach. */
    var host: FragmentActivity? = null

    open fun onCreate(savedInstanceState: Bundle?) {}

    open fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                          savedInstanceState: Bundle?): View? = null

    open fun onSaveInstanceState(outState: Bundle) {}

    /** The platform getter is final and nullable; here it reports the attached host. */
    fun getActivity(): FragmentActivity? = host

    /** Resolves through the host, so the checks observe which string a call site requested. */
    fun getString(resId: Int): String = host?.getString(resId) ?: "res:$resId"

    /** The platform's formatted string lookup. */
    fun getString(resId: Int, vararg formatArgs: Any?): String =
        host?.getString(resId) ?: "res:$resId"

    open fun onDestroyView() {}

    /** The view of the fragment, if it created one. */
    var currentView: View? = null

    open fun getView(): View? = currentView

    /** The context the fragment is attached to, or null while it is detached. */
    fun getContext(): android.content.Context? = host

    /** The resources of the attached host. */
    fun getResources(): android.content.res.Resources = host?.resources ?: android.content.res.Resources()

    /** Forwards to the host; the checks record or refuse the start there. */
    open fun startActivity(intent: Intent) {
        host!!.startActivity(intent)
    }
}

/**
 * The activity type a fragment is attached to. The real class extends the platform activity chain;
 * this stand-in takes the stub `Activity` so a fake host inherits the recorded context surface.
 */
open class FragmentActivity : android.app.Activity() {
    private val supportFragmentManager = FragmentManager()

    open fun getSupportFragmentManager(): FragmentManager = supportFragmentManager
}

/** Fragment transactions are not modelled; the pager adapter only needs the type. */
open class FragmentManager {
    val restoredFragments: MutableList<Fragment> = ArrayList()

    open fun getFragments(): List<Fragment> = restoredFragments
}

/**
 * Stand-in for the pager adapter that hands out fragments per page. It records nothing itself;
 * the checks read the adapter the fake ViewPager received and call into it directly.
 */
abstract class FragmentPagerAdapter(private val fm: FragmentManager, private val behavior: Int) : PagerAdapter() {
    abstract fun getItem(position: Int): Fragment

    override fun getCount(): Int = 0

    override fun getPageTitle(position: Int): CharSequence? = null

    companion object {
        const val BEHAVIOR_RESUME_ONLY_CURRENT_FRAGMENT = 1
    }
}
