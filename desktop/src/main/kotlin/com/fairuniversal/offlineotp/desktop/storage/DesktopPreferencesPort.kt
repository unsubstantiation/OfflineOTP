package com.fairuniversal.offlineotp.desktop.storage

import com.fairuniversal.offlineotp.Utilities.KeyValueSettings
import com.fairuniversal.offlineotp.Utilities.PreferencesExport

/**
 * Adapts the desktop vault's [KeyValueSettings] to [PreferencesExport.SettingsPort].
 *
 * The desktop store is deliberately thin — strings and ints, no booleans, no string sets — so the
 * mapping is explicit rather than generic: a boolean becomes `"true"`/`"false"` and a set becomes
 * newline-joined text. Both encodings are reversible here and neither is visible to another client,
 * because this adapter is only ever used to move settings between two desktop installs.
 *
 * There is no backup password on this side: the desktop re-prompts for a backup passphrase on every
 * export and never stores one, so [readBackupPassword] is always null and a preferences file that
 * carries one is simply not written by this client.
 */
class DesktopPreferencesPort(private val settings: KeyValueSettings) : PreferencesExport.SettingsPort {

    override fun storedKeys(): Set<String> = settings.keys()

    override fun read(key: String): PreferencesExport.Value? {
        val catalogued = PreferencesExport.setting(key, PreferencesExport.PLATFORM_DESKTOP) ?: return null
        val raw = settings.getString(key, "")
        // An empty stored value is how this store spells "absent": a properties file has no types,
        // so an unset key and an empty one are the same thing here.
        if (raw.isEmpty()) return null
        return when (catalogued.kind) {
            PreferencesExport.Kind.BOOLEAN -> raw.toBooleanStrictOrNull()?.let { PreferencesExport.Value.Bool(it) }
            PreferencesExport.Kind.INTEGER -> raw.toIntOrNull()?.let { PreferencesExport.Value.IntValue(it) }
            PreferencesExport.Kind.STRING -> PreferencesExport.Value.Text(raw)
            PreferencesExport.Kind.STRING_SET ->
                PreferencesExport.Value.TextSet(raw.split('\n').filter { it.isNotEmpty() })
            // Secrets are not stored by this client; the catalog's only SECRET is the backup password.
            PreferencesExport.Kind.SECRET, PreferencesExport.Kind.DEVICE_BOUND -> null
        }
    }

    /** The desktop never stores the backup password, so there is never one to move. */
    override fun readBackupPassword(): String? = null

    override fun write(key: String, value: PreferencesExport.Value) {
        when (val kind = PreferencesExport.setting(key, PreferencesExport.PLATFORM_DESKTOP)?.kind) {
            PreferencesExport.Kind.BOOLEAN -> {
                val typed = value as? PreferencesExport.Value.Bool
                    ?: throw IllegalArgumentException("$key is not a boolean")
                settings.putString(key, typed.value.toString())
            }
            PreferencesExport.Kind.INTEGER -> {
                val typed = value as? PreferencesExport.Value.IntValue
                    ?: throw IllegalArgumentException("$key is not an integer")
                settings.putInt(key, typed.value)
            }
            PreferencesExport.Kind.STRING -> {
                val typed = value as? PreferencesExport.Value.Text
                    ?: throw IllegalArgumentException("$key is not text")
                settings.putString(key, typed.value)
            }
            PreferencesExport.Kind.STRING_SET -> {
                val typed = value as? PreferencesExport.Value.TextSet
                    ?: throw IllegalArgumentException("$key is not a set")
                settings.putString(key, typed.values.joinToString("\n"))
            }
            PreferencesExport.Kind.SECRET ->
                throw IllegalArgumentException("this client cannot store $key")
            PreferencesExport.Kind.DEVICE_BOUND -> throw IllegalArgumentException("$key never leaves this device")
            null -> throw IllegalArgumentException("$key has no portable meaning")
        }
    }
}
