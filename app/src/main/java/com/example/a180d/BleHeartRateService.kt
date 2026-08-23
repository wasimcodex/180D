package com.example.a180d

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.coroutines.resume

data class HeartRateSample(val bpm: Int, val timestampMs: Long)

enum class ConnectionState {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    DISCOVERING,
    CONNECTED,
    RECONNECTING,
    LINK_LOST_UNRECOVERABLE,
}

/**
 * Foreground service owning the BLE connection to the Fitbit Air's standard
 * Heart Rate service (0x180D). See CLAUDE.md for the hardware facts and
 * GATT-cache gotcha this implementation works around.
 */
class BleHeartRateService : Service() {

    inner class LocalBinder : Binder() {
        val service: BleHeartRateService get() = this@BleHeartRateService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent): IBinder = binder

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private lateinit var notificationManager: NotificationManager

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _latestSample = MutableStateFlow<HeartRateSample?>(null)
    val latestSample: StateFlow<HeartRateSample?> = _latestSample.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var sessionActive = false
    private var targetDevice: BluetoothDevice? = null
    private var currentGatt: BluetoothGatt? = null
    private var reconnectJob: Job? = null
    private var firstDisconnectAtMs = 0L
    private var backoffIndex = 0

    override fun onCreate() {
        super.onCreate()
        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
        serviceScope.launch {
            latestSample.collect { sample -> updateNotification(sample) }
        }
        serviceScope.launch {
            connectionState.collect { updateNotification(latestSample.value) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification(null))
                startSession()
            }
            ACTION_STOP -> {
                stopSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ---- Session lifecycle -------------------------------------------------

    @SuppressLint("MissingPermission")
    private fun startSession() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            _lastError.value = "Bluetooth permission is required to connect."
            _connectionState.value = ConnectionState.LINK_LOST_UNRECOVERABLE
            return
        }
        sessionActive = true
        _lastError.value = null
        firstDisconnectAtMs = 0L
        backoffIndex = 0
        serviceScope.launch {
            _connectionState.value = ConnectionState.SCANNING
            val device = findAirDevice()
            if (device == null) {
                _connectionState.value = ConnectionState.LINK_LOST_UNRECOVERABLE
                _lastError.value = "Couldn't find Google Fitbit Air. In Google Health, go to " +
                    "Connections → Fitbit Air and enable “Always visible,” then start a new session."
                return@launch
            }
            targetDevice = device
            attemptConnect()
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopSession() {
        sessionActive = false
        reconnectJob?.cancel()
        reconnectJob = null
        currentGatt?.let { gatt ->
            runCatching { gatt.disconnect() }
            runCatching { gatt.close() }
        }
        currentGatt = null
        targetDevice = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _latestSample.value = null
    }

    // ---- Device discovery ---------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun findAirDevice(): BluetoothDevice? {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return null
        bluetoothAdapter.bondedDevices?.firstOrNull { it.name == AIR_DEVICE_NAME }?.let { return it }
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) return null
        return scanForAir()
    }

