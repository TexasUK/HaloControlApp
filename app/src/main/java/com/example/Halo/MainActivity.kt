// MainActivity.kt — Complete with TickSeekBar integration
package com.example.Halo

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // App version
    private val APP_VERSION = "v1.8"
    private val APP_BUILD_DATE = "2025-10-11"

    // BLE UUIDs — MUST match ESP32 (use your current, bumped service UUID)
    private val SERVICE_UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914c")
    private val FLASH_CHARACTERISTIC_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
    private val TEST_CHARACTERISTIC_UUID  = UUID.fromString("d7a2d055-5c6a-4b8a-8c0d-2e1e1c6f4b9a")
    private val VOLUME_CHARACTERISTIC_UUID = UUID.fromString("f7a2d055-5c6a-4b8a-8c0d-2e1e1c6f4b9b")
    private val ELEVATION_CHARACTERISTIC_UUID = UUID.fromString("a8b2d055-5c6a-4b8a-8c0d-2e1e1c6f4b9c")
    private val QNH_CHARACTERISTIC_UUID = UUID.fromString("b9c2d055-5c6a-4b8a-8c0d-2e1e1c6f4b9d")
    private val RESET_CHARACTERISTIC_UUID = UUID.fromString("c8b2d055-5c6a-4b8a-8c0d-2e1e1c6f4b9e")
    private val DATASOURCE_CHARACTERISTIC_UUID = UUID.fromString("d8b2d055-5c6a-4b8a-8c0d-2e1e1c6f4b9f")

    // Volume scaling
    private val VOLUME_SLIDER_MAX = 10
    private val VOLUME_ACTUAL_MAX = 30
    private val DEFAULT_VOLUME_SLIDER = 7
    private val DEFAULT_VOLUME_ACTUAL = 21

    // Data source
    private val SOFTRF_BAUD_RATE = 38400
    private val FLARM_BAUD_RATE  = 19200
    private val DEFAULT_DATA_SOURCE = "SoftRF"

    // Other defaults (app slider indices)
    private val DEFAULT_ELEVATION = 64   // 640ft (Lasham)
    private val DEFAULT_QNH       = 107  // ×2 hPa slider

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private val bleScanner by lazy { bluetoothAdapter.bluetoothLeScanner }
    private var scanning = false
    private val scanHandler = Handler(Looper.getMainLooper())

    private var bluetoothGatt: BluetoothGatt? = null
    private var lastDevice: BluetoothDevice? = null

    // Characteristics (everything except BAUD)
    private var flashCharacteristic: BluetoothGattCharacteristic? = null
    private var testCharacteristic: BluetoothGattCharacteristic? = null
    private var volumeCharacteristic: BluetoothGattCharacteristic? = null
    private var elevationCharacteristic: BluetoothGattCharacteristic? = null
    private var qnhCharacteristic: BluetoothGattCharacteristic? = null
    private var resetCharacteristic: BluetoothGattCharacteristic? = null
    private var dataSourceCharacteristic: BluetoothGattCharacteristic? = null

    // UI - Changed to TickSeekBar
    private lateinit var devicesSpinner: Spinner
    private lateinit var scanButton: Button
    private lateinit var connectButton: Button
    private lateinit var flashButton: Button
    private lateinit var resetButton: Button
    private lateinit var testSwitch: Switch
    private lateinit var dataSourceSwitch: Switch
    private lateinit var volumeSeekBar: TickSeekBar
    private lateinit var volumeValue: TextView
    private lateinit var elevationSeekBar: TickSeekBar
    private lateinit var elevationValue: TextView
    private lateinit var qnhSeekBar: TickSeekBar
    private lateinit var qnhValue: TextView
    private lateinit var dataSourceValue: TextView
    private lateinit var statusTextView: TextView
    private lateinit var connectionStatus: TextView
    private lateinit var versionTextView: TextView

    private lateinit var deviceList: MutableList<BluetoothDevice>
    private lateinit var deviceAdapter: ArrayAdapter<String>

    private var isConnected = false
    private var isReadingValues = false

    // Read values on connect class variables
    private var characteristicsRead = 0
    private var totalCharacteristicsToRead = 4

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSIONS = 1
        private const val TAG = "BLEDebug"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeUI()
        checkPermissions()
        initializeBluetooth()

        Log.d(TAG, "App started: $APP_VERSION ($APP_BUILD_DATE)")
    }

    private fun initializeUI() {
        devicesSpinner = findViewById(R.id.devicesSpinner)
        scanButton = findViewById(R.id.scanButton)
        connectButton = findViewById(R.id.connectButton)
        flashButton = findViewById(R.id.flashButton)
        resetButton = findViewById(R.id.resetButton)
        testSwitch = findViewById(R.id.testSwitch)
        dataSourceSwitch = findViewById(R.id.dataSourceSwitch)
        volumeSeekBar = findViewById(R.id.volumeSeekBar)
        volumeValue = findViewById(R.id.volumeValue)
        elevationSeekBar = findViewById(R.id.elevationSeekBar)
        elevationValue = findViewById(R.id.elevationValue)
        qnhSeekBar = findViewById(R.id.qnhSeekBar)
        qnhValue = findViewById(R.id.qnhValue)
        dataSourceValue = findViewById(R.id.dataSourceValue)
        statusTextView = findViewById(R.id.statusTextView)
        connectionStatus = findViewById(R.id.connectionStatus)
        versionTextView = findViewById(R.id.versionTextView)

        versionTextView.text = "Version: $APP_VERSION"

        deviceList = mutableListOf()

        // Initialize spinner with a placeholder item
        deviceAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf("Select a device"))
        deviceAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        devicesSpinner.adapter = deviceAdapter

        // Set spinner selection listener
        devicesSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 0) {
                    // This is the "Select a device" placeholder
                    statusTextView.text = "Please select a device"
                } else if (position - 1 < deviceList.size) {
                    // Adjust for the placeholder item (position - 1)
                    val selectedDevice = deviceList[position - 1]
                    statusTextView.text = "Selected: ${selectedDevice.name ?: "Unknown device"}"
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                statusTextView.text = "No device selected"
            }
        }

        // defaults - Use the setter methods instead of direct property access
        volumeSeekBar.setMax(VOLUME_SLIDER_MAX)
        volumeSeekBar.setProgress(DEFAULT_VOLUME_SLIDER)

        // Changed elevation range to 0-1000ft (0-100 in 10ft increments)
        elevationSeekBar.setMax(100)
        elevationSeekBar.setProgress(DEFAULT_ELEVATION)

        qnhSeekBar.setMax(200)
        qnhSeekBar.setProgress(DEFAULT_QNH)
        dataSourceSwitch.isChecked = (DEFAULT_DATA_SOURCE == "SoftRF")

        updateDataSourceDisplay()
        updateSliderDisplays()
        setupListeners()
        updateConnectButton()
    }

    private fun convertSliderToActualVolume(sliderValue: Int): Int =
        (sliderValue * VOLUME_ACTUAL_MAX / VOLUME_SLIDER_MAX).coerceIn(0, VOLUME_ACTUAL_MAX)
    private fun convertActualToSliderVolume(actualValue: Int): Int =
        (actualValue * VOLUME_SLIDER_MAX / VOLUME_ACTUAL_MAX).coerceIn(0, VOLUME_SLIDER_MAX)

    private fun updateSliderDisplays() {
        volumeValue.text = "Volume: ${volumeSeekBar.getProgress()}/10"
        elevationValue.text = "Airfield Elevation: ${elevationSeekBar.getProgress() * 10}ft"
        val qnh = 800 + (qnhSeekBar.getProgress() * 2)
        qnhValue.text = "QNH Pressure: ${String.format("%.1f", qnh.toFloat())}mb"
    }

    private fun updateDataSourceDisplay() {
        val isSoftRF = dataSourceSwitch.isChecked
        val srcName = if (isSoftRF) "SoftRF" else "Flarm"
        val baud = if (isSoftRF) SOFTRF_BAUD_RATE else FLARM_BAUD_RATE
        dataSourceValue.text = "$srcName ($baud baud)"
    }

    private fun setupListeners() {
        scanButton.setOnClickListener { scanDevices() }
        scanButton.setOnLongClickListener {
            Toast.makeText(this, "Forced rescan started", Toast.LENGTH_SHORT).show()
            deviceList.clear()
            // Clear existing devices but keep the placeholder
            if (deviceAdapter.count > 0) {
                // Remove all items except the first one (placeholder)
                while (deviceAdapter.count > 1) {
                    deviceAdapter.remove(deviceAdapter.getItem(1))
                }
            }
            stopBleScan()
            clearBluetoothCache()
            Handler(Looper.getMainLooper()).postDelayed({ scanDevices() }, 1000)
            true
        }

        connectButton.setOnClickListener { if (isConnected) disconnectDevice() else connectToDevice() }

        flashButton.setOnClickListener { sendFlashCommand() }
        resetButton.setOnClickListener { resetToDefaults() }

        testSwitch.setOnCheckedChangeListener { _, isChecked -> sendTestCommand(isChecked) }

        dataSourceSwitch.setOnCheckedChangeListener { _, isChecked ->
            val srcName = if (isChecked) "SoftRF" else "Flarm"
            val baud = if (isChecked) SOFTRF_BAUD_RATE else FLARM_BAUD_RATE
            Log.d(TAG, "Data source changed: $srcName ($baud baud)")
            updateDataSourceDisplay()
            if (isConnected) sendDataSourceCommand(isChecked)
            else Toast.makeText(this, "Connect to apply $srcName settings", Toast.LENGTH_SHORT).show()
        }

        volumeSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                volumeValue.text = "Volume: $progress/10"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (!isReadingValues) sendVolumeCommand(convertSliderToActualVolume(volumeSeekBar.getProgress()))
            }
        })

        elevationSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                elevationValue.text = "Airfield Elevation: ${progress * 10}ft"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (!isReadingValues) sendElevationCommand(elevationSeekBar.getProgress())
            }
        })

        qnhSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val q = 800 + (progress * 2)
                qnhValue.text = "QNH Pressure: ${String.format("%.1f", q.toFloat())}mb"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (!isReadingValues) sendQNHCommand(qnhSeekBar.getProgress())
            }
        })
    }

    private fun enableControlsWithFallback() {
        runOnUiThread {
            Handler(Looper.getMainLooper()).postDelayed({
                if (isReadingValues) {
                    Log.w(TAG, "Fallback: Enabling controls after timeout")
                    isReadingValues = false
                    dataSourceSwitch.isEnabled = true
                    volumeSeekBar.isEnabled = true
                    elevationSeekBar.isEnabled = true
                    qnhSeekBar.isEnabled = true
                    statusTextView.text = "Connected (some values may be default)"
                }
            }, 5000) // 5 second fallback
        }
    }

    private fun resetToDefaults() {
        volumeSeekBar.setProgress(DEFAULT_VOLUME_SLIDER)
        elevationSeekBar.setProgress(DEFAULT_ELEVATION)
        qnhSeekBar.setProgress(DEFAULT_QNH)
        dataSourceSwitch.isChecked = (DEFAULT_DATA_SOURCE == "SoftRF")
        updateDataSourceDisplay()
        updateSliderDisplays()

        if (isConnected) {
            sendResetCommand()
            Handler(Looper.getMainLooper()).postDelayed({
                sendVolumeCommand(DEFAULT_VOLUME_ACTUAL)
                sendElevationCommand(DEFAULT_ELEVATION)
                sendQNHCommand(DEFAULT_QNH)
                sendDataSourceCommand(DEFAULT_DATA_SOURCE == "SoftRF")
            }, 500)
            Toast.makeText(this, "Reset to defaults sent", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Defaults set locally - connect to send to device", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateConnectButton() {
        connectButton.text = if (isConnected) "Disconnect" else "Connect"
    }

    private fun checkPermissions() {
        val perms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.BLUETOOTH)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADMIN) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (perms.isNotEmpty()) ActivityCompat.requestPermissions(this, perms.toTypedArray(), REQUEST_BLUETOOTH_PERMISSIONS)
    }

    private fun initializeBluetooth() {
        val mgr = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = mgr.adapter ?: run {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            finish(); return
        }
    }

    // Optional: nuke bonds with "Flarm" devices when long-press scanning
    @SuppressLint("MissingPermission")
    private fun clearBluetoothCache() {
        try {
            val method = bluetoothAdapter.javaClass.getMethod("removeBond", BluetoothDevice::class.java)
            bluetoothAdapter.bondedDevices.forEach { dev ->
                if (dev.name?.contains("Flarm", true) == true) {
                    method.invoke(bluetoothAdapter, dev)
                    Log.d(TAG, "Cleared bond for: ${dev.name}")
                }
            }
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun scanDevices() {
        // Clear existing devices but keep the placeholder
        if (deviceAdapter.count > 0) {
            // Remove all items except the first one (placeholder)
            while (deviceAdapter.count > 1) {
                deviceAdapter.remove(deviceAdapter.getItem(1))
            }
        }
        deviceList.clear()
        stopBleScan()

        // show paired matches first
        bluetoothAdapter.bondedDevices.forEach { dev ->
            if (dev.name?.contains("Flarm", true) == true) {
                if (deviceList.none { it.address == dev.address }) {
                    deviceList.add(dev)
                    deviceAdapter.add("PAIRED: ${dev.name ?: "Unknown"}")
                }
            }
        }

        scanning = true
        statusTextView.text = "Scanning for all BLE devices..."
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            bleScanner.startScan(emptyList<ScanFilter>(), settings, scanCallback)
            scanHandler.postDelayed({
                stopBleScan()
                statusTextView.text = if (deviceList.isEmpty()) "No BLE devices found." else "Scan complete. Found ${deviceList.size} devices."
            }, 15000)
        } catch (e: Exception) {
            statusTextView.text = "Scan failed: ${e.message}"
            scanning = false
        }
    }

    private fun stopBleScan() {
        if (!scanning) return
        scanning = false
        try { bleScanner.stopScan(scanCallback) } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice() {
        if (deviceList.isEmpty()) {
            Toast.makeText(this, "No devices found. Scan first.", Toast.LENGTH_SHORT).show();
            return
        }
        val pos = devicesSpinner.selectedItemPosition
        if (pos <= 0 || pos - 1 >= deviceList.size) {
            Toast.makeText(this, "Please select a device first", Toast.LENGTH_SHORT).show()
            return
        }
        val dev = deviceList[pos - 1]  // Adjust for placeholder
        lastDevice = dev

        stopBleScan()
        bluetoothGatt?.close(); bluetoothGatt = null

        Handler(Looper.getMainLooper()).postDelayed({
            bluetoothGatt = dev.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            connectionStatus.text = "Connecting to ${dev.name}..."
        }, 100)
    }

    @SuppressLint("MissingPermission")
    private fun disconnectDevice() {
        connectionStatus.text = "Disconnecting..."
        bluetoothGatt?.disconnect()
    }

    @SuppressLint("MissingPermission")
    private fun readCurrentValues() {
        isReadingValues = true
        characteristicsRead = 0
        totalCharacteristicsToRead = 4

        Log.d(TAG, "Starting to read current values...")

        // Disable controls until we read the actual values
        runOnUiThread {
            dataSourceSwitch.isEnabled = false
            volumeSeekBar.isEnabled = false
            elevationSeekBar.isEnabled = false
            qnhSeekBar.isEnabled = false
            statusTextView.text = "Reading current values..."
        }

        // Helper function to read characteristics with proper error handling
        fun readCharacteristicWithRetry(characteristic: BluetoothGattCharacteristic?, name: String, delay: Long) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (characteristic == null) {
                    Log.e(TAG, "$name characteristic is null")
                    characteristicsRead++
                    checkAllCharacteristicsRead()
                    return@postDelayed
                }

                val readSuccess = bluetoothGatt?.readCharacteristic(characteristic)
                if (readSuccess != true) {
                    Log.e(TAG, "Failed to initiate read for $name characteristic")
                    characteristicsRead++
                    checkAllCharacteristicsRead()
                }
                // If readSuccess == true, we wait for onCharacteristicRead callback
            }, delay)
        }

        // Read characteristics one by one with delays
        readCharacteristicWithRetry(volumeCharacteristic, "volume", 100)
        readCharacteristicWithRetry(elevationCharacteristic, "elevation", 300)
        readCharacteristicWithRetry(qnhCharacteristic, "QNH", 500)
        readCharacteristicWithRetry(dataSourceCharacteristic, "data source", 700)

        // Fallback in case some reads fail or timeout
        enableControlsWithFallback()
    }

    // Add this helper method to check if all reads are complete
    private fun checkAllCharacteristicsRead() {
        Log.d(TAG, "Characteristics read: $characteristicsRead/$totalCharacteristicsToRead")
        if (characteristicsRead >= totalCharacteristicsToRead) {
            runOnUiThread {
                // All values read (or failed), enable controls
                isReadingValues = false
                dataSourceSwitch.isEnabled = true
                volumeSeekBar.isEnabled = true
                elevationSeekBar.isEnabled = true
                qnhSeekBar.isEnabled = true

                val successCount = totalCharacteristicsToRead - countFailedReads()
                statusTextView.text = "Connected! Read $successCount/$totalCharacteristicsToRead values"
                Log.d(TAG, "All characteristic reads completed, controls enabled")
            }
        }
    }

    // Helper to count how many reads actually failed
    private fun countFailedReads(): Int {
        // You might want to track which specific reads failed
        // For now, we'll assume any increment without a corresponding onCharacteristicRead is a failure
        return 0 // Implement based on your needs
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(type: Int, res: ScanResult) {
            val dev = res.device ?: return
            val name = dev.name ?: "Unknown"
            runOnUiThread {
                if (deviceList.none { it.address == dev.address }) {
                    deviceList.add(dev)
                    deviceAdapter.add(if (name.contains("Flarm", true)) "Flarm: $name" else "Device: $name")
                    deviceAdapter.notifyDataSetChanged()
                    statusTextView.text = "Found: $name"
                }
            }
        }
        override fun onBatchScanResults(results: List<ScanResult>) {}
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { statusTextView.text = "Scan failed: $errorCode"; scanning = false }
        }
    }

    // ====== GATT ======
    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    isConnected = true
                    connectionStatus.text = "Connected"
                    gatt.discoverServices()
                    updateConnectButton()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    isConnected = false
                    connectionStatus.text = "Disconnected"
                    updateConnectButton()
                    // Re-enable controls when disconnected
                    isReadingValues = false
                    dataSourceSwitch.isEnabled = true
                    volumeSeekBar.isEnabled = true
                    elevationSeekBar.isEnabled = true
                    qnhSeekBar.isEnabled = true
                    try { gatt.close() } catch (_: Exception) {}
                    bluetoothGatt = null
                    Handler(Looper.getMainLooper()).postDelayed({
                        statusTextView.text = "Scanning for device..."
                        scanDevices()
                    }, 3000)
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    connectionStatus.text = "Connection error ($status)"
                    // Re-enable controls on connection error
                    isReadingValues = false
                    dataSourceSwitch.isEnabled = true
                    volumeSeekBar.isEnabled = true
                    elevationSeekBar.isEnabled = true
                    qnhSeekBar.isEnabled = true
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "Services discovered, status=$status")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    statusTextView.text = "Service discovery failed: $status"
                    // Re-enable controls if service discovery fails
                    isReadingValues = false
                    dataSourceSwitch.isEnabled = true
                    volumeSeekBar.isEnabled = true
                    elevationSeekBar.isEnabled = true
                    qnhSeekBar.isEnabled = true
                }
                return
            }
            val svc = gatt.getService(SERVICE_UUID)
            if (svc == null) {
                runOnUiThread {
                    statusTextView.text = "Service not found - check UUIDs"
                    // Re-enable controls if service not found
                    isReadingValues = false
                    dataSourceSwitch.isEnabled = true
                    volumeSeekBar.isEnabled = true
                    elevationSeekBar.isEnabled = true
                    qnhSeekBar.isEnabled = true
                }
                return
            }

            // Get all needed characteristics
            flashCharacteristic      = svc.getCharacteristic(FLASH_CHARACTERISTIC_UUID)
            testCharacteristic       = svc.getCharacteristic(TEST_CHARACTERISTIC_UUID)
            volumeCharacteristic     = svc.getCharacteristic(VOLUME_CHARACTERISTIC_UUID)
            elevationCharacteristic  = svc.getCharacteristic(ELEVATION_CHARACTERISTIC_UUID)
            qnhCharacteristic        = svc.getCharacteristic(QNH_CHARACTERISTIC_UUID)
            resetCharacteristic      = svc.getCharacteristic(RESET_CHARACTERISTIC_UUID)
            dataSourceCharacteristic = svc.getCharacteristic(DATASOURCE_CHARACTERISTIC_UUID)

            Log.d(TAG, "Chars: FLASH=${flashCharacteristic!=null}, TEST=${testCharacteristic!=null}, VOL=${volumeCharacteristic!=null}, ELEV=${elevationCharacteristic!=null}, QNH=${qnhCharacteristic!=null}, RESET=${resetCharacteristic!=null}, DS=${dataSourceCharacteristic!=null}")

            // Read initial values
            runOnUiThread {
                statusTextView.text = "Reading current values..."
            }
            readCurrentValues()
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic read failed: ${ch.uuid}, status: $status")
                characteristicsRead++
                checkAllCharacteristicsRead()
                return
            }
            val value = ch.value ?: byteArrayOf()

            runOnUiThread {
                when (ch.uuid) {
                    VOLUME_CHARACTERISTIC_UUID -> {
                        if (value.isNotEmpty()) {
                            val actual = (value[0].toInt() and 0xFF).coerceIn(0, VOLUME_ACTUAL_MAX)
                            volumeSeekBar.setProgress(convertActualToSliderVolume(actual))
                            volumeValue.text = "Volume: ${volumeSeekBar.getProgress()}/10"
                            Log.d(TAG, "Read volume: $actual/30")
                        }
                        characteristicsRead++
                    }
                    ELEVATION_CHARACTERISTIC_UUID -> {
                        // ... your existing elevation handling code ...
                        characteristicsRead++
                    }
                    QNH_CHARACTERISTIC_UUID -> {
                        // ... your existing QNH handling code ...
                        characteristicsRead++
                    }
                    DATASOURCE_CHARACTERISTIC_UUID -> {
                        // ... your existing data source handling code ...
                        characteristicsRead++
                    }
                }
                checkAllCharacteristicsRead()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            Log.d(TAG, "onCharacteristicWrite: uuid=${ch.uuid} status=$status len=${ch.value?.size ?: -1}")
        }
    }

    // ===== Commands =====
    @SuppressLint("MissingPermission")
    private fun sendVolumeCommand(value: Int) {
        val safe = value.coerceIn(0, VOLUME_ACTUAL_MAX).toByte()
        volumeCharacteristic?.value = byteArrayOf(safe)
        bluetoothGatt?.writeCharacteristic(volumeCharacteristic)
    }

    @SuppressLint("MissingPermission")
    private fun sendElevationCommand(value: Int) {
        val safe = value.coerceIn(0, 100).toByte() // slider index (×10 ft) - changed to 100
        elevationCharacteristic?.value = byteArrayOf(safe)
        bluetoothGatt?.writeCharacteristic(elevationCharacteristic)
    }

    @SuppressLint("MissingPermission")
    private fun sendQNHCommand(value: Int) {
        val safe = value.coerceIn(0, 200).toByte() // slider index (×2 hPa)
        qnhCharacteristic?.value = byteArrayOf(safe)
        bluetoothGatt?.writeCharacteristic(qnhCharacteristic)
    }

    @SuppressLint("MissingPermission")
    private fun sendTestCommand(isOn: Boolean) {
        testCharacteristic?.value = byteArrayOf(if (isOn) 0x01 else 0x00)
        bluetoothGatt?.writeCharacteristic(testCharacteristic)
        Toast.makeText(this, "Test ${if (isOn) "ON" else "OFF"}", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun sendFlashCommand() {
        flashCharacteristic?.value = byteArrayOf(0x01)
        bluetoothGatt?.writeCharacteristic(flashCharacteristic)
        Toast.makeText(this, "Flash command sent", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("MissingPermission")
    private fun sendResetCommand() {
        resetCharacteristic?.value = byteArrayOf(0x01)
        bluetoothGatt?.writeCharacteristic(resetCharacteristic)
        Toast.makeText(this, "Reset command sent", Toast.LENGTH_SHORT).show()
    }

    /**
     * Combined write:
     * Byte0: data source (0x01=SoftRF, 0x00=FLARM)
     * Byte1: baud index (0x01=38400, 0x00=19200)
     */
    @SuppressLint("MissingPermission")
    private fun sendDataSourceCommand(isSoftRF: Boolean) {
        val dsByte: Byte = if (isSoftRF) 0x01 else 0x00
        val baudIndex: Byte = if (isSoftRF) 0x01 else 0x00  // SoftRF=38400, FLARM=19200
        val payload = byteArrayOf(dsByte, baudIndex)

        dataSourceCharacteristic?.let { ch ->
            ch.value = payload
            ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val ok = bluetoothGatt?.writeCharacteristic(ch) ?: false
            Log.d(TAG, "DS+BAUD write issued=$ok (ds=0x%02X, idx=0x%02X)".format(dsByte, baudIndex))
        } ?: run {
            Log.e(TAG, "DS characteristic null — cannot write")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopBleScan()
        bluetoothGatt?.close()
    }
}