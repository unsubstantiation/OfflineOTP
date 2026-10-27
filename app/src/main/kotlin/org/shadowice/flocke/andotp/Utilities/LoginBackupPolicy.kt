package org.shadowice.flocke.andotp.Utilities

import android.content.Context
import android.preference.PreferenceManager
import org.shadowice.flocke.andotp.R

/** Chooses the login-derived key without changing an existing separate-password configuration. */
internal object LoginBackupPolicy {
    fun usesLoginPassword(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val key = context.getString(R.string.settings_key_auto_backup_use_login)
        if (prefs.contains(key)) return prefs.getBoolean(key, false)
        // Existing installations that configured a separate backup password keep using it until
        // they explicitly opt in. Fresh installations default to the app-password path.
        val legacy = prefs.getString(context.getString(R.string.settings_key_backup_password_enc), "")
        val oldPlain = prefs.getString(context.getString(R.string.settings_key_backup_password), "")
        return legacy.isNullOrEmpty() && oldPlain.isNullOrEmpty()
    }
}
