// Sandbox-only stand-ins, see ../../../android/android_content.kt.
//
// The AppCompat activity surface the converted base activities use. It records what the activity
// did (theme, window color, registered receivers, posted UI-thread work, finished state and
// dispatched touches) so the checks can drive the screen-off and obscured-touch logic. It is NOT
// an activity: there is no window, decor, lifecycle, configuration change or fragment support.
package androidx.appcompat.app

import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.FragmentManager

/** Stand-in for the AppCompat activity. */
open class AppCompatActivity : android.app.Activity() {
    /** The receivers the activity registered, in registration order. */
    val registeredReceivers = ArrayList<Pair<BroadcastReceiver, IntentFilter>>()

    /** The deliveries that were posted to the UI thread, in order. */
    val uiThreadDeliveries = ArrayList<Runnable>()

    /** The touch events that were dispatched to the activity. */
    val dispatchedTouches = ArrayList<MotionEvent>()

    /** The bundles the activity was created with. */
    val createdWith = ArrayList<Bundle?>()

    /** The title/content resources the activity set, recorded instead of resolved. */
    var titleResource = 0
        private set
    var contentResource = 0
        private set

    /** The toolbar the activity installed as its support action bar. */
    var supportActionBar: Toolbar? = null
        private set

    /** The action bar the support toolbar was installed in. */
    var actionBar: ActionBar? = null
        private set

    private val supportFragmentManager = FragmentManager()

    /** The deprecated platform fragment manager the task base activity uses. */
    private val platformFragmentManager = android.app.FragmentManager()

    open fun getFragmentManager(): android.app.FragmentManager = platformFragmentManager

    override fun onCreate(savedInstanceState: Bundle?) {
        createdWith.add(savedInstanceState)
    }

    protected open fun onStart() {}

    override fun onResume() {}

    override fun onPause() {}

    protected open fun onStop() {}

    protected open fun onDestroy() {}

    override fun getWindow(): android.view.Window = activityWindow

    open fun setTitle(resId: Int) {
        titleResource = resId
    }

    open fun setContentView(resId: Int) {
        contentResource = resId
    }

    open fun setSupportActionBar(toolbar: Toolbar?) {
        supportActionBar = toolbar
        actionBar = ActionBar(toolbar)
    }

    /** The platform getter is `getSupportActionBar()`; Kotlin sees the `supportActionBar` property. */
    fun getSupportActionBar(): ActionBar? = actionBar

    /**
     * `open` so the about checks can supply the toolbar/stub/pager views; the platform resolves
     * the real window hierarchy instead and the getter is final there.
     */
    open fun <T : View> findViewById(id: Int): T? = null

    open fun getSupportFragmentManager(): FragmentManager = supportFragmentManager

    /** The up-navigation hook; the converted about screen finishes instead of navigating. */
    open fun onSupportNavigateUp(): Boolean = false

    open fun onBackPressed() {}

    /** Records the delivery instead of running it on a looper; the checks drain it themselves. */
    open fun runOnUiThread(action: Runnable) {
        uiThreadDeliveries.add(action)
    }

    open fun dispatchTouchEvent(event: MotionEvent): Boolean {
        dispatchedTouches.add(event)
        return true
    }

    /** The menu that was inflated into the activity. */
    val inflatedMenus = ArrayList<Pair<Int, android.view.Menu>>()

    open fun getMenuInflater(): android.view.MenuInflater = android.view.MenuInflater()

    open fun onCreateOptionsMenu(menu: android.view.Menu): Boolean = false

    open fun onOptionsItemSelected(item: android.view.MenuItem): Boolean = false

    open fun invalidateOptionsMenu() {}

    override fun recreate() {}

    open fun onUserInteraction() {}

    open fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean = false

    protected open fun onPostCreate(savedInstanceState: Bundle?) {}

    protected open fun onConfigurationChanged(newConfig: android.content.res.Configuration) {}

    protected open fun onSaveInstanceState(outState: Bundle) {}

    open fun registerReceiver(receiver: BroadcastReceiver, filter: IntentFilter): Intent? {
        registeredReceivers.add(receiver to filter)
        return null
    }

    open fun unregisterReceiver(receiver: BroadcastReceiver) {
        registeredReceivers.removeAll { it.first === receiver }
    }
}

/** The action bar of the support toolbar; the converted screens only set its title. */
open class ActionBar(private val toolbar: Toolbar?) {
    /** The title resource that was set. */
    var titleResource = 0
        private set

    fun setTitle(resId: Int) {
        titleResource = resId
    }

    fun setTitle(title: CharSequence?) {}
}
