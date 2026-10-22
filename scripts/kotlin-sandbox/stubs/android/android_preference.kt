// Sandbox-only stand-ins, see android_content.kt.
package android.preference

import android.content.Context
import android.content.SharedPreferences

/**
 * Framework (not androidx) PreferenceManager, as referenced by the resolved Java sources and by the
 * converted `Settings` class.
 *
 * [getDefaultSharedPreferences] returns an in-memory [FakeSharedPreferences] rather than an inert
 * value, because the settings checks *execute* `Settings`: preference keys, string sets, typed
 * defaults, the credential transaction's single commit and the audit of which keys were written are
 * observed through it. This is not the platform's `SharedPreferencesImpl`: no file backing, no
 * asynchronous `apply`, no multi-process or type-mismatch behaviour, and the recorded
 * [setDefaultValues] call does not inflate the XML preference resources.
 */
object PreferenceManager {
    private var store: SharedPreferences = FakeSharedPreferences()

    /** Resource ids that [setDefaultValues] was called with, in call order. */
    val defaultValuesCalls: MutableList<Int> = ArrayList()

    @JvmStatic
    fun getDefaultSharedPreferences(context: Context): SharedPreferences = store

    /** Recorded only; no XML resource is read and no default is written. */
    @JvmStatic
    fun setDefaultValues(context: Context, resId: Int, readAgain: Boolean) {
        defaultValuesCalls.add(resId)
    }

    /** Discards the shared store and the recorded calls so a check starts from a clean state. */
    fun reset() {
        store = FakeSharedPreferences()
        defaultValuesCalls.clear()
    }
}

/**
 * Functional in-memory [SharedPreferences] for the executed settings checks: typed values, string
 * sets copied on write and on read (the platform acknowledges mutations in place), a synchronous
 * `apply`, a `commit` that always succeeds, and a listener registry that records registrations
 * without delivering callbacks. Nothing is persisted, cleared on process death or observed by the
 * platform.
 */
class FakeSharedPreferences : SharedPreferences {
    private val values = LinkedHashMap<String?, Any?>()

    /** Registrations recorded by the converted code; no callback is ever delivered. */
    val listeners = ArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    var editCount = 0
    var commitCount = 0
    var applyCount = 0

    /** A copy, non-null-keyed like the interface's return type (the platform rejects null keys). */
    override fun getAll(): Map<String, *> = values.mapKeys { it.key ?: "" }

    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue

