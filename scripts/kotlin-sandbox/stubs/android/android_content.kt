// Sandbox-only stand-ins for the Android platform surface used by the checked classes.
// They exist so the Kotlin sources can be type-checked without the Android SDK. They are NOT
// the Android platform and prove nothing about SDK behaviour. Real Java getters are modelled as
// Kotlin properties because Kotlin only synthesises properties for Java declarations.
package android.content

import android.content.res.Resources
import android.net.Uri

open class Context {
    /** Open only to let the preference check supply explicit fake attribute values. */
    open fun obtainStyledAttributes(attrs: android.util.AttributeSet?, styleable: IntArray): android.content.res.TypedArray = android.content.res.TypedArray()
    val theme: Resources.Theme get() = Resources.Theme()
    /**
     * `open` so the settings checks can substitute a resource view with distinct, check-owned values;
     * the default returns an inert [Resources].
     */
    open val resources: Resources get() = Resources()
    /**
     * `open` so the settings checks can map a resource id to a distinct key string. The default is a
     * recognisable placeholder, not a real resource lookup.
     */
    open fun getString(resId: Int): String = "stub"

    /**
     * The theme that was applied to this context. The platform keeps the theme in the base
     * context; here the applied id is only recorded, no theme is resolved.
     */
    var appliedTheme: Int = 0
        private set

    /** Records the applied theme; the platform resolves and stores the theme. */
    open fun setTheme(theme: Int) {
        appliedTheme = theme
    }

    /** The base context; the platform returns the context the application context was made from. */
    open fun getBaseContext(): Context = this
    /** The last configuration passed to the locale-context factory. */
    var createdConfiguration: android.content.res.Configuration? = null
        private set
    /** Configuration wrapper used by locale-aware activities. The sandbox records, but does not resolve, resources. */
    open fun createConfigurationContext(configuration: android.content.res.Configuration): Context {
        createdConfiguration = configuration
        return this
    }

    /**
     * Format overload; the result stays a recognisable placeholder, arguments are not applied.
     * `open` so the checks can substitute a value that carries the formatted arguments, the way
     * the platform's `Context.getString(int, Object...)` does.
     */
    open fun getString(resId: Int, vararg formatArgs: Any?): String = "text:$resId"
    /** Resolves to a recognisable placeholder so the checks can tell which resource was read. */
    fun getText(resId: Int): CharSequence = "text:$resId"
    /** `open` so a fake activity can grant or deny; the default denies, like an ungranted app. */
    open fun checkSelfPermission(permission: String): Int = android.content.pm.PackageManager.PERMISSION_DENIED
    /** `open` so checks can return a recording service instead of the null default. */
    open fun getSystemService(name: String): Any? = null

    /** The context itself; the platform returns the wrapper's base context. */
    open val context: Context get() = this

    /** The layout inflater of the context. */
    open fun getLayoutInflater(): android.view.LayoutInflater = android.view.LayoutInflater.from(this)
    /** The task classes keep only the application context; the checks substitute their own. */
    open fun getApplicationContext(): Context = this
    fun getAssets(): android.content.res.AssetManager = android.content.res.AssetManager()
    fun getFilesDir(): java.io.File = java.io.File(".")
    /** `open` so the storage checks can substitute a recording resolver for the inert default. */
    open fun getContentResolver(): ContentResolver = ContentResolver()
    /** `open` so the storage checks can supply their own private cache directory. */
    open fun getCacheDir(): java.io.File = java.io.File(".")
    /** `open` so the about checks can report a package that is not installed. */
    open fun getPackageManager(): android.content.pm.PackageManager = android.content.pm.PackageManager
    open fun getApplicationInfo(): android.content.pm.ApplicationInfo = android.content.pm.ApplicationInfo()
    open fun getPackageName(): String = "org.shadowice.flocke.andotp"
    /** `open` so the about checks can record or refuse the activity start. */
    open fun startActivity(intent: Intent) {}
    open fun sendBroadcast(intent: Intent) {}

    companion object {
        const val CLIPBOARD_SERVICE = "clipboard"
        const val INPUT_METHOD_SERVICE = "input_method"
        const val NOTIFICATION_SERVICE = "notification"
        const val KEYGUARD_SERVICE = "keyguard"
    }
}

