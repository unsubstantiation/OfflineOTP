// Sandbox-only stand-ins, see android_content.kt.
package android.app

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.view.View
import android.view.Window

/**
 * The dialog only records what the builder wired up so the checks can drive the buttons; no
 * window/loopers are involved.
 */
class AlertDialog private constructor() : DialogInterface {
    var shown = false
        private set
    var titleId = 0
        private set
    var message: CharSequence? = null
        private set
    var contentView: View? = null
        private set
    var positiveButtonListener: DialogInterface.OnClickListener? = null
        private set
    var negativeButtonListener: DialogInterface.OnClickListener? = null
        private set
    var neutralButtonListener: DialogInterface.OnClickListener? = null
        private set
    var dismissals = 0
        private set

    fun show() {
        shown = true
    }

    /** The buttons of the dialog, by the platform's button constants. */
    val buttons: MutableMap<Int, android.widget.Button> = LinkedHashMap()

    /** Whether the dialog can be cancelled. */
    var cancelable: Boolean = true
        private set

    /** The cancel listener that was set. */
    var onCancelListener: DialogInterface.OnCancelListener? = null
        private set

    fun setCancelable(cancelable: Boolean) {
        this.cancelable = cancelable
    }

    fun setOnCancelListener(listener: DialogInterface.OnCancelListener?) {
        onCancelListener = listener
    }

    fun getButton(which: Int): android.widget.Button? = buttons[which]

    companion object {
        const val BUTTON_POSITIVE = -1
        const val BUTTON_NEGATIVE = -2
        const val BUTTON_NEUTRAL = -3
    }

    override fun dismiss() {
        dismissals++
    }

    /** The dismiss listener that was set. */
    var onDismissListener: DialogInterface.OnDismissListener? = null
        private set

    fun setOnDismissListener(listener: DialogInterface.OnDismissListener?) {
        onDismissListener = listener
    }

    /** The dialog was cancelled. */
    var cancelled = false
        private set

    fun cancel() {
        cancelled = true
    }

    class Builder(private val context: Context) {
        private var titleId = 0
        private var messageId = 0
        private var message: CharSequence? = null
        private var contentView: View? = null
        private var positiveListener: DialogInterface.OnClickListener? = null
        private var negativeListener: DialogInterface.OnClickListener? = null
        private var neutralListener: DialogInterface.OnClickListener? = null
        private var cancelable: Boolean = true
        private var onCancelListener: DialogInterface.OnCancelListener? = null

        fun setTitle(titleId: Int): Builder {
            this.titleId = titleId
            return this
        }

        fun setMessage(messageId: Int): Builder {
            this.messageId = messageId
            return this
        }

        fun setMessage(message: CharSequence?): Builder {
            this.message = message
            return this
        }

        fun setView(view: View?): Builder {
            this.contentView = view
            return this
        }

        fun setPositiveButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder {
            this.positiveListener = listener
            return this
        }

        fun setPositiveButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            this.positiveListener = listener
            return this
        }

        fun setNegativeButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder {
            this.negativeListener = listener
            return this
        }

        fun setNegativeButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            this.negativeListener = listener
            return this
        }

        fun setNeutralButton(textId: Int, listener: DialogInterface.OnClickListener?): Builder {
            this.neutralListener = listener
            return this
        }

        fun setNeutralButton(text: CharSequence?, listener: DialogInterface.OnClickListener?): Builder {
            this.neutralListener = listener
            return this
        }

        fun setCancelable(cancelable: Boolean): Builder {
            this.cancelable = cancelable
            return this
        }

        fun setOnCancelListener(listener: DialogInterface.OnCancelListener?): Builder {
            this.onCancelListener = listener
            return this
        }

        fun show(): AlertDialog {
            val dialog = create()
            dialog.show()
            return dialog
        }

        fun create(): AlertDialog {
            val dialog = AlertDialog()
            dialog.titleId = titleId
            dialog.message = message
            dialog.contentView = contentView
            dialog.positiveButtonListener = positiveListener
            dialog.negativeButtonListener = negativeListener
            dialog.neutralButtonListener = neutralListener
            dialog.cancelable = cancelable
            dialog.onCancelListener = onCancelListener
            lastCreated = dialog
            created.add(dialog)
            return dialog
        }

        companion object {
            /** The last dialog a check created; production dialogs are tracked by the OS instead. */
            var lastCreated: AlertDialog? = null

            /** Every dialog a check created, in creation order. */
            val created: MutableList<AlertDialog> = ArrayList()
        }
    }
}

/** The device credential challenge of the main screen. */
open class KeyguardManager {
    /** Whether the device has a secure lock screen. */
    var keyguardSecure: Boolean = true

    /** The challenge that was created. */
    var challenge: Intent? = null

    fun isKeyguardSecure(): Boolean = keyguardSecure

    fun createConfirmDeviceCredentialIntent(title: String?, description: String?): Intent? = challenge
}

/** Recording stand-in: the checks read back the channels and notifications a call produced. */
open class NotificationManager {
    val channels = ArrayList<NotificationChannel>()
    val posted = ArrayList<Pair<Int, Notification>>()

    fun createNotificationChannel(channel: NotificationChannel) {
        channels.add(channel)
    }

    fun notify(id: Int, notification: Notification) {
        posted.add(id to notification)
    }

    companion object {
        const val IMPORTANCE_LOW = 2
        const val IMPORTANCE_DEFAULT = 3
        const val IMPORTANCE_HIGH = 4
    }
}

class NotificationChannel(private val id: String?, private var name: CharSequence?, private var importance: Int) {
    private var description: String? = null
    fun getId(): String? = id
    fun getName(): CharSequence? = name
    fun getDescription(): String? = description
    fun getImportance(): Int = importance
    fun setName(name: CharSequence?) { this.name = name }
    fun setDescription(description: String?) { this.description = description }
    fun setImportance(importance: Int) { this.importance = importance }
}

