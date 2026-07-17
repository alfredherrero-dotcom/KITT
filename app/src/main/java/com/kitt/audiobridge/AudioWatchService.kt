package com.kitt.audiobridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.IOException

/**
 * Which players count as "hablando" for the dashboard mouth animation.
 * Start with ANY while inspecting logcat tag KITTBridge to see what usage
 * Gemini TTS reports on this device, then switch to ASSISTANT_ONLY so
 * e.g. a radio/music app playing in the car doesn't trigger the mouth.
 */
enum class Filter { ANY, ASSISTANT_ONLY }

class AudioWatchService : Service() {

    companion object {
        private const val TAG = "KITTBridge"
        private const val FLAG_FILE_PATH = "/storage/emulated/0/KITT/parlant.txt"
        private val FILTER_MODE = Filter.ANY
        private const val SILENCE_DEBOUNCE_MS = 700L
        private const val NOTIFICATION_CHANNEL_ID = "kitt_audio_bridge_status"
        private const val NOTIFICATION_ID = 1
        private const val EDGE_HANDLE_REQUEST_CODE = 10

        const val ACTION_SYNC_EDGE_HANDLE = "com.kitt.audiobridge.action.SYNC_EDGE_HANDLE"
        const val ACTION_TOGGLE_EDGE_HANDLE = "com.kitt.audiobridge.action.TOGGLE_EDGE_HANDLE"
    }

    private lateinit var audioManager: AudioManager
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var notificationManager: NotificationManager
    private lateinit var edgeHandleOverlay: EdgeHandleOverlay

    private var lastNotificationText = ""

    private var currentState = false
    private var pendingSilenceRunnable: Runnable? = null

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            val activeConfigs = configs.orEmpty()
            for (config in activeConfigs) {
                val attrs = config.audioAttributes
                Log.d(TAG, "usage=${attrs?.usage} contentType=${attrs?.contentType}")
            }

            val active = when (FILTER_MODE) {
                Filter.ANY -> activeConfigs.isNotEmpty()
                Filter.ASSISTANT_ONLY -> activeConfigs.any {
                    it.audioAttributes?.usage == AudioAttributes.USAGE_ASSISTANT
                }
            }

            if (active) {
                pendingSilenceRunnable?.let { handler.removeCallbacks(it) }
                pendingSilenceRunnable = null
                if (!currentState) {
                    currentState = true
                    writeState(true)
                }
            } else if (currentState && pendingSilenceRunnable == null) {
                val runnable = Runnable {
                    currentState = false
                    pendingSilenceRunnable = null
                    writeState(false)
                }
                pendingSilenceRunnable = runnable
                handler.postDelayed(runnable, SILENCE_DEBOUNCE_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        edgeHandleOverlay = EdgeHandleOverlay(this)

        handlerThread = HandlerThread("KITTAudioWatchThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        createNotificationChannel()
        lastNotificationText = getString(R.string.notification_text_silence)
        startForeground(NOTIFICATION_ID, buildNotification(lastNotificationText))

        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)

        syncEdgeHandleVisibility()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_TOGGLE_EDGE_HANDLE -> {
                AppPreferences.setEdgeHandleEnabled(this, !AppPreferences.isEdgeHandleEnabled(this))
                syncEdgeHandleVisibility()
            }
            ACTION_SYNC_EDGE_HANDLE -> syncEdgeHandleVisibility()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        pendingSilenceRunnable?.let { handler.removeCallbacks(it) }
        handlerThread.quitSafely()
        edgeHandleOverlay.hide()
        super.onDestroy()
    }

    private fun syncEdgeHandleVisibility() {
        val shouldShow = AppPreferences.isEdgeHandleEnabled(this) && Settings.canDrawOverlays(this)
        if (shouldShow) edgeHandleOverlay.show() else edgeHandleOverlay.hide()
        notificationManager.notify(NOTIFICATION_ID, buildNotification(lastNotificationText))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun writeState(active: Boolean) {
        try {
            val file = File(FLAG_FILE_PATH)
            file.parentFile?.mkdirs()
            file.writeText(if (active) "1" else "0")
            lastNotificationText = getString(
                if (active) R.string.notification_text_playing else R.string.notification_text_silence
            )
            notificationManager.notify(NOTIFICATION_ID, buildNotification(lastNotificationText))
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write flag file", e)
            lastNotificationText = getString(R.string.notification_text_error, e.message ?: e.toString())
            notificationManager.notify(NOTIFICATION_ID, buildNotification(lastNotificationText))
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to write flag file", e)
            lastNotificationText = getString(R.string.notification_text_error, e.message ?: e.toString())
            notificationManager.notify(NOTIFICATION_ID, buildNotification(lastNotificationText))
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val edgeHandleEnabled = AppPreferences.isEdgeHandleEnabled(this)
        val toggleIntent = Intent(this, AudioWatchService::class.java).apply {
            action = ACTION_TOGGLE_EDGE_HANDLE
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            EDGE_HANDLE_REQUEST_CODE,
            toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleActionLabel = getString(
            if (edgeHandleEnabled) R.string.notification_action_hide_tab else R.string.notification_action_show_tab
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, toggleActionLabel, togglePendingIntent)
            .build()
    }
}
