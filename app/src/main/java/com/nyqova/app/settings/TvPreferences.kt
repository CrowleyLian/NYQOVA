package com.nyqova.app.settings

import android.content.Context

class TvPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("tv_preferences", Context.MODE_PRIVATE)
    fun enabled(key: String, default: Boolean = true) = prefs.getBoolean(key, default)
    fun set(key: String, value: Boolean) { prefs.edit().putBoolean(key, value).apply() }
    fun text(key: String, default: String = "") = prefs.getString(key, default) ?: default
    fun set(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    fun number(key: String, default: Int = 10) = prefs.getInt(key, default)
    fun set(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
}
