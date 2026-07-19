package com.kitt.audiobridge

import android.content.Context

object AppPreferences {

    const val DEFAULT_PANEL_URL = "http://192.168.1.106:48640/"

    private const val PREFS_NAME = "kitt_prefs"
    private const val KEY_PANEL_URL = "panel_url"
    private const val KEY_AUTOSTART_PANEL = "autostart_panel"
    private const val KEY_EDGE_HANDLE_ENABLED = "edge_handle_enabled"
    private const val KEY_EDGE_HANDLE_DOCKED_RIGHT = "edge_handle_docked_right"
    private const val KEY_EDGE_HANDLE_Y = "edge_handle_y"
    private const val KEY_OBD_DEVICE_ADDRESS = "obd_device_address"
    private const val KEY_OBD_ENABLED = "obd_enabled"
    private const val KEY_OBD_KM_TOTAL = "obd_km_total"

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

    fun isEdgeHandleEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EDGE_HANDLE_ENABLED, false)

    fun setEdgeHandleEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_EDGE_HANDLE_ENABLED, enabled).apply()
    }

    fun isEdgeHandleDockedRight(context: Context): Boolean =
        prefs(context).getBoolean(KEY_EDGE_HANDLE_DOCKED_RIGHT, true)

    fun setEdgeHandleDockedRight(context: Context, dockedRight: Boolean) {
        prefs(context).edit().putBoolean(KEY_EDGE_HANDLE_DOCKED_RIGHT, dockedRight).apply()
    }

    /** Returns the persisted Y offset in px, or -1 if never positioned. */
    fun getEdgeHandleY(context: Context): Int =
        prefs(context).getInt(KEY_EDGE_HANDLE_Y, -1)

    fun setEdgeHandleY(context: Context, y: Int) {
        prefs(context).edit().putInt(KEY_EDGE_HANDLE_Y, y).apply()
    }

    fun getObdDeviceAddress(context: Context): String? =
        prefs(context).getString(KEY_OBD_DEVICE_ADDRESS, null)

    fun setObdDeviceAddress(context: Context, address: String) {
        prefs(context).edit().putString(KEY_OBD_DEVICE_ADDRESS, address).apply()
    }

    fun isObdEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_OBD_ENABLED, false)

    fun setObdEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_OBD_ENABLED, enabled).apply()
    }

    fun getObdKmTotal(context: Context): Float =
        prefs(context).getFloat(KEY_OBD_KM_TOTAL, 0f)

    fun setObdKmTotal(context: Context, km: Float) {
        prefs(context).edit().putFloat(KEY_OBD_KM_TOTAL, km).apply()
    }
}
