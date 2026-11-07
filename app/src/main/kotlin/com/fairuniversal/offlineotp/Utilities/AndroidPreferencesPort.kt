package com.fairuniversal.offlineotp.Utilities

import android.content.Context
import android.preference.PreferenceManager

import java.util.Collections

/**
 * Adapts the app's `SharedPreferences` to [PreferencesExport.SettingsPort].
 *
 * The port is deliberately narrow, and this class is where the platform's own knowledge stays:
 * a value is read in the type the catalog declares, never "whatever was stored". A key whose stored
 * value is of another type reads as absent instead of being coerced, because the export must not
 * invent a setting that the app itself would not read back the same way.
 *
 * The backup password is the one value that is not plain preference text — it is wrapped by the
 * device's Keystore — so it goes through [Settings.getBackupPasswordEnc]/`setBackupPassword`
 * rather than the generic path. That is also why it is the only secret the export can carry.
 */
class AndroidPreferencesPort(
    context: Context,
    private val settings: Settings = Settings(context)
) : PreferencesExport.SettingsPort {

    /** The same store [android.preference.PreferenceManager] hands to [Settings]. */
    private val store = PreferenceManager.getDefaultSharedPreferences(context)

    override fun storedKeys(): Set<String> = all().keys.mapNotNull { it }.toSet()

    /**
     * The whole store. The platform's `getAll()` is non-null; the sandbox stub types it nullable, so
     * the null case is absorbed once here rather than at each call site.
     */
    private fun all(): Map<String, *> = store.getAll() ?: emptyMap<String, Any>()

    override fun read(key: String): PreferencesExport.Value? {
        if (!store.contains(key)) return null
        val raw = all()[key] ?: return null
        return when (PreferencesExport.setting(key)?.kind) {
            PreferencesExport.Kind.BOOLEAN -> (raw as? Boolean)?.let { PreferencesExport.Value.Bool(it) }
            PreferencesExport.Kind.INTEGER -> (raw as? Int)?.let { PreferencesExport.Value.IntValue(it) }
            PreferencesExport.Kind.STRING -> (raw as? String)?.let { PreferencesExport.Value.Text(it) }
            PreferencesExport.Kind.STRING_SET -> (raw as? Set<*>)
                ?.mapNotNull { it as? String }
                ?.takeIf { it.size == (raw as Set<*>).size }
                ?.let { PreferencesExport.Value.TextSet(it.sorted()) }
            else -> null
        }
    }

    override fun readBackupPassword(): String? = settings.getBackupPasswordEnc().ifEmpty { null }

    override fun write(key: String, value: PreferencesExport.Value) {
        val editor = store.edit()
        when (val kind = PreferencesExport.setting(key)?.kind) {
            PreferencesExport.Kind.BOOLEAN -> {
                val typed = value as? PreferencesExport.Value.Bool
                    ?: throw IllegalArgumentException("$key is not a boolean")
                editor.putBoolean(key, typed.value)
            }
            PreferencesExport.Kind.INTEGER -> {
                val typed = value as? PreferencesExport.Value.IntValue
                    ?: throw IllegalArgumentException("$key is not an integer")
                editor.putInt(key, typed.value)
            }
            PreferencesExport.Kind.STRING, PreferencesExport.Kind.SECRET -> {
                val typed = value as? PreferencesExport.Value.Text
                    ?: throw IllegalArgumentException("$key is not text")
                // The backup password keeps its Keystore wrapping; it never lands in the prefs as
                // plaintext, which is why it is written through Settings rather than the editor.
                if (kind == PreferencesExport.Kind.SECRET) {
                    if (!settings.setBackupPassword(typed.value)) {
                        throw IllegalStateException("The backup password could not be re-encrypted")
                    }
                    return
                }
                editor.putString(key, typed.value)
            }
            PreferencesExport.Kind.STRING_SET -> {
                val typed = value as? PreferencesExport.Value.TextSet
                    ?: throw IllegalArgumentException("$key is not a set")
                editor.putStringSet(key, Collections.unmodifiableSet(LinkedHashSet(typed.values)))
            }
            PreferencesExport.Kind.DEVICE_BOUND -> throw IllegalArgumentException("$key never leaves this device")
            null -> throw IllegalArgumentException("$key has no portable meaning")
        }
        editor.apply()
    }
}