/**
 * Resolver surface used by the storage helper. The defaults are inert (`null`, like a provider that
 * returns nothing); the storage checks subclass it with a recording fake that serves their own
 * documents. No content provider, permission or URI-grant behaviour of the platform is modelled.
 */
open class ContentResolver {
    open fun openFileDescriptor(uri: Uri?, mode: String?): android.os.ParcelFileDescriptor? = null
    open fun query(uri: Uri?, projection: Array<String>?, selection: String?, selectionArgs: Array<String>?, sortOrder: String?): android.database.Cursor? = null
    open fun openInputStream(uri: Uri?): java.io.InputStream? = null
    open fun openOutputStream(uri: Uri?): java.io.OutputStream? = null
    open fun getType(uri: Uri?): String? = null

    /** The persistable permissions that were taken. */
    val takenPermissions: MutableList<Pair<Uri?, Int>> = ArrayList()

    open fun takePersistableUriPermission(uri: Uri?, takeFlags: Int) {
        takenPermissions.add(uri to takeFlags)
    }
}

/**
 * Field/type-only stand-in for the preference interface. The converted `Settings` class is
 * *executed* against it through [android.preference.PreferenceManager]'s in-memory implementation;
 * this interface itself models no persistence, listener delivery or type-mismatch behaviour.
 */
interface SharedPreferences {
    fun getAll(): Map<String, *>?
    fun getString(key: String?, defValue: String?): String?
    fun getStringSet(key: String?, defValues: Set<String>?): Set<String>?
    fun getInt(key: String?, defValue: Int): Int
    fun getLong(key: String?, defValue: Long): Long
    fun getBoolean(key: String?, defValue: Boolean): Boolean
    fun contains(key: String?): Boolean
    fun edit(): Editor

    interface Editor {
        fun putString(key: String?, value: String?): Editor
        fun putStringSet(key: String?, values: Set<String>?): Editor
        fun putInt(key: String?, value: Int): Editor
        fun putLong(key: String?, value: Long): Editor
        fun putBoolean(key: String?, value: Boolean): Editor
        fun remove(key: String?): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }

    fun registerOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?)
    fun unregisterOnSharedPreferenceChangeListener(listener: OnSharedPreferenceChangeListener?)

    fun interface OnSharedPreferenceChangeListener {
        fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?)
    }
}

/** Clipboard description, including the sensitive-content extra. */
class ClipDescription {
    var extras: android.os.PersistableBundle? = null

    companion object {
        const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}

class ClipData private constructor(val label: CharSequence?, val text: CharSequence?) {
    val description = ClipDescription()

    companion object {
        @JvmStatic
        fun newPlainText(label: CharSequence?, text: CharSequence?): ClipData = ClipData(label, text)
    }
}

class ClipboardManager {
    /** The clips that were set, in order; the platform hands them to other apps instead. */
    val clips = ArrayList<ClipData>()

    fun setPrimaryClip(clip: ClipData) {
        clips.add(clip)
    }
}

/**
 * Field/type-only stand-in; the checked code only reads the action of an intent.
 *
 * `getAction()` is a method (not a property) on purpose: the platform class is a Java class, so
 * the converted receivers call `intent.getAction()` and that call must resolve here as well.
 */
open class Intent(action: String? = null) {
    /** The action the stand-in was created with. */
    var intentAction: String? = action

    /** The data URI the stand-in received; the platform resolves it into the intent. */
    var data: android.net.Uri? = null

    /** The action of the intent, or null. */
    open fun getAction(): String? = intentAction

    /** The data URI as a string, the way the platform hands it to the URI parser. */
    open fun getDataString(): String? = data?.toString()

    /** Records the explicit package/class target; the platform resolves the component. */
    open fun setClassName(packageName: String, className: String): Intent = this

    /** The flags the intent carries. */
    var intentFlags: Int = 0
        private set

    /** The class the intent targets. */
    var targetClass: Class<*>? = null
        private set

    /** The intent that targets a class. */
    constructor(context: Context, target: Class<*>?) : this() {
        targetClass = target
    }

    /** The extras the intent received; the platform stores them in a bundle. */
    val extras: MutableMap<String, Any?> = LinkedHashMap()

    open fun getStringExtra(name: String): String? = extras[name] as? String