    @SuppressLint("MissingPermission")
    private suspend fun scanForAir(): BluetoothDevice? = withTimeoutOrNull(SCAN_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            val scanner = bluetoothAdapter.bluetoothLeScanner
            if (scanner == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            val filter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(HR_SERVICE_UUID))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    runCatching { scanner.stopScan(this) }
                    if (cont.isActive) cont.resume(result.device)
                }
                override fun onScanFailed(errorCode: Int) {
                    if (cont.isActive) cont.resume(null)
                }
            }
            cont.invokeOnCancellation { runCatching { scanner.stopScan(callback) } }
            scanner.startScan(listOf(filter), settings, callback)
        }
    }

    // ---- Connection + reconnection ------------------------------------------

    @SuppressLint("MissingPermission")
    private fun attemptConnect() {
        val device = targetDevice ?: return
        _connectionState.value = ConnectionState.CONNECTING
        currentGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    private fun handleDisconnected(gatt: BluetoothGatt) {
        runCatching { gatt.close() }
        if (currentGatt === gatt) currentGatt = null
        if (!sessionActive) return

        val now = System.currentTimeMillis()
        if (firstDisconnectAtMs == 0L) firstDisconnectAtMs = now
        if (now - firstDisconnectAtMs >= UNRECOVERABLE_AFTER_MS) {
            _connectionState.value = ConnectionState.LINK_LOST_UNRECOVERABLE
            _lastError.value = "Lost connection to Fitbit Air. Re-enable “Always visible” " +
                "in Google Health, then start a new session."
            return
        }

        _connectionState.value = ConnectionState.RECONNECTING
        val delayMs = BACKOFF_SCHEDULE_MS.getOrElse(backoffIndex) { BACKOFF_SCHEDULE_MS.last() }
        backoffIndex++
        reconnectJob = serviceScope.launch {
            delay(delayMs)
            if (sessionActive) attemptConnect()
        }
    }

    // ---- GATT callback -------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _connectionState.value = ConnectionState.DISCOVERING
                    gatt.forceRefresh()
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> handleDisconnected(gatt)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _lastError.value = "Service discovery failed (status $status)."
                gatt.disconnect()
                return
            }
            val hrCharacteristic = gatt.getService(HR_SERVICE_UUID)?.getCharacteristic(HR_MEASUREMENT_UUID)
            if (hrCharacteristic == null) {
                _connectionState.value = ConnectionState.LINK_LOST_UNRECOVERABLE
                _lastError.value = "Heart rate service not found on this device. Try toggling " +
                    "Bluetooth off/on or rebooting your phone."
                gatt.disconnect()
                return
            }
            gatt.setCharacteristicNotification(hrCharacteristic, true)
            val cccd = hrCharacteristic.getDescriptor(CCCD_UUID)
            if (cccd == null || !gatt.writeDescriptorCompat(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                _lastError.value = "Couldn't enable heart rate notifications."
            }
            subscribeServiceChanged(gatt)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid == CCCD_UUID && descriptor.characteristic.uuid == HR_MEASUREMENT_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    _connectionState.value = ConnectionState.CONNECTED
                    _lastError.value = null
                    firstDisconnectAtMs = 0L
                    backoffIndex = 0
                } else {
                    _lastError.value = "Couldn't subscribe to heart rate notifications (status $status)."
                }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            handleCharacteristicChanged(gatt, characteristic.uuid, characteristic.value)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleCharacteristicChanged(gatt, characteristic.uuid, value)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleCharacteristicChanged(gatt: BluetoothGatt, uuid: UUID, value: ByteArray?) {
        when (uuid) {
            HR_MEASUREMENT_UUID -> {
                val bpm = value?.let { parseHeartRateBpm(it) } ?: return
                _latestSample.value = HeartRateSample(bpm, System.currentTimeMillis())
            }
            SERVICE_CHANGED_UUID -> gatt.discoverServices()
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeServiceChanged(gatt: BluetoothGatt) {
        val changedCharacteristic = gatt.getService(GENERIC_ATTRIBUTE_SERVICE_UUID)
            ?.getCharacteristic(SERVICE_CHANGED_UUID) ?: return
        gatt.setCharacteristicNotification(changedCharacteristic, true)
        val cccd = changedCharacteristic.getDescriptor(CCCD_UUID) ?: return
        gatt.writeDescriptorCompat(cccd, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
    }

    // ---- Notification --------------------------------------------------------

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Heart rate session",
            NotificationManager.IMPORTANCE_LOW,
        )
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(sample: HeartRateSample?): Notification {
        val text = when {
            sample != null -> "${sample.bpm} bpm"
            connectionState.value == ConnectionState.RECONNECTING -> "Reconnecting…"
            connectionState.value == ConnectionState.SCANNING ||
                connectionState.value == ConnectionState.CONNECTING ||
                connectionState.value == ConnectionState.DISCOVERING -> "Connecting…"
            else -> "Waiting for heart rate…"
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Fitbit Air heart rate")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(contentIntent)
            .setGroupSummary(false)
            .build()
    }

    private fun updateNotification(sample: HeartRateSample?) {
        if (!sessionActive) return
        notificationManager.notify(NOTIFICATION_ID, buildNotification(sample))
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "com.example.a180d.action.START_SESSION"
        const val ACTION_STOP = "com.example.a180d.action.STOP_SESSION"

        private const val AIR_DEVICE_NAME = "Google Fitbit Air"
        private const val CHANNEL_ID = "heart_rate_session"
        private const val NOTIFICATION_ID = 1001
        private const val SCAN_TIMEOUT_MS = 8_000L
        private const val UNRECOVERABLE_AFTER_MS = 30_000L
        private val BACKOFF_SCHEDULE_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 30_000)

        private fun uuid16(shortHex: String): UUID =
            UUID.fromString("0000$shortHex-0000-1000-8000-00805f9b34fb")

        val HR_SERVICE_UUID: UUID = uuid16("180d")
        val HR_MEASUREMENT_UUID: UUID = uuid16("2a37")
        val CCCD_UUID: UUID = uuid16("2902")
        val GENERIC_ATTRIBUTE_SERVICE_UUID: UUID = uuid16("1801")
        val SERVICE_CHANGED_UUID: UUID = uuid16("2a05")

        /**
         * Android caches the GATT service table for bonded devices. That cache can
         * predate a service becoming available (e.g. Share Heart Rate being turned
         * on after pairing), so discovery silently omits it. There is no public API
         * for this; the hidden `refresh()` method is the standard workaround.
         */
        internal fun BluetoothGatt.forceRefresh(): Boolean = runCatching {
            javaClass.getMethod("refresh").invoke(this) as Boolean
        }.getOrDefault(false)

        @SuppressLint("MissingPermission")
        internal fun BluetoothGatt.writeDescriptorCompat(descriptor: BluetoothGattDescriptor, value: ByteArray): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = value
                @Suppress("DEPRECATION")
                writeDescriptor(descriptor)
            }
        }

        /**
         * Parses the Heart Rate Measurement characteristic (0x2A37) per the
         * Bluetooth SIG flags byte. Field offsets are cumulative, so this must be
         * parsed in order even though only BPM is currently used.
         */
        internal fun parseHeartRateBpm(data: ByteArray): Int? {
            if (data.isEmpty()) return null
            val flags = data[0].toInt() and 0xFF
            val bpmIsUInt16 = (flags and 0x01) != 0
            val bpm = if (bpmIsUInt16) {
                if (data.size < 3) return null
                (data[1].toInt() and 0xFF) or ((data[2].toInt() and 0xFF) shl 8)
            } else {
                if (data.size < 2) return null
                data[1].toInt() and 0xFF
            }
            return bpm.takeIf { it in 25..230 }
        }
    }
}
