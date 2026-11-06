package com.pisotiyempo.ui

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The app's screen language, English or Filipino, chosen in Settings independently of the phone's language.
 * Stored in plain SharedPreferences because it must be read synchronously before the first screen draws.
 * Filipino strings live in res/values-tl (Android's resource code for Filipino/Tagalog).
 */
object UiLanguage {
    const val ENGLISH = "en"
    const val FILIPINO = "fil"
    private const val PREFS = "ui"
    private const val KEY = "language"

    fun get(context: Context): String {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (saved != null) return saved
        val device = Locale.getDefault().language
        return if (device == "fil" || device == "tl") FILIPINO else ENGLISH
    }

    fun set(context: Context, language: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language).apply()
    }

    /** A context whose resources use the chosen language. Formatting of numbers and dates stays explicit (Locale.US). */
    fun wrap(base: Context): Context {
        val locale = if (get(base) == FILIPINO) Locale("tl", "PH") else Locale("en", "PH")
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
