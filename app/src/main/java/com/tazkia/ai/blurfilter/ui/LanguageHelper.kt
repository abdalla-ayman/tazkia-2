package com.tazkia.ai.blurfilter.ui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object LanguageHelper {

    /**
     * Set app language
     */
    fun setLanguage(context: Context, languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)

        val resources = context.resources
        val configuration = Configuration(resources.configuration)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale)
            context.createConfigurationContext(configuration)
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
        }

        @Suppress("DEPRECATION")
        resources.updateConfiguration(configuration, resources.displayMetrics)

        // Set layout direction for RTL support
        if (languageCode == "ar") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                configuration.setLayoutDirection(locale)
            }
        }
    }

    /**
     * Apply language and recreate activity
     */
    fun applyLanguage(activity: Activity, languageCode: String) {
        setLanguage(activity, languageCode)
        activity.recreate()
    }

    /**
     * Get current language
     */
    fun getCurrentLanguage(context: Context): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0].language
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale.language
        }
    }

    /**
     * Check if current language is RTL
     */
    fun isRTL(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            context.resources.configuration.layoutDirection == Configuration.SCREENLAYOUT_LAYOUTDIR_RTL
        } else {
            false
        }
    }
}