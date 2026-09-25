package com.example.a180d

import android.content.Context

data class ZoneSettings(val age: Int, val restingHr: Int)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Persists the age/resting-HR inputs the Karvonen zone calculation needs, and the display theme choice. Never guessed or hardcoded. */
class UserSettings(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ZoneSettings? {
        val age = prefs.getInt(KEY_AGE, -1)
        val restingHr = prefs.getInt(KEY_RESTING_HR, -1)
        return if (age > 0 && restingHr > 0) ZoneSettings(age, restingHr) else null
    }

    fun save(settings: ZoneSettings) {
        prefs.edit()
            .putInt(KEY_AGE, settings.age)
            .putInt(KEY_RESTING_HR, settings.restingHr)
            .apply()
    }

    fun loadThemeMode(): ThemeMode =
        prefs.getString(KEY_THEME_MODE, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM

    fun saveThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    fun loadKeepScreenOn(): Boolean = prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun saveKeepScreenOn(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }

    /** The address of the device the user last picked, so repeat sessions can skip the picker. */
    fun loadRememberedDeviceAddress(): String? = prefs.getString(KEY_REMEMBERED_DEVICE_ADDRESS, null)

    fun saveRememberedDeviceAddress(address: String?) {
        prefs.edit().putString(KEY_REMEMBERED_DEVICE_ADDRESS, address).apply()
    }

    /** The floating BPM overlay is opt-in and off by default — it draws over other apps. */
    fun loadBubbleEnabled(): Boolean = prefs.getBoolean(KEY_BUBBLE_ENABLED, false)

    fun saveBubbleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply()
    }

    /** Which edge the bubble snapped to last. Default right: least likely to cover back-navigation. */
    fun loadBubbleOnLeftEdge(): Boolean = prefs.getBoolean(KEY_BUBBLE_ON_LEFT_EDGE, false)

    /** Vertical position as a fraction of the usable height, so it survives a rotation or a different display. */
    fun loadBubbleYFraction(): Float = prefs.getFloat(KEY_BUBBLE_Y_FRACTION, DEFAULT_BUBBLE_Y_FRACTION)

    fun saveBubblePosition(onLeftEdge: Boolean, yFraction: Float) {
        prefs.edit()
            .putBoolean(KEY_BUBBLE_ON_LEFT_EDGE, onLeftEdge)
            .putFloat(KEY_BUBBLE_Y_FRACTION, yFraction)
            .apply()
    }

    companion object {
        /** Clear of the status bar, clear of the bottom nav zone. */
        const val DEFAULT_BUBBLE_Y_FRACTION = 0.35f

        private const val PREFS_NAME = "user_settings"
        private const val KEY_AGE = "age"
        private const val KEY_RESTING_HR = "resting_hr"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_KEEP_SCREEN_ON = "keep_screen_on"
        private const val KEY_REMEMBERED_DEVICE_ADDRESS = "remembered_device_address"
        private const val KEY_BUBBLE_ENABLED = "bubble_enabled"
        private const val KEY_BUBBLE_ON_LEFT_EDGE = "bubble_on_left_edge"
        private const val KEY_BUBBLE_Y_FRACTION = "bubble_y_fraction"
    }
}
