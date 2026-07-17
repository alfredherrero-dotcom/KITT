package com.kitt.audiobridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
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
    }

    private lateinit var audioManager: AudioManager
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var notificationManager: NotificationManager

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

        handlerThread = HandlerThread("KITTAudioWatchThread")
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_text_silence)))

        audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        pendingSilenceRunnable?.let { handler.removeCallbacks(it) }
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun writeState(active: Boolean) {
        try {
            val file = File(FLAG_FILE_PATH)
            file.parentFile?.mkdirs()
            file.writeText(if (active) "1" else "0")
            val text = getString(
                if (active) R.string.notification_text_playing else R.string.notification_text_silence
            )
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write flag file", e)
            val text = getString(R.string.notification_text_error, e.message ?: e.toString())
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to write flag file", e)
            val text = getString(R.string.notification_text_error, e.message ?: e.toString())
            notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
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
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }
}
