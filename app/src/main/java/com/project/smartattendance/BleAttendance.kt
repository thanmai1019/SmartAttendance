package com.project.smartattendance

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.resume

private const val TEACHER_BLE_SCAN_TIMEOUT_MILLIS = 6_000L
private const val TEACHER_BLE_ADVERTISE_TIMEOUT_MILLIS = 4_000L
private const val ATTENDANCE_MANUFACTURER_ID = 0x0BEE
private val LEGACY_ATTENDANCE_SERVICE_UUID: UUID = UUID.fromString("7d4a9a4f-6e53-4b63-b5f9-4b2eac8f3f91")

data class BleVerificationResult(
    val success: Boolean,
    val message: String,
    val rssi: Int? = null
)

data class BleDiagnostics(
    val bluetoothLeSupported: Boolean,
    val bluetoothEnabled: Boolean,
    val advertiseSupported: Boolean,
    val scanSupported: Boolean,
    val permissionGranted: Boolean,
    val details: String
)

class TeacherBleBroadcaster(private val context: Context) {
    private var advertiseCallback: AdvertiseCallback? = null
    private var advertisedCode: String? = null

    suspend fun start(sessionCode: String): BleVerificationResult {
        val normalizedCode = sessionCode.trim().uppercase()
        if (normalizedCode.isBlank()) {
            return BleVerificationResult(false, "Attendance code is missing, so Bluetooth broadcast cannot start.")
        }
        if (isRunningOnEmulator()) {
            advertisedCode = normalizedCode
            return BleVerificationResult(true, "BLE broadcast is skipped on emulator.")
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return BleVerificationResult(false, "This teacher device does not support Bluetooth LE.")
        }
        if (!hasTeacherBluetoothPermission(context)) {
            return BleVerificationResult(false, "Bluetooth advertise permission is missing on the teacher device.")
        }

        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            ?: return BleVerificationResult(false, "Bluetooth manager is unavailable.")
        val adapter = bluetoothManager.adapter
            ?: return BleVerificationResult(false, "Bluetooth adapter is unavailable.")
        if (!adapter.isEnabled) {
            return BleVerificationResult(false, "Turn on Bluetooth on the teacher device to broadcast the attendance session.")
        }

        val advertiser = adapter.bluetoothLeAdvertiser
            ?: return BleVerificationResult(false, "Bluetooth advertising is not available on this device.")

        if (advertisedCode == normalizedCode && advertiseCallback != null) {
            return BleVerificationResult(true, "Teacher Bluetooth broadcast is already live for code $normalizedCode.")
        }

        stop()

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(
                ATTENDANCE_MANUFACTURER_ID,
                normalizedCode.toByteArray(StandardCharsets.UTF_8)
            )
            .build()
        val handler = Handler(Looper.getMainLooper())

        return suspendCancellableCoroutine { continuation ->
            var finished = false

            fun complete(result: BleVerificationResult) {
                if (finished) return
                finished = true
                handler.removeCallbacksAndMessages(null)
                continuation.resume(result)
            }

            val callback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                    advertiseCallback = this
                    advertisedCode = normalizedCode
                    complete(BleVerificationResult(true, "Teacher Bluetooth broadcast is active for code $normalizedCode."))
                }

                override fun onStartFailure(errorCode: Int) {
                    advertiseCallback = null
                    advertisedCode = null
                    complete(BleVerificationResult(false, "Bluetooth advertising could not start. Error code $errorCode."))
                }
            }

            val started = runCatching {
                advertiser.startAdvertising(settings, data, callback)
                true
            }.getOrDefault(false)

            if (!started) {
                complete(BleVerificationResult(false, "Bluetooth advertising could not start on the teacher device."))
                return@suspendCancellableCoroutine
            }

            handler.postDelayed({
                runCatching { advertiser.stopAdvertising(callback) }
                advertiseCallback = null
                advertisedCode = null
                complete(BleVerificationResult(false, "Bluetooth advertising start timed out on the teacher device."))
            }, TEACHER_BLE_ADVERTISE_TIMEOUT_MILLIS)

            continuation.invokeOnCancellation {
                handler.removeCallbacksAndMessages(null)
                runCatching { advertiser.stopAdvertising(callback) }
                if (advertiseCallback == callback) {
                    advertiseCallback = null
                    advertisedCode = null
                }
            }
        }
    }

    fun stop() {
        if (isRunningOnEmulator()) {
            advertisedCode = null
            return
        }

        val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return
        val adapter = bluetoothManager.adapter ?: return
        val advertiser = adapter.bluetoothLeAdvertiser ?: return
        advertiseCallback?.let { callback ->
            runCatching { advertiser.stopAdvertising(callback) }
        }
        advertiseCallback = null
        advertisedCode = null
    }
}

