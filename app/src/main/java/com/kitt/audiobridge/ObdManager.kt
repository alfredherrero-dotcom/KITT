package com.kitt.audiobridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth classic (SPP) connection to an ELM327 OBD-II adapter. Owned and
 * started/stopped by AudioWatchService. Polls standard mode-01 PIDs and
 * writes /storage/emulated/0/KITT/telemetria.json for the web dashboard.
 */
class ObdManager(private val service: Context) {

    companion object {
        private const val TAG = "KITTObd"
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val TELEMETRY_FILE_PATH = "/storage/emulated/0/KITT/telemetria.json"

        private const val COMMAND_TIMEOUT_MS = 1500L
        private const val FAST_POLL_INTERVAL_MS = 500L
        private const val SLOW_POLL_INTERVAL_MS = 5000L
        private const val JSON_WRITE_INTERVAL_MS = 1000L
        private const val KM_PERSIST_INTERVAL_MS = 30000L
        private const val RECONNECT_BACKOFF_MIN_MS = 2000L
        private const val RECONNECT_BACKOFF_MAX_MS = 30000L
        private const val NO_ADDRESS_RETRY_MS = 2000L
        private const val IGNITION_OFF_NO_DATA_THRESHOLD = 3
        private const val KEEP_ALIVE_POLL_INTERVAL_MS = 10000L
        private const val KEEP_ALIVE_TICK_MS = 200L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @Volatile private var connected = false
    private var dpfSupported = true
    private var consecutiveNoDataRpm = 0

    private var lastSpeedSampleTime = 0L
    private var kmTrip = 0f
    private var kmTotal = 0f
    private var lastKmPersistTime = 0L
    private var lastJsonWriteTime = 0L

    private var speedKmh = 0
    private var rpm = 0
    private var coolantC: Int? = null
    private var oilC: Int? = null
    private var voltage: Float? = null
    private var boostBar: Float? = null
    private var loadPct: Int? = null
    private var dpfTempC: Int? = null

    fun start() {
        if (job?.isActive == true) return
        kmTotal = AppPreferences.getObdKmTotal(service)
        kmTrip = 0f
        job = scope.launch { runLoop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        connected = false
        closeSocket()
        persistKmTotal()
        writeTelemetry()
    }

    fun isRunning(): Boolean = job?.isActive == true

    private suspend fun CoroutineScope.runLoop() {
        var backoffMs = RECONNECT_BACKOFF_MIN_MS
        while (isActive) {
            val address = AppPreferences.getObdDeviceAddress(service)
            if (address.isNullOrEmpty() || !hasBluetoothPermission()) {
                connected = false
                writeTelemetryIfDue(force = true)
                delay(NO_ADDRESS_RETRY_MS)
                continue
            }

            val initOk = try {
                connectAndInit(address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "OBD connect failed", e)
                false
            }

            if (!initOk) {
                closeSocket()
                connected = false
                writeTelemetryIfDue(force = true)
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(RECONNECT_BACKOFF_MAX_MS)
                continue
            }

            backoffMs = RECONNECT_BACKOFF_MIN_MS
            connected = true
            dpfSupported = true
            consecutiveNoDataRpm = 0
            lastSpeedSampleTime = SystemClock.elapsedRealtime()
            writeTelemetryIfDue(force = true)

            try {
                pollUntilDisconnected()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "OBD connection lost", e)
            }

            connected = false
            closeSocket()
            persistKmTotal()
            writeTelemetryIfDue(force = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectAndInit(address: String): Boolean {
        val manager = service.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter ?: return false
        if (!adapter.isEnabled) return false

        val device = adapter.getRemoteDevice(address)
        adapter.cancelDiscovery()

        val newSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
        newSocket.connect()

        socket = newSocket
        input = newSocket.inputStream
        output = newSocket.outputStream

        sendCommand("ATZ")
        sendCommand("ATE0")
        sendCommand("ATL0")
        sendCommand("ATS0")
        sendCommand("ATH0")
        sendCommand("ATSP0")
        val handshake = sendCommand("0100")
        return handshake != null
    }

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(service, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private suspend fun pollUntilDisconnected() {
        var lastSlowPoll = 0L
        var lastKeepAlive = 0L
        var ignitionOff = false

        while (true) {
            val now = SystemClock.elapsedRealtime()

            if (ignitionOff) {
                if (now - lastKeepAlive >= KEEP_ALIVE_POLL_INTERVAL_MS) {
                    lastKeepAlive = now
                    val rpmRaw = sendCommand("010C") ?: throw IOException("OBD keep-alive timed out")
                    val reading = parsePid("010C", rpmRaw)
                    if (reading != null && reading.size >= 2) {
                        rpm = ((reading[0] * 256) + reading[1]) / 4
                        consecutiveNoDataRpm = 0
                        ignitionOff = false
                        lastSpeedSampleTime = SystemClock.elapsedRealtime()
                    }
                }
                writeTelemetryIfDue()
                persistKmIfDue()
                delay(KEEP_ALIVE_TICK_MS)
                continue
            }

            pollSpeedAndRpm()

            if (consecutiveNoDataRpm >= IGNITION_OFF_NO_DATA_THRESHOLD) {
                ignitionOff = true
                speedKmh = 0
                rpm = 0
            }

            if (now - lastSlowPoll >= SLOW_POLL_INTERVAL_MS) {
                lastSlowPoll = now
                pollSlowGroup()
            }

            writeTelemetryIfDue()
            persistKmIfDue()

            delay(FAST_POLL_INTERVAL_MS)
        }
    }

    private fun pollSpeedAndRpm() {
        val speedReading = parsePid("010D", sendCommand("010D"))
        if (speedReading != null && speedReading.isNotEmpty()) {
            val newSpeed = speedReading[0]
            integrateKm(newSpeed)
            speedKmh = newSpeed
        }

        val rpmReading = parsePid("010C", sendCommand("010C"))
        if (rpmReading != null && rpmReading.size >= 2) {
            rpm = ((rpmReading[0] * 256) + rpmReading[1]) / 4
            consecutiveNoDataRpm = 0
        } else {
            consecutiveNoDataRpm++
        }
    }

    private fun pollSlowGroup() {
        parsePid("0105", sendCommand("0105"))?.let { coolantC = it[0] - 40 }
        parsePid("015C", sendCommand("015C"))?.let { oilC = it[0] - 40 }
        parsePid("0142", sendCommand("0142"))?.let {
            if (it.size >= 2) voltage = ((it[0] * 256) + it[1]) / 1000f
        }

        val mapKpa = parsePid("010B", sendCommand("010B"))?.getOrNull(0)
        val baroKpa = parsePid("0133", sendCommand("0133"))?.getOrNull(0)
        if (mapKpa != null && baroKpa != null) {
            boostBar = (mapKpa - baroKpa) / 100f
        }

        parsePid("0104", sendCommand("0104"))?.let { loadPct = (it[0] * 100) / 255 }

        if (dpfSupported) {
            val dpfReading = parsePid("017C", sendCommand("017C"))
            if (dpfReading != null && dpfReading.size >= 2) {
                dpfTempC = (((dpfReading[0] * 256) + dpfReading[1]) / 10) - 40
            } else {
                dpfSupported = false
                dpfTempC = null
            }
        }
    }

    private fun integrateKm(currentSpeedKmh: Int) {
        val now = SystemClock.elapsedRealtime()
        val dtSeconds = (now - lastSpeedSampleTime) / 1000.0
        lastSpeedSampleTime = now
        if (currentSpeedKmh > 0 && dtSeconds > 0) {
            val deltaKm = (currentSpeedKmh * dtSeconds / 3600.0).toFloat()
            kmTrip += deltaKm
            kmTotal += deltaKm
        }
    }

    /**
     * Sends one command and waits for the '>' prompt, single-flight (only
     * ever called sequentially from the poll loop). Returns null on timeout.
     * BluetoothSocket streams have no settable read timeout, so the bound is
     * enforced by polling InputStream.available() against a deadline instead
     * of blocking indefinitely in read(); a dead socket surfaces as an
     * IOException once it's closed (from here or from closeSocket()).
     */
    private fun sendCommand(command: String): String? {
        val out = output ?: throw IOException("OBD socket not connected")
        val inStream = input ?: throw IOException("OBD socket not connected")
        out.write((command + "\r").toByteArray())
        out.flush()
        return readUntilPrompt(inStream)
    }

    private fun readUntilPrompt(stream: InputStream): String? {
        val buffer = StringBuilder()
        val byteBuf = ByteArray(1)
        val deadline = SystemClock.elapsedRealtime() + COMMAND_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (stream.available() <= 0) {
                Thread.sleep(10)
                continue
            }
            val n = stream.read(byteBuf)
            if (n <= 0) throw IOException("OBD stream closed")
            val c = byteBuf[0].toInt().toChar()
            if (c == '>') return buffer.toString()
            if (c != ' ') buffer.append(c)
        }
        return null
    }

    private fun parsePid(pid: String, raw: String?): List<Int>? {
        if (raw == null) return null
        val cleaned = raw.uppercase().replace("\r", " ").replace("\n", " ")
        if (cleaned.contains("NO DATA") || cleaned.contains("NODATA")) return null
        if (cleaned.contains("SEARCHING") || cleaned.contains("UNABLE") || cleaned.contains("ERROR")) return null

        val tokens = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        val expectedPidByte = pid.substring(2).uppercase()
        val startIndex = tokens.indexOfFirst { it == "41" }
        if (startIndex == -1 || startIndex + 1 >= tokens.size) return null
        if (tokens[startIndex + 1] != expectedPidByte) return null

        val dataBytes = tokens.drop(startIndex + 2).mapNotNull { it.toIntOrNull(16) }
        return dataBytes.ifEmpty { null }
    }

    private fun writeTelemetryIfDue(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastJsonWriteTime < JSON_WRITE_INTERVAL_MS) return
        lastJsonWriteTime = now
        writeTelemetry()
    }

    private fun writeTelemetry() {
        val json = JSONObject().apply {
            put("ts", System.currentTimeMillis())
            put("conectado", connected)
            put("velocidad", speedKmh)
            put("rpm", rpm)
            put("refrigerante", coolantC ?: JSONObject.NULL)
            put("aceite", oilC ?: JSONObject.NULL)
            put("voltaje", voltage ?: JSONObject.NULL)
            put("boost", boostBar ?: JSONObject.NULL)
            put("carga", loadPct ?: JSONObject.NULL)
            put("kmTrip", kmTrip)
            put("kmTotal", kmTotal)
            put("dpfTemp", dpfTempC ?: JSONObject.NULL)
        }
        writeAtomically(json.toString())
    }

    private fun writeAtomically(content: String) {
        try {
            val finalFile = File(TELEMETRY_FILE_PATH)
            finalFile.parentFile?.mkdirs()
            val tempFile = File(finalFile.parentFile, "${finalFile.name}.tmp")
            tempFile.writeText(content)
            if (!tempFile.renameTo(finalFile)) {
                finalFile.writeText(content)
                tempFile.delete()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to write telemetry file", e)
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to write telemetry file", e)
        }
    }

    private fun persistKmIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastKmPersistTime >= KM_PERSIST_INTERVAL_MS) {
            lastKmPersistTime = now
            persistKmTotal()
        }
    }

    private fun persistKmTotal() {
        AppPreferences.setObdKmTotal(service, kmTotal)
    }

    private fun closeSocket() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }
}
