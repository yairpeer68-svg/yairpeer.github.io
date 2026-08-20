package com.ghosteye.intelligence

import android.content.Context

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("ghost-eye-ui", Context.MODE_PRIVATE)

    var darkMode: Boolean
        get() = prefs.getBoolean("dark_mode", true)
        set(value) { prefs.edit().putBoolean("dark_mode", value).apply() }
}
