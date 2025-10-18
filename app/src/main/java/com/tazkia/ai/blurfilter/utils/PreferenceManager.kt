package com.tazkia.ai.blurfilter.utils

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val PREFS_NAME = "tazkia_prefs"

        // Keys
        private const val KEY_MODE = "mode"
        private const val KEY_FILTER_TARGET = "filter_target"
        private const val KEY_BLUR_INTENSITY = "blur_intensity"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_USE_GPU = "use_gpu"
        private const val KEY_ADAPTIVE_FPS = "adaptive_fps"
        private const val KEY_RESOLUTION = "resolution"
        private const val KEY_IS_RUNNING = "is_running"

        // Default values
        const val MODE_HYBRID = 0
        const val MODE_MEDIA_PROJECTION_ONLY = 1

        const val FILTER_WOMEN = 0
        const val FILTER_MEN = 1

        const val RESOLUTION_LOW = 240
        const val RESOLUTION_MEDIUM = 320
        const val RESOLUTION_HIGH = 480

        const val LANG_ENGLISH = "en"
        const val LANG_ARABIC = "ar"
    }

    // Mode
    var detectionMode: Int
        get() = prefs.getInt(KEY_MODE, MODE_HYBRID)
        set(value) = prefs.edit().putInt(KEY_MODE, value.coerceIn(MODE_HYBRID, MODE_MEDIA_PROJECTION_ONLY)).apply()
    // Filter target
    var filterTarget: Int
        get() = prefs.getInt(KEY_FILTER_TARGET, FILTER_WOMEN)
        set(value) = prefs.edit().putInt(KEY_FILTER_TARGET, value.coerceIn(FILTER_WOMEN, FILTER_MEN)).apply()
    // Blur intensity (1-10)
    var blurIntensity: Int
        get() = prefs.getInt(KEY_BLUR_INTENSITY, 5)
        set(value) = prefs.edit().putInt(KEY_BLUR_INTENSITY, value.coerceIn(1, 10)).apply()

    // Language
    var language: String
        get() = prefs.getString(KEY_LANGUAGE, LANG_ENGLISH) ?: LANG_ENGLISH
        set(value) = prefs.edit().putString(KEY_LANGUAGE, value).apply()

    // GPU acceleration
    var useGpu: Boolean
        get() = prefs.getBoolean(KEY_USE_GPU, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_GPU, value).apply()

    // Adaptive FPS
    var adaptiveFps: Boolean
        get() = prefs.getBoolean(KEY_ADAPTIVE_FPS, true)
        set(value) = prefs.edit().putBoolean(KEY_ADAPTIVE_FPS, value).apply()

    // Resolution
    var processingResolution: Int
        get() = prefs.getInt(KEY_RESOLUTION, RESOLUTION_MEDIUM)
        set(value) = prefs.edit().putInt(KEY_RESOLUTION, value).apply()

    // Running state
    var isProtectionRunning: Boolean
        get() = prefs.getBoolean(KEY_IS_RUNNING, false)
        set(value) = prefs.edit().putBoolean(KEY_IS_RUNNING, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }
}