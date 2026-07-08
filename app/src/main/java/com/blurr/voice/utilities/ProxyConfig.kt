package com.blurr.voice.utilities

import android.content.Context

object ProxyConfig {
    private const val PREFS_NAME = "BlurrSettings"
    private const val KEY_PROXY_URL = "proxy_url"
    private const val KEY_PROXY_KEY = "proxy_key"

    fun saveUrl(context: Context, url: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROXY_URL, url).apply()
    }

    fun saveKey(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_PROXY_KEY, key).apply()
    }

    fun getUrl(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROXY_URL, "") ?: ""
    }

    fun getKey(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PROXY_KEY, "") ?: ""
    }
}