    /** Stored sets are copied out, so a caller cannot mutate the stored value in place. */
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? {
        val stored = values[key] as? Set<String> ?: return defValues
        return HashSet(stored)
    }

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor {
        editCount += 1
        return FakeEditor()
    }

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners.add(listener)
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        listeners.remove(listener)
    }

    /** The value as the platform would hand it back: sets are copied, not shared. */
    @Suppress("UNCHECKED_CAST")
    fun stringSet(key: String): Set<String> = HashSet(values[key] as Set<String>)

    inner class FakeEditor : SharedPreferences.Editor {
        private val pending = LinkedHashMap<String?, Any?>()
        private val removals = HashSet<String?>()
        private var cleared = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            if (value == null) removals.add(key) else pending[key] = value
            return this
        }

        override fun putStringSet(key: String?, values: Set<String>?): SharedPreferences.Editor {
            if (values == null) removals.add(key) else pending[key] = HashSet(values)
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = putPending(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = putPending(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = putPending(key, value)

        override fun remove(key: String?): SharedPreferences.Editor {
            removals.add(key)
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            cleared = true
            return this
        }

        override fun commit(): Boolean {
            commitCount += 1
            applyToStore()
            return true
        }

        override fun apply() {
            applyCount += 1
            applyToStore()
        }

        private fun putPending(key: String?, value: Any?): SharedPreferences.Editor {
            if (value == null) removals.add(key) else pending[key] = value
            return this
        }

        private fun applyToStore() {
            if (cleared) values.clear()
            for (key in removals) values.remove(key)
            values.putAll(pending)
        }
    }
}

/**
 * Recording superclass for the preference/dialog control flow only. No PreferenceManager,
 * real persistence, lifecycle, listener delivery or summary rendering is implemented here.
 */
open class DialogPreference(val context: Context, attrs: android.util.AttributeSet?, defStyleAttr: Int) {
    constructor(context: Context, attrs: android.util.AttributeSet?) : this(context, attrs, 0)

    var summary: CharSequence? = null
    open fun callChangeListener(value: Any?): Boolean = true
    protected open fun persistInt(value: Int): Boolean = true
    protected open fun getPersistedInt(defaultValue: Int): Int = defaultValue
    protected open fun persistString(value: String): Boolean = true
    protected open fun getPersistedString(defaultValue: String): String = defaultValue
    open fun setDialogLayoutResource(resource: Int) {}
    open fun getDialog(): android.app.AlertDialog? = null
    protected open fun onCreateDialogView(): android.view.View = android.view.View()
    protected open fun onBindDialogView(view: android.view.View) {}
    protected open fun onDialogClosed(positiveResult: Boolean) {}
    protected open fun onPrepareDialogBuilder(builder: android.app.AlertDialog.Builder) {}
    protected open fun onGetDefaultValue(a: android.content.res.TypedArray, index: Int): Any? = null
    protected open fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {}
}

/**
 * The framework preference base class. The stand-in records the state the converted settings
 * screen sets; no preference screen, persistence or click dispatch is modelled.
 */
open class Preference {
    /** The context the preference belongs to. */
    var context: android.content.Context? = null

    /** The summary that was set. */
    var summary: CharSequence? = null

    /** Whether the preference is enabled. */
    var isEnabled: Boolean = true

    /** The value that was set. */
    var value: String? = null

    /**
     * The change listener that was set, recorded rather than dispatched. The platform exposes
     * `setOnPreferenceChangeListener(...)`; it is modelled as a function here (and not as a
     * property) so the converted code calls it exactly as it would call the platform's method.
     */
    var changeListener: OnPreferenceChangeListener? = null
        private set

    /**
     * The click listener that was set, recorded rather than dispatched. See [changeListener] for
     * why the setter is a function and not a property.
     */
    var clickListener: OnPreferenceClickListener? = null
        private set

    fun setSummary(summaryResId: Int) {
        this.summary = "res:$summaryResId"
    }

    /** Mirrors `Preference.setOnPreferenceChangeListener`. */
    fun setOnPreferenceChangeListener(listener: OnPreferenceChangeListener?) {
        changeListener = listener
    }

    /** Mirrors `Preference.setOnPreferenceClickListener`. */
    fun setOnPreferenceClickListener(listener: OnPreferenceClickListener?) {
        clickListener = listener
    }

    /**
     * Mirrors `Preference.getSharedPreferences()`; the stand-in never persists anything.
     * The platform type is non-null, so the stand-in returns the shared no-op store.
     */
    fun getSharedPreferences(): android.content.SharedPreferences = NoOpSharedPreferences


    /** The change callback of a preference. */
    fun interface OnPreferenceChangeListener {
        fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean
    }

    /** The click callback of a preference. */
    fun interface OnPreferenceClickListener {
        fun onPreferenceClick(preference: Preference): Boolean
    }
}

/** Inert [android.content.SharedPreferences] returned by the preference stand-ins. */
private object NoOpSharedPreferences : android.content.SharedPreferences {
    override fun getAll(): Map<String, *>? = emptyMap<String, Any>()
    override fun getString(key: String?, defValue: String?): String? = defValue
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = defValue
    override fun getLong(key: String?, defValue: Long): Long = defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
    override fun contains(key: String?): Boolean = false
    override fun edit(): android.content.SharedPreferences.Editor = throw UnsupportedOperationException("stand-in")
    override fun registerOnSharedPreferenceChangeListener(
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(
        listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
}

/** A group of preferences. */
open class PreferenceCategory : Preference() {
    /** The preferences that were removed. */
    val removed: MutableList<Preference> = ArrayList()

    fun removePreference(preference: Preference) {
        removed.add(preference)
    }
}

/** A preference that stores one of a list of values. */
open class ListPreference : Preference()

/**
 * A preference with a check box. The platform exposes `isChecked()`/`setChecked(boolean)` as Java
 * methods, which Kotlin sees as the `isChecked` property, so the stand-in models them as one
 * property.
 */
open class CheckBoxPreference : Preference() {
    /** Whether the check box is checked. */
    var isChecked: Boolean = false
}

/**
 * A preference that stores a text. The platform exposes `getText()`/`setText(String)` as Java
 * methods, which Kotlin sees as the `text` property, so the stand-in models them as one property.
 */
open class EditTextPreference : Preference() {
    /** The text that was set. */
    var text: String? = null
}

/**
 * The preference screen. The stand-in records the inflated resources and the looked-up
 * preferences; no preference screen is built.
 */
open class PreferenceFragment : android.app.Fragment() {
    /** The resources that were inflated. */
    val inflatedResources: MutableList<Int> = ArrayList()

    /** The list the platform builds from the preference screen; no rows are laid out here. */
    private val settingsList = android.widget.ListView(android.content.Context())

    /** Mirrors `PreferenceFragment.getListView()`. */
    fun getListView(): android.widget.ListView = settingsList

    /** The preferences that were found, by key. */
    val preferences: MutableMap<String, Preference> = LinkedHashMap()

    fun addPreferencesFromResource(resId: Int) {
        inflatedResources.add(resId)
    }

    fun findPreference(key: String): Preference? = preferences[key]
}
