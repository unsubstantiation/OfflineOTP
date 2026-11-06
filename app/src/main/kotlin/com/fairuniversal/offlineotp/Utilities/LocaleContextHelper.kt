package com.fairuniversal.offlineotp.Utilities

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/** Builds locale-specific activity contexts without mutating shared Resources. */
object LocaleContextHelper {
    /** Returns a context whose resources resolve strings and layouts for [locale]. */
    @JvmStatic
    fun createLocalizedContext(context: Context, locale: Locale): Context {
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }

    /** Whether this context already resolves resources using [locale]. */
    @JvmStatic
    fun isLocaleApplied(context: Context, locale: Locale): Boolean {
        val locales = context.resources.configuration.locales
        return locales.size() > 0 && locales[0] == locale
    }
}