/** What the AndroidX builder stand-in recorded; the platform object has no such accessors. */
class Notification(val channelId: String?, val smallIcon: Int, val title: CharSequence?, val bigText: CharSequence?)

/** A context subclass so a fragment can hand one back; no lifecycle is modelled. */
open class Activity : Context() {
    /** The base-context hook supplied by Android before activity creation. */
    open fun attachBaseContext(newBase: Context) {}

    /** Requests a fresh activity instance with its current base-context configuration. */
    open fun recreate() {}

    /** The resume callback supplied by Android before an activity is interactive. */
    open fun onResume() {}

    /** The intent the activity was started with; the platform returns the launch intent. */
    open fun getIntent(): Intent? = null

    /** Replaces the cached launch intent, as the platform does when a running activity is re-targeted. */
    open fun setIntent(newIntent: Intent?) {}

    /** Called when a running singleInstance activity receives a new intent. */
    open fun onNewIntent(intent: Intent?) {}

    /** The window the activity owns. */
    val activityWindow = Window()

    /** How often the activity removed its task. */
    var removedTasks = 0
        private set

    /** How often the activity was finished. */
    var finishes = 0
        private set

    /** True once the activity was finished. */
    private var finishing = false

    /** The result the activity reported to the caller. */
    var resultCode: Int = RESULT_CANCELED
        private set
    var resultData: Intent? = null
        private set

    open fun getWindow(): Window = activityWindow

    /** The platform declares this as `isFinishing()`; the call syntax is kept. */
    open fun isFinishing(): Boolean = finishing

    open fun setResult(resultCode: Int, data: Intent?) {
        this.resultCode = resultCode
        this.resultData = data
    }

    open fun finish() {
        finishes++
        finishing = true
    }

    /** Removes the activity's task; the platform also drops the task from the recents list. */
    open fun finishAndRemoveTask() {
        removedTasks++
    }

    /** How often the activity moved its task to the back. */
    var backgroundedTasks = 0
        private set

    open fun moveTaskToBack(nonRoot: Boolean): Boolean {
        backgroundedTasks++
        return true
    }

    /** The requests the activity started, in order. */
    val startedRequests = ArrayList<Pair<Intent, Int>>()

    open fun startActivityForResult(intent: Intent, requestCode: Int) {
        startedRequests.add(intent to requestCode)
    }

    /** The intent senders the activity forwarded, in order. */
    val forwardedIntentSenders = ArrayList<IntentSender>()

    open fun startIntentSenderForResult(sender: IntentSender, requestCode: Int, fillInIntent: Intent?,
                                        flagsMask: Int, flagsValues: Int, extraFlags: Int) {
        forwardedIntentSenders.add(sender)
    }

    protected open fun onCreate(savedInstanceState: Bundle?) {}

    protected open fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {}

    companion object {
        const val RESULT_OK = -1
        const val RESULT_CANCELED = 0
    }
}

/** A pending intent; the OpenPGP app returns one when the user has to confirm. */
class PendingIntent(val intentSender: android.content.IntentSender = android.content.IntentSender()) :
    android.os.Parcelable

/** The deprecated platform fragment the settings screen still uses; it only records requests. */
open class Fragment {
    /**
     * The host the fragment is attached to. The platform sets it on attach and clears it on
     * detach; the checks set it so a fragment can reach its activity.
     */
    var host: Activity? = null

    open fun getActivity(): Activity? = host

    /** The view of the fragment, if it created one. */
    open fun getView(): View? = null

    /** The string resources of the attached host. */
    open fun getString(resId: Int): String = "res:$resId"

    /** The string resources of the attached host. */
    open fun getString(resId: Int, vararg formatArgs: Any?): String = "res:$resId"
    val requested = ArrayList<Pair<List<String>, Int>>()
    fun requestPermissions(permissions: Array<String>, requestCode: Int) {
        requested.add(permissions.toList() to requestCode)
    }

    /** The retained flag; the platform keeps the fragment across configuration changes. */
    var retainedInstance = false
        private set

    fun setRetainInstance(retain: Boolean) {
        retainedInstance = retain
    }

    open fun onDestroy() {}

    protected open fun onCreate(savedInstanceState: android.os.Bundle?) {}
}

/**
 * Stand-in for the deprecated platform fragment manager. The transactions are recorded, not
 * executed: the platform attaches and detaches the fragments itself.
 */
open class FragmentManager {
    /** The fragments that were added or removed, in order. */
    val transactions = ArrayList<String>()

    /** The tags the manager was asked about, in order. */
    val lookups = ArrayList<String?>()

    /** The fragment a tag resolves to; the checks set it to fake a retained fragment. */
    var taggedFragment: Fragment? = null

    fun beginTransaction(): FragmentTransaction = FragmentTransaction(this)

    fun findFragmentByTag(tag: String?): Fragment? {
        lookups.add(tag)
        return taggedFragment
    }
}

/** Stand-in for a platform fragment transaction; nothing is committed. */
class FragmentTransaction(private val manager: FragmentManager) {
    fun add(fragment: Fragment, tag: String?): FragmentTransaction {
        manager.transactions.add("add:$tag")
        return this
    }

    fun remove(fragment: Fragment): FragmentTransaction {
        manager.transactions.add("remove")
        return this
    }

    fun replace(containerViewId: Int, fragment: Fragment): FragmentTransaction {
        manager.transactions.add("replace:$containerViewId")
        return this
    }

    fun commit(): Int {
        manager.transactions.add("commit")
        return 0
    }
}
