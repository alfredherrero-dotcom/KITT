package com.kitt.audiobridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val serviceIntent = Intent(context, AudioWatchService::class.java)
            ContextCompat.startForegroundService(context, serviceIntent)

            if (AppPreferences.isAutostartPanelEnabled(context)) {
                // Starting an activity from a BroadcastReceiver is subject to Android
                // 10+'s background-activity-launch restrictions. The app holds
                // SYSTEM_ALERT_WINDOW, which is one of the documented exemptions,
                // so this launch is allowed to proceed without user interaction.
                val panelIntent = Intent(context, PanelActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(panelIntent)
            }
        }
    }
}