suspend fun scanForTeacherBleDevice(
    context: Context,
    expectedSessionCode: String
): BleVerificationResult {
    if (expectedSessionCode.isBlank()) {
        return BleVerificationResult(false, "Enter the attendance code before starting Bluetooth verification.")
    }
    if (isRunningOnEmulator()) {
        return BleVerificationResult(true, "Bluetooth scan skipped on emulator.")
    }
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
        return BleVerificationResult(false, "This device does not support Bluetooth LE.")
    }
    if (!hasStudentBluetoothPermission(context)) {
        return BleVerificationResult(false, "Bluetooth scan permission is missing.")
    }

    val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
        ?: return BleVerificationResult(false, "Bluetooth manager is unavailable.")
    val adapter: BluetoothAdapter = bluetoothManager.adapter
        ?: return BleVerificationResult(false, "Bluetooth adapter is unavailable.")
    if (!adapter.isEnabled) {
        return BleVerificationResult(false, "Bluetooth is currently turned off.")
    }

    val scanner = adapter.bluetoothLeScanner
        ?: return BleVerificationResult(false, "Bluetooth LE scanner is unavailable.")
    val handler = Handler(Looper.getMainLooper())
    val targetCode = expectedSessionCode.trim().uppercase()

    return suspendCancellableCoroutine { continuation ->
        var finished = false
        var strongestMatch: ScanResult? = null
        lateinit var scanCallback: ScanCallback

        fun complete(result: BleVerificationResult) {
            if (finished) return
            finished = true
            runCatching { scanner.stopScan(scanCallback) }
            continuation.resume(result)
        }

        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val serviceData = readAttendancePayload(result)?.uppercase()

                if (serviceData == targetCode) {
                    if (strongestMatch == null || result.rssi > strongestMatch?.rssi ?: Int.MIN_VALUE) {
                        strongestMatch = result
                    }
                    if (result.rssi >= MIN_ACCEPTABLE_BLE_RSSI) {
                        handler.removeCallbacksAndMessages(null)
                        complete(
                            BleVerificationResult(
                                success = true,
                                message = "Teacher device detected nearby for code $targetCode at ${result.rssi} dBm.",
                                rssi = result.rssi
                            )
                        )
                    }
                }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { result -> onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, result) }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.removeCallbacksAndMessages(null)
                complete(BleVerificationResult(false, "Bluetooth scan failed with error code $errorCode."))
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        val started = runCatching {
            scanner.startScan(emptyList(), settings, scanCallback)
            true
        }.getOrDefault(false)

        if (!started) {
            complete(BleVerificationResult(false, "Bluetooth scan could not start on this device."))
            return@suspendCancellableCoroutine
        }

        handler.postDelayed({
            val bestResult = strongestMatch
            if (bestResult != null) {
                complete(
                    BleVerificationResult(
                        false,
                        "Teacher Bluetooth was found for code $targetCode, but the signal was too weak (${bestResult.rssi} dBm). Move closer and scan again.",
                        bestResult.rssi
                    )
                )
            } else {
                complete(BleVerificationResult(false, "No teacher Bluetooth session was detected for code $targetCode."))
            }
        }, TEACHER_BLE_SCAN_TIMEOUT_MILLIS)

        continuation.invokeOnCancellation {
            handler.removeCallbacksAndMessages(null)
            runCatching { scanner.stopScan(scanCallback) }
        }
    }
}

fun teacherBleDiagnostics(context: Context): BleDiagnostics {
    val manager = context.getSystemService(BluetoothManager::class.java)
    val adapter = manager?.adapter
    val leSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    val advertiseSupported = adapter?.bluetoothLeAdvertiser != null
    val enabled = adapter?.isEnabled == true
    val permissionGranted = hasTeacherBluetoothPermission(context)
    val detailParts = buildList {
        add("BLE support: ${if (leSupported) "Yes" else "No"}")
        add("Bluetooth on: ${if (enabled) "Yes" else "No"}")
        add("Advertising available: ${if (advertiseSupported) "Yes" else "No"}")
        add("Permissions granted: ${if (permissionGranted) "Yes" else "No"}")
        add("Android ${Build.VERSION.SDK_INT}")
    }
    return BleDiagnostics(
        bluetoothLeSupported = leSupported,
        bluetoothEnabled = enabled,
        advertiseSupported = advertiseSupported,
        scanSupported = adapter?.bluetoothLeScanner != null,
        permissionGranted = permissionGranted,
        details = detailParts.joinToString(" | ")
    )
}

fun studentBleDiagnostics(context: Context): BleDiagnostics {
    val manager = context.getSystemService(BluetoothManager::class.java)
    val adapter = manager?.adapter
    val leSupported = context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
    val scanSupported = adapter?.bluetoothLeScanner != null
    val enabled = adapter?.isEnabled == true
    val permissionGranted = hasStudentBluetoothPermission(context)
    val detailParts = buildList {
        add("BLE support: ${if (leSupported) "Yes" else "No"}")
        add("Bluetooth on: ${if (enabled) "Yes" else "No"}")
        add("Scanner available: ${if (scanSupported) "Yes" else "No"}")
        add("Permissions granted: ${if (permissionGranted) "Yes" else "No"}")
        add("Android ${Build.VERSION.SDK_INT}")
    }
    return BleDiagnostics(
        bluetoothLeSupported = leSupported,
        bluetoothEnabled = enabled,
        advertiseSupported = adapter?.bluetoothLeAdvertiser != null,
        scanSupported = scanSupported,
        permissionGranted = permissionGranted,
        details = detailParts.joinToString(" | ")
    )
}

private fun readAttendancePayload(result: ScanResult): String? {
    val record = result.scanRecord ?: return null
    val manufacturerPayload = record
        .getManufacturerSpecificData(ATTENDANCE_MANUFACTURER_ID)
        ?.toString(StandardCharsets.UTF_8)
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    if (manufacturerPayload != null) return manufacturerPayload

    return record
        .getServiceData(ParcelUuid(LEGACY_ATTENDANCE_SERVICE_UUID))
        ?.toString(StandardCharsets.UTF_8)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

fun studentRequiredPermissions(): Array<String> {
    val permissions = mutableListOf(Manifest.permission.CAMERA)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        permissions += Manifest.permission.BLUETOOTH_CONNECT
        permissions += Manifest.permission.BLUETOOTH_SCAN
    } else {
        permissions += Manifest.permission.ACCESS_FINE_LOCATION
    }
    return permissions.toTypedArray()
}

fun teacherRequiredPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        emptyArray()
    }
}

private fun hasStudentBluetoothPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
}

private fun hasTeacherBluetoothPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) ==
            PackageManager.PERMISSION_GRANTED
    }
}
