package com.kitt.audiobridge

import android.content.Context

object AppPreferences {

    const val DEFAULT_PANEL_URL = "http://localhost:48640/storage/emulated/0/KITT/index.html"

    private const val PREFS_NAME = "kitt_prefs"
    private const val KEY_PANEL_URL = "panel_url"
    private const val KEY_AUTOSTART_PANEL = "autostart_panel"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPanelUrl(context: Context): String =
        prefs(context).getString(KEY_PANEL_URL, DEFAULT_PANEL_URL) ?: DEFAULT_PANEL_URL

    fun setPanelUrl(context: Context, url: String) {
        prefs(context).edit().putString(KEY_PANEL_URL, url).apply()
    }

    fun isAutostartPanelEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTOSTART_PANEL, false)

    fun setAutostartPanelEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTOSTART_PANEL, enabled).apply()
    }
}