    open fun getIntExtra(name: String, defaultValue: Int): Int = extras[name] as? Int ?: defaultValue

    open fun getBooleanExtra(name: String, defaultValue: Boolean): Boolean =
        extras[name] as? Boolean ?: defaultValue

    open fun hasExtra(name: String): Boolean = extras.containsKey(name)

    open fun putExtra(name: String, value: String?): Intent {
        extras[name] = value
        return this
    }

    open fun putExtra(name: String, value: Int): Intent {
        extras[name] = value
        return this
    }

    open fun putExtra(name: String, value: android.net.Uri?): Intent {
        extras[name] = value
        return this
    }

    open fun putExtra(name: String, value: Long): Intent {
        extras[name] = value
        return this
    }

    open fun putExtra(name: String, value: Boolean): Intent {
        extras[name] = value
        return this
    }

    open fun putExtra(name: String, value: Array<String>?): Intent {
        extras[name] = value
        return this
    }

    /** The platform declares this as `<T extends Parcelable> T getParcelableExtra(String)`. */
    @Suppress("UNCHECKED_CAST")
    open fun <T : android.os.Parcelable> getParcelableExtra(name: String): T? =
        extras[name] as? T

    /** The activity that would handle the intent; the platform queries the package manager. */
    open fun resolveActivity(pm: android.content.pm.PackageManager): android.content.ComponentName? = null

    open fun addCategory(category: String): Intent = this

    open fun addFlags(flags: Int): Intent = this

    open fun getFlags(): Int = intentFlags

    open fun setType(type: String?): Intent = this

    open fun setAction(action: String?): Intent {
        intentAction = action
        return this
    }


    companion object {
        const val ACTION_SCREEN_OFF = "android.intent.action.SCREEN_OFF"
        const val ACTION_VIEW = "android.intent.action.VIEW"
        const val ACTION_SEND = "android.intent.action.SEND"
        const val ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT"
        const val ACTION_CREATE_DOCUMENT = "android.intent.action.CREATE_DOCUMENT"
        const val ACTION_GET_CONTENT = "android.intent.action.GET_CONTENT"
        const val ACTION_OPEN_DOCUMENT_TREE = "android.intent.action.OPEN_DOCUMENT_TREE"
        const val FLAG_ACTIVITY_NEW_TASK = 0x10000000
        const val FLAG_GRANT_READ_URI_PERMISSION = 0x00000001
        const val FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002
        const val FLAG_GRANT_PERSISTABLE_URI_PERMISSION = 0x00000040
        const val FLAG_GRANT_PREFIX_URI_PERMISSION = 0x00000080
        const val CATEGORY_OPENABLE = "android.intent.category.OPENABLE"
        const val EXTRA_TEXT = "android.intent.extra.TEXT"
        const val EXTRA_TITLE = "android.intent.extra.TITLE"
    }
}

/** The name of the activity that handles an intent. */
class ComponentName(private val packageName: String, private val className: String) {
    override fun toString(): String = "$packageName/$className"
}

/** The platform's intent sender; the backup screen hands it back to the OpenPGP app. */
class IntentSender {
    class SendIntentException : Exception()
}

/** Thrown when no activity can handle an intent; the fallback paths catch it. */
class ActivityNotFoundException : RuntimeException()

/**
 * Stand-in for the platform broadcast receiver base class. Nothing is dispatched here; the
 * converted receivers are called directly by the checks.
 */
abstract class BroadcastReceiver {
    abstract fun onReceive(context: Context, intent: Intent?)
}

/** Field/type-only stand-in for the platform intent filter. */
class IntentFilter(action: String? = null) {
    /** The action the filter was created with. */
    val action: String? = action
}

/** The nested listener mirrors the platform nesting; `fun interface` keeps SAM conversion working. */
interface DialogInterface {
    fun dismiss()

    fun interface OnCancelListener {
        fun onCancel(dialog: DialogInterface?)
    }

    fun interface OnDismissListener {
        fun onDismiss(dialog: DialogInterface?)
    }

    fun interface OnClickListener {
        fun onClick(dialog: DialogInterface, which: Int)
    }

    companion object {
        const val BUTTON_NEGATIVE = -2
        const val BUTTON_POSITIVE = -1
        const val BUTTON_NEUTRAL = -3
    }
}
