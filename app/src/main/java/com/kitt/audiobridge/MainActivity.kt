package com.kitt.audiobridge

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.Spinner
import android.widget.Switch
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
        private const val BLUETOOTH_CONNECT_REQUEST_CODE = 101
    }

    private lateinit var textStatusService: TextView
    private lateinit var textStatusFile: TextView
    private lateinit var textStatusState: TextView
    private lateinit var buttonToggleService: Button
    private lateinit var switchEdgeHandle: Switch
    private lateinit var spinnerObdDevice: Spinner
    private lateinit var switchObd: Switch

    private var bondedDevices: List<BluetoothDevice> = emptyList()

    private val edgeHandleCheckedChangeListener =
        CompoundButton.OnCheckedChangeListener { _, isChecked -> onEdgeHandleSwitchChanged(isChecked) }

    private val obdCheckedChangeListener =
        CompoundButton.OnCheckedChangeListener { _, isChecked -> onObdSwitchChanged(isChecked) }

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

        findViewById<Button>(R.id.button_overlay_permission).setOnClickListener {
            openOverlayPermissionSettings()
        }

        switchEdgeHandle = findViewById(R.id.switch_edge_handle)
        switchEdgeHandle.isChecked = AppPreferences.isEdgeHandleEnabled(this) && Settings.canDrawOverlays(this)
        switchEdgeHandle.setOnCheckedChangeListener(edgeHandleCheckedChangeListener)

        spinnerObdDevice = findViewById(R.id.spinner_obd_device)
        findViewById<Button>(R.id.button_save_obd_device).setOnClickListener {
            saveSelectedObdDevice()
        }

        switchObd = findViewById(R.id.switch_obd)
        switchObd.isChecked = AppPreferences.isObdEnabled(this)
        switchObd.setOnCheckedChangeListener(obdCheckedChangeListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPostNotificationsPermission()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBluetoothConnectPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
        refreshBondedDevices()
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

    private fun requestBluetoothConnectPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT),
                BLUETOOTH_CONNECT_REQUEST_CODE
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

    private fun openOverlayPermissionSettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }

    private fun onEdgeHandleSwitchChanged(isChecked: Boolean) {
        if (isChecked && !Settings.canDrawOverlays(this)) {
            switchEdgeHandle.setOnCheckedChangeListener(null)
            switchEdgeHandle.isChecked = false
            switchEdgeHandle.setOnCheckedChangeListener(edgeHandleCheckedChangeListener)
            Toast.makeText(this, R.string.toast_overlay_permission_needed, Toast.LENGTH_LONG).show()
            openOverlayPermissionSettings()
            return
        }

        AppPreferences.setEdgeHandleEnabled(this, isChecked)
        val intent = Intent(this, AudioWatchService::class.java).apply {
            action = AudioWatchService.ACTION_SYNC_EDGE_HANDLE
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun syncEdgeHandleSwitch() {
        val shouldBeChecked = AppPreferences.isEdgeHandleEnabled(this) && Settings.canDrawOverlays(this)
        if (switchEdgeHandle.isChecked != shouldBeChecked) {
            switchEdgeHandle.setOnCheckedChangeListener(null)
            switchEdgeHandle.isChecked = shouldBeChecked
            switchEdgeHandle.setOnCheckedChangeListener(edgeHandleCheckedChangeListener)
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    @SuppressLint("MissingPermission")
    private fun refreshBondedDevices() {
        if (!hasBluetoothConnectPermission()) {
            bondedDevices = emptyList()
            spinnerObdDevice.adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.obd_device_permission_needed))
            )
            return
        }

        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        bondedDevices = devices

        val labels = if (devices.isEmpty()) {
            listOf(getString(R.string.obd_device_none_bonded))
        } else {
            devices.map { "${it.name} (${it.address})" }
        }
        spinnerObdDevice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        val savedAddress = AppPreferences.getObdDeviceAddress(this)
        val savedIndex = devices.indexOfFirst { it.address == savedAddress }
        if (savedIndex >= 0) {
            spinnerObdDevice.setSelection(savedIndex)
        }
    }

    private fun saveSelectedObdDevice() {
        val index = spinnerObdDevice.selectedItemPosition
        val device = bondedDevices.getOrNull(index)
        if (device == null) {
            Toast.makeText(this, R.string.toast_obd_no_device_selected, Toast.LENGTH_SHORT).show()
            return
        }
        AppPreferences.setObdDeviceAddress(this, device.address)
        Toast.makeText(this, R.string.toast_obd_device_saved, Toast.LENGTH_SHORT).show()

        val intent = Intent(this, AudioWatchService::class.java).apply {
            action = AudioWatchService.ACTION_SYNC_OBD
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun onObdSwitchChanged(isChecked: Boolean) {
        if (isChecked && AppPreferences.getObdDeviceAddress(this).isNullOrEmpty()) {
            switchObd.setOnCheckedChangeListener(null)
            switchObd.isChecked = false
            switchObd.setOnCheckedChangeListener(obdCheckedChangeListener)
            Toast.makeText(this, R.string.toast_obd_no_device_selected, Toast.LENGTH_SHORT).show()
            return
        }

        AppPreferences.setObdEnabled(this, isChecked)
        val intent = Intent(this, AudioWatchService::class.java).apply {
            action = AudioWatchService.ACTION_SYNC_OBD
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun syncObdSwitch() {
        val shouldBeChecked = AppPreferences.isObdEnabled(this)
        if (switchObd.isChecked != shouldBeChecked) {
            switchObd.setOnCheckedChangeListener(null)
            switchObd.isChecked = shouldBeChecked
            switchObd.setOnCheckedChangeListener(obdCheckedChangeListener)
        }
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

        syncEdgeHandleSwitch()
        syncObdSwitch()
    }
}
