package com.example.a180d

import android.content.Context

data class ZoneSettings(val age: Int, val restingHr: Int)

/** Persists the age/resting-HR inputs the Karvonen zone calculation needs. Never guessed or hardcoded. */
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

    companion object {
        private const val PREFS_NAME = "user_settings"
        private const val KEY_AGE = "age"
        private const val KEY_RESTING_HR = "resting_hr"
    }
}
