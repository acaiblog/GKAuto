package com.acai.auto.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.acai.auto.MyApp
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager private constructor() {

    companion object {
        private const val TAG = "BleManager"

        val UUID_SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val UUID_CHARACTERISTIC: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val UUID_SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MAX_RECONNECT_RETRIES = 3
        private val RECONNECT_DELAYS = longArrayOf(5000, 10000, 20000)
        private const val HEALTH_CHECK_MISS_THRESHOLD = 2
        private const val HEALTH_CHECK_INTERVAL = 30000L

        @Volatile
        private var instance: BleManager? = null

        fun getInstance(): BleManager {
            return instance ?: synchronized(this) {
                instance ?: BleManager().also { instance = it }
            }
        }
    }

    // ========== 环境检测 ==========
    // true = ECARX 等定制系统，标准 BluetoothAdapter 不可用（mService = null）
    @Volatile
    var isEcarxSystem = false
        private set

    private var scanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    // 经典蓝牙 RFCOMM Socket
    private var rfcommSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var readJob: Job? = null

    @Volatile
    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected

    @Volatile
    private var isGattPrimaryConnection = false

    private var onDataReceivedListener: ((ByteArray) -> Unit)? = null
    private var onConnectionStateChangedListener: ((Int) -> Unit)? = null
    private var onLogListener: ((String) -> Unit)? = null

    private var autoConnectJob: Job? = null
    @Volatile
    private var isAutoConnecting = false
    private var currentReconnectRetry = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var aclReceiver: android.content.BroadcastReceiver? = null
    private var healthCheckJob: Job? = null
    @Volatile
    private var healthCheckMissCount = 0

    fun init(context: Context) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        scanner = adapter?.bluetoothLeScanner
        // 检测 ECARX 环境：BluetoothAdapter 存在但 mService 为 null
        isEcarxSystem = detectEcarxEnvironment(adapter)
        if (isEcarxSystem) {
            log("检测到 ECARX 车机系统，使用经典蓝牙模式")
        }
        registerGlobalAclListener()
    }

    /**
     * 检测是否为 ECARX 等定制系统
     * 标准 BluetoothAdapter 存在，但 getState() 返回 STATE_OFF 且 mService 为 null
     */
    private fun detectEcarxEnvironment(adapter: BluetoothAdapter?): Boolean {
        if (adapter == null) return false
        try {
            // getState 内部如果 mService == null 会返回 STATE_OFF
            if (adapter.state == BluetoothAdapter.STATE_OFF) {
                // 进一步检查 settings 里蓝牙是否开启
                val btOn = android.provider.Settings.Global.getInt(
                    MyApp.getInstance().contentResolver, "bluetooth_on", 0
                )
                if (btOn == 1) {
                    // 系统 settings 说蓝牙开了，但 adapter 说 STATE_OFF → 定制系统
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * 注册全局 ACL 监听器，持续监听蓝牙设备的物理连接/断开
     */
    private fun registerGlobalAclListener() {
        if (aclReceiver != null) return
        aclReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        val bd = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (bd != null && bd.address == connectedDevice?.address && _isConnected) {
                            log("检测到蓝牙设备物理断开: ${bd.name} (${bd.address})")
                            _isConnected = false
                            isGattPrimaryConnection = false
                            writeCharacteristic = null
                            closeRfcommSocket()
                            stopHealthCheck()
                            onConnectionStateChangedListener?.invoke(BluetoothProfile.STATE_DISCONNECTED)
                        }
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val bd = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (bd != null && bd.address == connectedDevice?.address && !_isConnected) {
                            log("检测到蓝牙设备物理重连: ${bd.name} (${bd.address})")
                            _isConnected = true
                            onConnectionStateChangedListener?.invoke(BluetoothProfile.STATE_CONNECTED)
                        }
                    }
                }
            }
        }
        try {
            val filter = android.content.IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            MyApp.getInstance().registerReceiver(aclReceiver, filter)
        } catch (_: Exception) {}
    }

    fun setOnDataReceivedListener(listener: (ByteArray) -> Unit) { onDataReceivedListener = listener }
    fun setOnConnectionStateChangedListener(listener: (Int) -> Unit) { onConnectionStateChangedListener = listener }
    fun setOnLogListener(listener: (String) -> Unit) { onLogListener = listener }

    // ========== 已保存设备检查 ==========

    @SuppressLint("PrivateApi")
    fun checkExistingConnection(): BluetoothDevice? {
        // ECARX 系统: mService=null, 无法检查真实连接状态
        if (isEcarxSystem) return null

        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return null
        @Suppress("DEPRECATION")
        val bondedDevices = adapter.bondedDevices
        for (device in bondedDevices) {
            try {
                val method = device.javaClass.getMethod("getConnectionState")
                val state = method.invoke(device) as? Int ?: -1
                if (state == BluetoothProfile.STATE_CONNECTED) return device
            } catch (_: Exception) {}
        }
        return null
    }

    @SuppressLint("PrivateApi")
    fun restoreConnection(device: BluetoothDevice) {
        connectedDevice = device
        _isConnected = true
        startHealthCheck()
    }

    // ========== 手动输入 MAC 地址连接（ECARX 模式） ==========

    /**
     * 通过 MAC 地址直接连接设备
     * ECARX 系统上扫描不可用，用户需要手动输入 MAC 地址
     */
    fun connectByMacAddress(address: String, callback: ((Boolean) -> Unit)? = null) {
        // 校验 MAC 地址格式
        val cleanedAddress = address.replace(":", "").replace("-", "").uppercase()
        if (cleanedAddress.length != 12) {
            logE("MAC 地址格式不正确: $address")
            callback?.invoke(false)
            return
        }
        val formattedAddress = cleanedAddress.chunked(2).joinToString(":")
        log("通过 MAC 地址连接: $formattedAddress")
        connectDevice(formattedAddress, callback)
    }

    // ========== 扫描设备 ==========

    /**
     * 扫描蓝牙设备
     * - 标准 Android：BLE 扫描 + 经典蓝牙 startDiscovery + ACTION_FOUND 广播
     * - ECARX 系统：扫描不可用（mService=null），返回空列表并提示用户手动输入 MAC
     */
    fun scanDevices(duration: Long = 10000, callback: (List<BluetoothDevice>) -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            logE("蓝牙适配器不可用")
            callback(emptyList())
            return
        }

        // ECARX 系统：蓝牙扫描完全不可用，返回空列表并提示
        if (isEcarxSystem) {
            log("ECARX 系统: 蓝牙扫描不可用（Bluetooth Service not connected），请手动输入设备 MAC 地址")
            callback(emptyList())
            return
        }

        log("开始扫描蓝牙设备 (标准BLE+经典蓝牙)...")

        val deviceMap = mutableMapOf<String, Pair<BluetoothDevice, Int>>()
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        // 0. 先加载已配对的设备
        @Suppress("DEPRECATION")
        val bondedDevices = adapter.bondedDevices
        for (device in bondedDevices) {
            if (!device.name.isNullOrBlank()) {
                deviceMap[device.address] = Pair(device, 0)
                log("已配对设备: ${device.name} (${device.address})")
            }
        }

        // 1. 经典蓝牙扫描 + ACTION_FOUND 广播
        var classicReceiver: android.content.BroadcastReceiver? = null
        try {
            classicReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == BluetoothDevice.ACTION_FOUND) {
                        @Suppress("DEPRECATION")
                        val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        if (device != null && !device.name.isNullOrBlank()) {
                            synchronized(deviceMap) {
                                val existing = deviceMap[device.address]
                                if (existing == null || rssi > existing.second) {
                                    deviceMap[device.address] = Pair(device, rssi)
                                }
                            }
                            log("发现设备: ${device.name} (${device.address}) rssi=$rssi")
                        }
                    } else if (intent.action == BluetoothAdapter.ACTION_DISCOVERY_FINISHED) {
                        log("系统蓝牙扫描完成")
                    }
                }
            }
            val filter = android.content.IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            MyApp.getInstance().registerReceiver(classicReceiver, filter)
        } catch (e: Exception) {
            logE("注册扫描广播接收器失败: ${e.message}")
        }

        // 2. 启动 BLE 扫描
        val bluetoothLeScanner = scanner
        if (bluetoothLeScanner != null) {
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            val bleScanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    if (device.name.isNullOrBlank()) return
                    if (result.rssi < -90) return
                    synchronized(deviceMap) {
                        val existing = deviceMap[device.address]
                        if (existing == null || result.rssi > existing.second) {
                            deviceMap[device.address] = Pair(device, result.rssi)
                        }
                    }
                }
                override fun onScanFailed(errorCode: Int) { logE("BLE扫描失败: error=$errorCode") }
            }
            try {
                bluetoothLeScanner.startScan(null, scanSettings, bleScanCallback)
                log("BLE扫描已启动")
            } catch (e: Exception) { logE("启动BLE扫描失败: ${e.message}") }
        }

        // 3. 启动经典蓝牙扫描
        try {
            adapter.startDiscovery()
            log("经典蓝牙发现已启动")
        } catch (e: Exception) { logE("启动经典蓝牙扫描失败: ${e.message}") }

        // 4. 定时结束扫描
        scope.launch {
            delay(duration)

            mainHandler.post {
                try {
                    if (adapter.isDiscovering) adapter.cancelDiscovery()
                } catch (_: Exception) {}
                classicReceiver?.let { receiver ->
                    try { MyApp.getInstance().unregisterReceiver(receiver) } catch (_: Exception) {}
                }
            }

            delay(500)

            val sortedDevices = synchronized(deviceMap) {
                deviceMap.values
                    .sortedWith(compareByDescending<Pair<BluetoothDevice, Int>> { it.second > 0 }.thenByDescending { it.second })
                    .map { it.first }
            }

            log("扫描完成，发现 ${sortedDevices.size} 个设备")
            withContext(Dispatchers.Main) { callback(sortedDevices) }
        }
    }

    // ========== 自动连接 ==========

    fun startAutoConnect() {
        autoConnectSavedDevices()
    }

    fun autoConnectSavedDevices(callback: ((Boolean) -> Unit)? = null) {
        val savedDevices = MyApp.getInstance().getSavedDevices()
        if (savedDevices.isEmpty()) {
            log("没有已保存的设备")
            callback?.invoke(false)
            return
        }
        if (isAutoConnecting) return
        isAutoConnecting = true

        scope.launch {
            var connected = false
            for (address in savedDevices) {
                if (!isActive) break
                log("自动连接: $address")
                connected = suspendCancellableCoroutine { cont ->
                    connectDevice(address) { success -> cont.resume(success) {} }
                }
                if (connected) { log("✅ 自动连接成功: $address"); break }
                delay(2000)
            }
            isAutoConnecting = false
            if (!connected) log("所有已保存设备连接失败")
            withContext(Dispatchers.Main) { callback?.invoke(connected) }
        }
    }

    fun stopAutoConnect() {
        autoConnectJob?.cancel()
        isAutoConnecting = false
    }

    // ========== 连接设备 ==========

    /**
     * 连接蓝牙设备
     * - ECARX: 经典蓝牙 RFCOMM Socket (SPP)
     * - 标准: 先 GATT，超时回退经典蓝牙
     */
    fun connectDevice(address: String, callback: ((Boolean) -> Unit)? = null) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { logE("蓝牙适配器不可用"); callback?.invoke(false); return }

        val device = adapter.getRemoteDevice(address)
        disconnect()

        log("尝试连接: ${device.name} ($address) 配对状态=${bondStateStr(device.bondState)}")

        if (isEcarxSystem) {
            // ECARX 模式：直接用 RFCOMM Socket
            connectViaRfcomm(device, callback)
        } else if (device.bondState == BluetoothDevice.BOND_BONDED) {
            tryGattConnect(device, callback)
        } else {
            // 先配对
            pairAndConnect(device, callback)
        }
    }

    private fun tryGattConnect(device: BluetoothDevice, callback: ((Boolean) -> Unit)? = null) {
        log("GATT 连接中...")
        var timeoutJob: Job? = null

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        log("GATT 已连接")
                        timeoutJob?.cancel()
                        bluetoothGatt = gatt
                        connectedDevice = device
                        // 延迟发现服务
                        scope.launch { delay(300); gatt.discoverServices() }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        log("GATT 断开 status=$status")
                        timeoutJob?.cancel()
                        if (!_isConnected) {
                            log("GATT 失败，回退经典蓝牙...")
                            scope.launch { connectViaRfcomm(device, callback) }
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    logE("服务发现失败 status=$status，回退经典蓝牙")
                    gatt.close()
                    scope.launch { connectViaRfcomm(device, callback) }
                    return
                }
                val service = gatt.getService(UUID_SERVICE)
                if (service == null) {
                    logE("未找到 FFE0 服务，回退经典蓝牙")
                    gatt.close()
                    scope.launch { connectViaRfcomm(device, callback) }
                    return
                }
                val char = service.getCharacteristic(UUID_CHARACTERISTIC)
                if (char == null) {
                    logE("未找到 FFE1 特征，回退经典蓝牙")
                    gatt.close()
                    scope.launch { connectViaRfcomm(device, callback) }
                    return
                }
                writeCharacteristic = char
                isGattPrimaryConnection = true
                _isConnected = true
                connectedDevice = device
                startHealthCheck()
                onConnectionStateChangedListener?.invoke(BluetoothProfile.STATE_CONNECTED)
                log("GATT 服务发现成功")
                callback?.invoke(true)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                onDataReceivedListener?.invoke(characteristic.value)
            }
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) onDataReceivedListener?.invoke(characteristic.value)
            }
        }

        try {
            bluetoothGatt = device.connectGatt(MyApp.getInstance(), false, gattCallback)
        } catch (e: Exception) {
            logE("GATT 连接异常: ${e.message}")
            connectViaRfcomm(device, callback)
            return
        }

        // 5 秒超时回退
        timeoutJob = scope.launch {
            delay(5000)
            if (!_isConnected) {
                log("GATT 连接超时，回退经典蓝牙")
                try { bluetoothGatt?.disconnect(); bluetoothGatt?.close() } catch (_: Exception) {}
                bluetoothGatt = null
                connectViaRfcomm(device, callback)
            }
        }
    }

    private fun pairAndConnect(device: BluetoothDevice, callback: ((Boolean) -> Unit)? = null) {
        val bondReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)
                when (state) {
                    BluetoothDevice.BOND_BONDED -> {
                        log("配对成功")
                        try { MyApp.getInstance().unregisterReceiver(this) } catch (_: Exception) {}
                        tryGattConnect(device, callback)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        log("配对取消/失败")
                        try { MyApp.getInstance().unregisterReceiver(this) } catch (_: Exception) {}
                        callback?.invoke(false)
                    }
                }
            }
        }
        val filter = android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        MyApp.getInstance().registerReceiver(bondReceiver, filter)

        log("正在配对 ${device.name ?: device.address}...")
        try { device.createBond() } catch (e: Exception) {
            logE("配对失败: ${e.message}")
            try { MyApp.getInstance().unregisterReceiver(bondReceiver) } catch (_: Exception) {}
            callback?.invoke(false)
        }
    }

    // ========== RFCOMM 连接 ==========

    /**
     * 通过经典蓝牙 RFCOMM Socket (SPP) 连接
     */
    @SuppressLint("MissingPermission")
    private fun connectViaRfcomm(device: BluetoothDevice, callback: ((Boolean) -> Unit)? = null) {
        scope.launch {
            try {
                log("RFCOMM 连接中: ${device.name ?: device.address}...")

                // 尝试多种方式创建 RFCOMM Socket
                var socket: BluetoothSocket? = null
                var connected = false

                // 方式1: 标准 createRfcommSocketToServiceRecord (UUID SPP)
                try {
                    socket = device.createRfcommSocketToServiceRecord(UUID_SPP)
                    log("使用标准 SPP UUID 创建 Socket")
                    socket.connect()
                    connected = true
                    log("RFCOMM 连接成功 (标准方式)")
                } catch (e: Exception) {
                    log("标准 RFCOMM 失败: ${e.message}")
                    try { socket?.close() } catch (_: Exception) {}
                    socket = null
                }

                // 方式2: 端口 1
                if (!connected) {
                    try {
                        @SuppressLint("DiscouragedPrivateApi")
                        val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                        socket = method.invoke(device, 1) as BluetoothSocket
                        log("使用端口 1 创建 Socket")
                        socket.connect()
                        connected = true
                        log("RFCOMM 连接成功 (端口1)")
                    } catch (e: Exception) {
                        log("端口 1 RFCOMM 失败: ${e.message}")
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                    }
                }

                // 方式3: insecure
                if (!connected) {
                    try {
                        socket = device.createInsecureRfcommSocketToServiceRecord(UUID_SPP)
                        log("使用 Insecure SPP UUID 创建 Socket")
                        socket.connect()
                        connected = true
                        log("RFCOMM 连接成功 (insecure)")
                    } catch (e: Exception) {
                        log("Insecure RFCOMM 失败: ${e.message}")
                        try { socket?.close() } catch (_: Exception) {}
                        socket = null
                    }
                }

                if (!connected || socket == null) {
                    logE("所有 RFCOMM 连接方式均失败")
                    withContext(Dispatchers.Main) { callback?.invoke(false) }
                    return@launch
                }

                rfcommSocket = socket
                outputStream = socket.outputStream
                inputStream = socket.inputStream
                _isConnected = true
                connectedDevice = device
                isGattPrimaryConnection = false
                startHealthCheck()

                // 启动读取线程
                startRfcommReadLoop()

                withContext(Dispatchers.Main) {
                    onConnectionStateChangedListener?.invoke(BluetoothProfile.STATE_CONNECTED)
                    callback?.invoke(true)
                }

            } catch (e: Exception) {
                logE("RFCOMM 连接异常: ${e.message}")
                withContext(Dispatchers.Main) { callback?.invoke(false) }
            }
        }
    }

    /**
     * 启动 RFCOMM 读取循环
     */
    private fun startRfcommReadLoop() {
        readJob?.cancel()
        readJob = scope.launch {
            val buffer = ByteArray(1024)
            try {
                while (isActive && _isConnected) {
                    val stream = inputStream ?: break
                    val len = stream.read(buffer)
                    if (len > 0) {
                        val data = buffer.copyOfRange(0, len)
                        onDataReceivedListener?.invoke(data)
                    }
                }
            } catch (e: Exception) {
                if (_isConnected) {
                    log("RFCOMM 读取异常: ${e.message}")
                    disconnect()
                }
            }
        }
    }

    private fun closeRfcommSocket() {
        readJob?.cancel()
        readJob = null
        try { outputStream?.close() } catch (_: Exception) {}
        try { inputStream?.close() } catch (_: Exception) {}
        try { rfcommSocket?.close() } catch (_: Exception) {}
        outputStream = null
        inputStream = null
        rfcommSocket = null
    }

    // ========== 数据发送 ==========

    /**
     * 发送数据
     * - GATT 模式: 通过 BLE writeCharacteristic
     * - RFCOMM 模式: 通过 OutputStream
     */
    fun sendData(data: ByteArray): Boolean {
        return if (isGattPrimaryConnection && writeCharacteristic != null) {
            sendViaGatt(data)
        } else {
            sendViaRfcomm(data)
        }
    }

    private fun sendViaGatt(data: ByteArray): Boolean {
        val char = writeCharacteristic ?: return false
        char.value = data
        return try {
            bluetoothGatt?.writeCharacteristic(char) == true
        } catch (e: Exception) {
            logE("GATT 写入失败: ${e.message}")
            false
        }
    }

    private fun sendViaRfcomm(data: ByteArray): Boolean {
        val stream = outputStream ?: return false
        return try {
            stream.write(data)
            stream.flush()
            true
        } catch (e: Exception) {
            logE("RFCOMM 写入失败: ${e.message}")
            false
        }
    }

    // ========== 断开连接 ==========

    fun disconnect() {
        try { bluetoothGatt?.disconnect(); bluetoothGatt?.close() } catch (_: Exception) {}
        bluetoothGatt = null
        writeCharacteristic = null
        isGattPrimaryConnection = false
        closeRfcommSocket()
        _isConnected = false
        connectedDevice = null
        stopHealthCheck()
        onConnectionStateChangedListener?.invoke(BluetoothProfile.STATE_DISCONNECTED)
    }

    // ========== 健康检查 ==========

    private fun startHealthCheck() {
        stopHealthCheck()
        healthCheckMissCount = 0
        healthCheckJob = scope.launch {
            while (isActive && _isConnected) {
                delay(HEALTH_CHECK_INTERVAL)
                if (!_isConnected) break

                // 发送心跳包（如果已连接）
                val sent = if (isGattPrimaryConnection) {
                    tryGattHeartbeat()
                } else {
                    sendViaRfcomm(healthCheckData())
                }

                if (!sent) {
                    healthCheckMissCount++
                    log("健康检查: 心跳发送失败 ($healthCheckMissCount/$HEALTH_CHECK_MISS_THRESHOLD)")
                } else {
                    healthCheckMissCount = 0
                }

                if (healthCheckMissCount >= HEALTH_CHECK_MISS_THRESHOLD) {
                    log("健康检查: 连接丢失")
                    disconnect()
                    break
                }
            }
        }
    }

    private fun tryGattHeartbeat(): Boolean {
        val char = writeCharacteristic ?: return false
        try {
            bluetoothGatt?.readCharacteristic(char)
            return true
        } catch (_: Exception) { return false }
    }

    private fun healthCheckData(): ByteArray = byteArrayOf(0x00)

    private fun stopHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        healthCheckMissCount = 0
    }

    fun getConnectedDevice(): BluetoothDevice? = connectedDevice

    // ========== 工具方法 ==========

    private fun log(msg: String) {
        Log.e(TAG, msg)
        onLogListener?.invoke(msg)
    }

    private fun logW(msg: String) {
        Log.e(TAG, "⚠ $msg")
        onLogListener?.invoke("⚠ $msg")
    }

    private fun logE(msg: String) {
        Log.e(TAG, "❌ $msg")
        onLogListener?.invoke("❌ $msg")
    }

    @SuppressLint("PrivateApi")
    private fun bondStateStr(state: Int) = when (state) {
        BluetoothDevice.BOND_BONDED -> "已配对"
        BluetoothDevice.BOND_BONDING -> "配对中"
        BluetoothDevice.BOND_NONE -> "未配对"
        else -> "未知($state)"
    }
}
