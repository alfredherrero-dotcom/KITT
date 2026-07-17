package com.kitt.audiobridge

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    companion object {
        private const val FLAG_FILE_PATH = "/storage/emulated/0/KITT/parlant.txt"
        private const val REFRESH_INTERVAL_MS = 1000L
        private const val POST_NOTIFICATIONS_REQUEST_CODE = 100
    }

    private lateinit var textStatusService: TextView
    private lateinit var textStatusFile: TextView
    private lateinit var textStatusState: TextView
    private lateinit var buttonToggleService: Button

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            updateStatus()
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        textStatusService = findViewById(R.id.text_status_service)
        textStatusFile = findViewById(R.id.text_status_file)
        textStatusState = findViewById(R.id.text_status_state)
        buttonToggleService = findViewById(R.id.button_toggle_service)

        findViewById<Button>(R.id.button_files_permission).setOnClickListener {
            openAllFilesAccessSettings()
        }
        findViewById<Button>(R.id.button_battery_optimization).setOnClickListener {
            requestIgnoreBatteryOptimizations()
        }
        buttonToggleService.setOnClickListener {
            if (isServiceRunning()) stopAudioWatchService() else startAudioWatchService()
            updateStatus()
        }

        val editPanelUrl = findViewById<EditText>(R.id.edit_panel_url)
        editPanelUrl.setText(AppPreferences.getPanelUrl(this))
        findViewById<Button>(R.id.button_save_url).setOnClickListener {
            val url = editPanelUrl.text.toString().trim()
            if (url.isNotEmpty()) {
                AppPreferences.setPanelUrl(this, url)
                Toast.makeText(this, R.string.toast_url_saved, Toast.LENGTH_SHORT).show()
            }
        }

        val checkboxAutostartPanel = findViewById<CheckBox>(R.id.checkbox_autostart_panel)
        checkboxAutostartPanel.isChecked = AppPreferences.isAutostartPanelEnabled(this)
        checkboxAutostartPanel.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.setAutostartPanelEnabled(this, isChecked)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPostNotificationsPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun requestPostNotificationsPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                POST_NOTIFICATIONS_REQUEST_CODE
            )
        }
    }

    private fun openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
        }
        startActivity(intent)
    }

    private fun startAudioWatchService() {
        val intent = Intent(this, AudioWatchService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopAudioWatchService() {
        stopService(Intent(this, AudioWatchService::class.java))
    }

    private fun isServiceRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        @Suppress("DEPRECATION")
        return activityManager.getRunningServices(Integer.MAX_VALUE)
            .any { it.service.className == AudioWatchService::class.java.name }
    }

    private fun isFileWritable(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val parent = File(FLAG_FILE_PATH).parentFile
            parent?.mkdirs()
            parent?.canWrite() == true
        }
    }

    private fun lastKnownState(): String? {
        val file = File(FLAG_FILE_PATH)
        if (!file.exists()) return null
        return runCatching { file.readText().trim() }.getOrNull()
    }

    private fun updateStatus() {
        val running = isServiceRunning()
        textStatusService.text = getString(
            if (running) R.string.status_service_running else R.string.status_service_stopped
        )
        buttonToggleService.text = getString(
            if (running) R.string.button_stop_service else R.string.button_start_service
        )

        textStatusFile.text = getString(
            if (isFileWritable()) R.string.status_file_writable else R.string.status_file_not_writable
        )

        textStatusState.text = when (lastKnownState()) {
            "1" -> getString(R.string.status_last_state_playing)
            "0" -> getString(R.string.status_last_state_silence)
            else -> getString(R.string.status_last_state_unknown)
        }
    }
}
