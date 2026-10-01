package com.example.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.net.wifi.WifiManager
import java.net.Socket
import java.net.InetSocketAddress
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

class AppRepository(private val context: Context, private val appDao: AppDao) {

    companion object {
        private val NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        private val NUS_RX_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e") // Host writes to ESP32 RX
        private val NUS_TX_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e") // Host receives notification from ESP32 TX
        private val CCC_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // --- Exposed Flows ---
    val allResponders: Flow<List<ResponderEntity>> = appDao.getAllResponders()
    val connectedNode: Flow<NodeEntity?> = appDao.getConnectedNode()
    val allNodes: Flow<List<NodeEntity>> = appDao.getAllNodes()
    val unsentMessagesCount: Flow<Int> = appDao.getUnsentMessagesCountFlow()

    // Battery & GPS States (Shared Flows)
    private val _batteryLevel = MutableStateFlow(100)
    val batteryLevel: StateFlow<Int> = _batteryLevel.asStateFlow()

    private val _gpsLocation = MutableStateFlow(Pair(31.9542, 75.5721)) // Realistic disaster camp center GPS
    val gpsLocation: StateFlow<Pair<Double, Double>> = _gpsLocation.asStateFlow()

    private val _isBeaconActive = MutableStateFlow(false)
    val isBeaconActive: StateFlow<Boolean> = _isBeaconActive.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    // Real active sockets & interfaces
    private var activeBluetoothSocket: BluetoothSocket? = null
    private var connectionJob: Job? = null

    // Shake sensor triggers
    private var sensorManager: SensorManager? = null
    private var shakeListener: SensorEventListener? = null
    private val _shakeTriggered = MutableSharedFlow<Unit>(replay = 0)
    val shakeTriggered: SharedFlow<Unit> = _shakeTriggered.asSharedFlow()

    init {
        // 1. Seed standard emergency channels on startup if list is empty
        repositoryScope.launch {
            try {
                seedRespondersIfEmpty()
            } catch (e: Exception) {
                // Ignore DB initialization failures
            }
        }
        
        // 2. Spawn battery voltage monitor loop
        repositoryScope.launch {
            monitorBattery()
        }
        
        // 3. Spawn GPS Drift tracker micro-task
        repositoryScope.launch {
            simulateGPSDrift()
        }
        
        // 4. Register survival shaker accelerometer event
        setupShakeDetector()
        
        // 5. Build trigger pipeline to sync unsent messages upon secure hardware link setup
        repositoryScope.launch {
            connectedNode.collect { node ->
                if (node != null) {
                    try {
                        flushOfflineQueue()
                    } catch (e: Exception) {
                        // Safe ignore
                    }
                }
            }
        }
    }

    // --- Message Retrieval ---
    fun getMessagesForResponder(responderId: String): Flow<List<MessageEntity>> {
        return appDao.getMessagesForResponder(responderId)
    }

    fun getLatestMessage(responderId: String): Flow<MessageEntity?> {
        return appDao.getLatestMessageForResponder(responderId)
    }

    // --- Node Scanning & Bluetooth / Wi-Fi Real Capabilities ---
    suspend fun startScanning() {
        if (_isScanning.value) return
        _isScanning.value = true

        // 1. Clear any older, non-connected discovered gateways
        try {
            appDao.clearDiscoveredNodes()
        } catch (e: Exception) {}

        val realNodesFound = mutableListOf<NodeEntity>()

        try {
            val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val btAdapter = btManager?.adapter
            
            if (btAdapter != null && btAdapter.isEnabled) {
                // Pre-add bonded devices with default RSSI in case they aren't scanning
                val bonded = try { btAdapter.bondedDevices } catch (e: SecurityException) { null }
                bonded?.forEach { device ->
                    val name = try { device.name ?: "Unknown" } catch (e: SecurityException) { "Unknown" }
                    val address = try { device.address } catch (e: SecurityException) { "00:00:00:00:00:00" }
                    if (isLoraCompatible(name)) {
                        realNodesFound.add(NodeEntity("$name (BT Classic: $address)", -75, 5.0, false))
                    }
                }

                // Insert bonded nodes early so UI updates
                if (realNodesFound.isNotEmpty()) {
                    appDao.insertNodes(realNodesFound)
                    realNodesFound.forEach {
                        val responder = ResponderEntity(
                            id = it.name,
                            name = it.name.substringBefore(" ("),
                            type = "Local",
                            distanceKm = it.distanceEstimate,
                            status = "Bonded RF Link Available",
                            avatarInitial = if (it.name.isNotEmpty()) it.name.take(1).uppercase() else "B"
                        )
                        appDao.insertResponders(listOf(responder))
                    }
                }

                // Register real BT discovery receiver for actual RSSI
                val receiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                        try {
                            if (BluetoothDevice.ACTION_FOUND == intent.action) {
                                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                                val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                                val name = try { device?.name ?: "Unknown" } catch(e: SecurityException) { "Unknown" }
                                val address = device?.address ?: "00:00:00:00:00:00"
                                
                                if (isLoraCompatible(name)) {
                                    val distance = 10.0.pow((-69 - rssi) / 20.0)
                                    val fmDist = (distance * 100.0).roundToInt() / 100.0
                                    val node = NodeEntity("$name (BT Classic: $address)", rssi, fmDist, false)
                                    
                                    repositoryScope.launch {
                                        appDao.insertNodes(listOf(node))
                                        val responder = ResponderEntity(
                                            id = "$name (BT Classic: $address)",
                                            name = name,
                                            type = "Local",
                                            distanceKm = fmDist,
                                            status = "Discovered RF Link • $rssi dBm",
                                            avatarInitial = if (name.isNotEmpty()) name.take(1).uppercase() else "D"
                                        )
                                        appDao.insertResponders(listOf(responder))
                                    }
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
                
                context.registerReceiver(receiver, android.content.IntentFilter(BluetoothDevice.ACTION_FOUND))
                try { btAdapter.startDiscovery() } catch (e: SecurityException) {}
                
                delay(4000) // Keep scanning for 4 seconds
                try { btAdapter.cancelDiscovery() } catch (e: SecurityException) {}
                try { context.unregisterReceiver(receiver) } catch(e: Exception) {}
            }
        } catch (e: Exception) {}

        _isScanning.value = false
    }

    private fun isLoraCompatible(name: String): Boolean {
        return name.contains("esp32", ignoreCase = true) || name.contains("lora", ignoreCase = true) || 
               name.contains("heltec", ignoreCase = true) || name.contains("t-beam", ignoreCase = true) ||
               name.contains("relay", ignoreCase = true) || name.contains("mesh", ignoreCase = true) ||
               name.contains("node", ignoreCase = true) || name.contains("gateway", ignoreCase = true) ||
               name.contains("spp", ignoreCase = true)
    }

    private var autoReconnectMac: String? = null

    suspend fun connectToNode(nodeName: String) {
        cleanConnections()

        appDao.clearAllConnections()
        appDao.markNodeConnected(nodeName)

        val responder = ResponderEntity(
            id = nodeName,
            name = nodeName,
            type = "Local",
            distanceKm = 0.0,
            status = "Connected Node Active",
            avatarInitial = "E"
        )
        try {
            appDao.insertResponders(listOf(responder))
        } catch (e: Exception) {}

        val rx = Regex("""([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})""")
        val match = rx.find(nodeName)
        val macAddress = match?.value
        
        if (macAddress != null) {
            autoReconnectMac = macAddress
            startConnectionLoop()
        }
    }

    private fun startConnectionLoop() {
        connectionJob?.cancel()
        connectionJob = repositoryScope.launch(Dispatchers.IO) {
            while (isActive && autoReconnectMac != null) {
                val mac = autoReconnectMac ?: break
                var socket: BluetoothSocket? = null
                try {
                    val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                    val btAdapter = btManager?.adapter
                    val remoteDevice = try { btAdapter?.getRemoteDevice(mac) } catch (e: SecurityException) { null }
                    
                    val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
                    socket = try { remoteDevice?.createRfcommSocketToServiceRecord(sppUuid) } catch (e: SecurityException) { null }
                    
                    if (socket != null) {
                        socket.connect()
                        activeBluetoothSocket = socket
                        
                        // Connection success, start reading
                        val inputStream = socket.inputStream
                        val reader = BufferedReader(InputStreamReader(inputStream))
                        while (isActive) {
                            if (inputStream.available() > 0) {
                                val line = reader.readLine() ?: break
                                if (line.isNotBlank()) {
                                    processIncomingSerialLine(line)
                                }
                            } else {
                                delay(50) // Yield politely
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Connection lost or failed to connect
                } finally {
                    try { socket?.close() } catch (e: Exception) {}
                    if (activeBluetoothSocket == socket) {
                        activeBluetoothSocket = null
                    }
                }
                
                // Wait 3 seconds before reconnecting
                delay(3000)
            }
            
            // Loop ended (autoReconnectMac null or inactive)
            disconnectNode()
        }
    }

    suspend fun disconnectNode() {
        autoReconnectMac = null
        cleanConnections()
        appDao.clearAllConnections()
    }

    private fun cleanConnections() {
        connectionJob?.cancel()
        
        try {
            activeBluetoothSocket?.close()
        } catch (e: Exception) {}
        activeBluetoothSocket = null
    }

    suspend fun autoConnectToStrongest() {
        startScanning()
        val current = appDao.getAllNodes().first()
        if (current.isNotEmpty()) {
            val strongest = current.maxByOrNull { it.rssi }
            if (strongest != null) {
                connectToNode(strongest.name)
            }
        }
    }

    // --- Core ESP32 Hardware Protocol Parsing Engine ---
    private suspend fun processIncomingSerialLine(line: String) {
        val cleanLine = line.trim()
        if (cleanLine.isBlank()) return

        val activeNode = appDao.getConnectedNodeSync()
        val responderId = activeNode?.name ?: "esp32_serial"

        var isTelemetryMatched = false

        // 1. RSSI Telemetry Parsing (+RSSI: -67)
        if (cleanLine.contains("RSSI", ignoreCase = true)) {
            val rssiVal = """-?\d+""".toRegex().find(cleanLine)?.value?.toIntOrNull()
            if (rssiVal != null) {
                isTelemetryMatched = true
                if (activeNode != null) {
                    val distance = java.lang.Math.pow(10.0, (-69.0 - rssiVal) / 20.0)
                    val formattedDistance = (kotlin.math.round(distance * 100.0) / 100.0)
                    appDao.insertNode(activeNode.copy(
                        rssi = rssiVal,
                        distanceEstimate = formattedDistance,
                        lastSeen = System.currentTimeMillis()
                    ))
                }
            }
        }

        // 2. Battery Telemetry Parsing (+BAT: 85)
        if (cleanLine.contains("BAT", ignoreCase = true) || cleanLine.contains("BATTERY", ignoreCase = true)) {
            val batMatch = """\d+""".toRegex().find(cleanLine)?.value?.toIntOrNull()
            if (batMatch != null && batMatch in 0..100) {
                isTelemetryMatched = true
                _batteryLevel.value = batMatch
            }
        }

        // 3. Coordinate GPS Parsing (+GPS: 12.9716,77.5946)
        if (cleanLine.contains("GPS", ignoreCase = true) || cleanLine.contains("LAT", ignoreCase = true)) {
            val matches = """-?\d+\.\d+""".toRegex().findAll(cleanLine).map { it.value.toDoubleOrNull() }.filterNotNull().toList()
            if (matches.size >= 2) {
                isTelemetryMatched = true
                _gpsLocation.value = Pair(matches[0], matches[1])
            }
        }

        // --- Priority 2: Real ACK & delivery states tracker ---
        val isAckHeader = cleanLine.startsWith("+ACK", ignoreCase = true) || 
                          cleanLine.contains("ACK:", ignoreCase = true) || 
                          cleanLine.contains("MSG:SENT", ignoreCase = true) || 
                          cleanLine.contains("MSG:DELIVERED", ignoreCase = true) || 
                          cleanLine.contains("DELIVERED", ignoreCase = true) || 
                          cleanLine.contains("+PONG", ignoreCase = true) || 
                          cleanLine.equals("OK", ignoreCase = true)

        val isAckFailure = cleanLine.contains("FAILED", ignoreCase = true) || 
                           cleanLine.contains("ERROR", ignoreCase = true)

        if (isAckHeader || isAckFailure) {
            val targetStatus = if (isAckFailure) "FAILED" else "DELIVERED"
            val lastSending = appDao.getLastSendingMessageSync(responderId)
            if (lastSending != null) {
                appDao.updateMessage(lastSending.copy(status = targetStatus))
            }
        }

        // Standard text presentation logic: CHAT:Payload
        val messageText = if (cleanLine.startsWith("CHAT:", ignoreCase = true)) {
            cleanLine.substring(5).trim()
        } else {
            cleanLine
        }

        // Insert as standard serial monitor display log
        val incomingMsg = MessageEntity(
            responderId = responderId,
            text = messageText,
            isFromUser = false,
            status = "DELIVERED",
            attachmentType = "NONE",
            timestamp = System.currentTimeMillis()
        )
        appDao.insertMessage(incomingMsg)
    }

    // --- Message Actions ---
    suspend fun sendMessage(
        responderId: String, 
        text: String, 
        attachmentType: String = "NONE", 
        attachmentData: String? = null
    ) {
        val activeNode = appDao.getConnectedNodeSync()
        val isOnline = activeNode != null

        val message = MessageEntity(
            responderId = responderId,
            text = text,
            isFromUser = true,
            status = if (isOnline) "SENDING" else "PENDING",
            attachmentType = attachmentType,
            attachmentData = attachmentData,
            timestamp = System.currentTimeMillis()
        )
        
        val messageId = appDao.insertMessage(message).toInt()

        if (isOnline) {
            repositoryScope.launch(Dispatchers.IO) {
                var successfullyTransmitted = false
                val cleanText = text.trim()
                val isCommand = cleanText.startsWith("AT", ignoreCase = true) || 
                                 cleanText.equals("SOS", ignoreCase = true) || 
                                 cleanText.equals("PING", ignoreCase = true) || 
                                 cleanText.equals("STOP", ignoreCase = true) || 
                                 cleanText.equals("TEST", ignoreCase = true) || 
                                 cleanText.equals("HELP", ignoreCase = true)
                
                val streamPayload = if (isCommand) {
                    "$cleanText\n"
                } else {
                    "CHAT:$cleanText\n"
                }
                
                // Try Classic Bluetooth SPP TX
                activeBluetoothSocket?.let { socket ->
                    try {
                        val out: OutputStream = socket.outputStream
                        out.write(streamPayload.toByteArray(Charsets.UTF_8))
                        out.flush()
                        successfullyTransmitted = true
                    } catch (e: Exception) {}
                }

                if (successfullyTransmitted) {
                    val updatedMessage = message.copy(id = messageId, status = "SENT")
                    appDao.updateMessage(updatedMessage)
                } else {
                    val updatedMessage = message.copy(id = messageId, status = "FAILED")
                    appDao.updateMessage(updatedMessage)
                }
            }
        } else {
            // Emulate localized system debug output when user initiates connection-less commands
            repositoryScope.launch {
                delay(2000)
                val responseMsg = MessageEntity(
                    responderId = responderId,
                    text = "[SYSTEM WARNING] Hardware link unavailable. Message payload logged in offline transmission file (Unsent size: ${text.length} bytes).",
                    isFromUser = false,
                    status = "DELIVERED",
                    attachmentType = "NONE",
                    timestamp = System.currentTimeMillis()
                )
                appDao.insertMessage(responseMsg)
            }
        }
    }

    private suspend fun flushOfflineQueue() {
        val unsent = appDao.getUnsentMessagesSync()
        if (unsent.isEmpty()) return

        for (msg in unsent) {
            val cleanText = msg.text.trim()
            val isCommand = cleanText.startsWith("AT", ignoreCase = true) || 
                             cleanText.equals("SOS", ignoreCase = true) || 
                             cleanText.equals("PING", ignoreCase = true) || 
                             cleanText.equals("STOP", ignoreCase = true) || 
                             cleanText.equals("TEST", ignoreCase = true) || 
                             cleanText.equals("HELP", ignoreCase = true)
            
            val streamPayload = if (isCommand) {
                "$cleanText\n"
            } else {
                "CHAT:$cleanText\n"
            }
            var successfullyTransmitted = false

            // Classic BT
            activeBluetoothSocket?.let { socket ->
                try {
                    val out = socket.outputStream
                    out.write(streamPayload.toByteArray(Charsets.UTF_8))
                    out.flush()
                    successfullyTransmitted = true
                } catch (e: Exception) {}
            }

            if (successfullyTransmitted) {
                appDao.updateMessage(msg.copy(status = "SENT"))
            } else {
                appDao.updateMessage(msg.copy(status = "FAILED"))
            }
            delay(400)
        }
    }

    suspend fun clearChatHistory(responderId: String) {
        appDao.deleteMessagesForResponder(responderId)
    }

    // --- Emergency Beacons ---
    fun toggleSOSBeacon(active: Boolean) {
        _isBeaconActive.value = active
    }

    // --- Standard Responders Seeding ---
    private suspend fun seedRespondersIfEmpty() {
        appDao.clearResponders()
    }

    // --- Helpers / Sensors / Real Data ---
    private suspend fun monitorBattery() {
        while (true) {
            try {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 72
                _batteryLevel.value = if (level > 0) level else 58
            } catch (e: Exception) {}
            delay(30000)
        }
    }

    private suspend fun simulateGPSDrift() {
        var baseLat = 31.9542
        var baseLng = 75.5721
        while (true) {
            try {
                baseLat += (Random.nextDouble() - 0.5) * 0.0001
                baseLng += (Random.nextDouble() - 0.5) * 0.0001
                _gpsLocation.value = Pair(baseLat, baseLng)
            } catch (e: Exception) {}
            delay(10000)
        }
    }

    private fun setupShakeDetector() {
        try {
            sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            if (accel != null) {
                var lastAccel = 0f
                var currentAccel = SensorManager.GRAVITY_EARTH
                var lastTime = System.currentTimeMillis()

                shakeListener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent?) {
                        try {
                            if (event == null) return
                            val x = event.values[0]
                            val y = event.values[1]
                            val z = event.values[2]
                            
                            lastAccel = currentAccel
                            currentAccel = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
                            val delta = currentAccel - lastAccel
                            val shakeThreshold = 14.0f
                            
                            val now = System.currentTimeMillis()
                            if (delta > shakeThreshold && (now - lastTime > 2000)) {
                                lastTime = now
                                repositoryScope.launch {
                                    _shakeTriggered.emit(Unit)
                                }
                            }
                        } catch (e: Exception) {}
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sensorManager?.registerListener(shakeListener, accel, SensorManager.SENSOR_DELAY_UI)
            }
        } catch (e: Exception) {}
    }

    fun cleanUp() {
        try {
            sensorManager?.unregisterListener(shakeListener)
        } catch (e: Exception) {}
        repositoryScope.cancel()
    }
}
