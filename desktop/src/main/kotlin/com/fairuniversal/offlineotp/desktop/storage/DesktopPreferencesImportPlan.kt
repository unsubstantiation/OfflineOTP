package com.fairuniversal.offlineotp.desktop.storage

import com.fairuniversal.offlineotp.Utilities.KeyValueSettings
import com.fairuniversal.offlineotp.Utilities.PreferencesExport

/** Validated allowlisted settings, not raw file bytes. Preparation never touches a live store. */
class DesktopPreferencesImportPlan private constructor(
    private val values: Map<String, String>,
    val applied: Int, val unknownCount: Int, val refusedCount: Int
) {
    fun contains(key: String): Boolean = values.containsKey(key)

    /** Merge only mentioned keys with one atomic-file write, preserving current auth/unmentioned keys. */
    fun applyTo(settings: KeyValueSettings) {
        require(settings is DesktopSettings) { "Atomic desktop settings store required" }
        settings.putAll(values)
    }
    override fun toString() = "DesktopPreferencesImportPlan($applied settings, unknown=$unknownCount, refused=$refusedCount)"

    companion object {
        fun prepare(bytes: ByteArray): DesktopPreferencesImportPlan {
            val staged = LinkedHashMap<String, String>()
            val memory = object : KeyValueSettings {
                override fun getString(key: String, default: String) = staged[key] ?: default
                override fun getInt(key: String, default: Int) = staged[key]?.toIntOrNull() ?: default
                override fun putString(key: String, value: String) { staged[key] = value }
                override fun putInt(key: String, value: Int) { staged[key] = value.toString() }
                override fun remove(key: String) { staged.remove(key) }
                override fun keys(): Set<String> = staged.keys.toSet()
            }
            val result = PreferencesExport.import(bytes, DesktopPreferencesPort(memory), PreferencesExport.PLATFORM_DESKTOP)
            return DesktopPreferencesImportPlan(staged.toMap(), result.applied, result.unknownKeys.size, result.refusedKeys.size)
        }
    }
}
