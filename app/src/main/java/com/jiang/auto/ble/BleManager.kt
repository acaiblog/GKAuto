package com.jiang.auto.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jiang.auto.MyApp
import kotlinx.coroutines.*
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager private constructor() {

    companion object {
        private const val TAG = "BleManager"

        val UUID_SERVICE: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
        val UUID_CHARACTERISTIC: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
        val UUID_CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val MAX_RECONNECT_RETRIES = 3
        private val RECONNECT_DELAYS = longArrayOf(5000, 10000, 20000)
        // 健康检查连续未找到判定阈值
        private const val HEALTH_CHECK_MISS_THRESHOLD = 2
        // 健康检查间隔（毫秒）
        private const val HEALTH_CHECK_INTERVAL = 30000L

        @Volatile
        private var instance: BleManager? = null

        fun getInstance(): BleManager {
            return instance ?: synchronized(this) {
                instance ?: BleManager().also { instance = it }
            }
        }
    }

    private var scanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    @Volatile
    private var _isConnected = false
    val isConnected: Boolean get() = _isConnected

    // 标记连接方式：true=GATT 主连接，false=经典蓝牙 ACL 主连接
    // GATT 断开时，只有 GATT 主连接才重置状态；经典蓝牙连接由 ACL 监听器管理
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

    // 全局 ACL 连接状态监听器（app 生命周期内持续监听）
    private var aclReceiver: android.content.BroadcastReceiver? = null
    // 连接健康检查定时任务
    private var healthCheckJob: Job? = null
    // 健康检查连续未找到计数器（经典蓝牙 profile 未找到时需要连续多次确认）
    @Volatile
    private var healthCheckMissCount = 0

    fun init(context: Context) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        scanner = adapter?.bluetoothLeScanner
        registerGlobalAclListener()
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
                            stopHealthCheck()
                            // 注意：不清空 connectedDevice，保留用于可能的自动重连
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
            log("全局ACL监听器已注册")
        } catch (e: Exception) {
            logE("注册全局ACL监听器失败: ${e.message}")
        }
    }

    /**
     * 启动连接健康检查：每30秒检查一次已连接设备是否真的还在连接中
     * 注意：区分 GATT 连接和经典蓝牙 ACL 连接
     * - GATT 连接：通过 BluetoothManager.getConnectedDevices(GATT) 检查
     * - ACL 连接（经典蓝牙）：通过 BluetoothManager.getConnectedDevices(A2DP/HEADSET/GATT) 检查
     * - 经典蓝牙连续多次检查均未找到才判定断开，避免瞬时不稳定导致误报
     */
    private fun startHealthCheck() {
        stopHealthCheck()
        healthCheckMissCount = 0
        val intervalSec = HEALTH_CHECK_INTERVAL / 1000
        log("健康检查已启动，每 ${intervalSec} 秒检查一次连接状态")
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL)
                if (!_isConnected || connectedDevice == null) continue
                val device = connectedDevice!!
                var reallyDisconnected = false

                if (bluetoothGatt != null) {
                    // GATT 连接：检查 GATT 服务列表是否可访问
                    try {
                        val services = bluetoothGatt?.services
                        if (services.isNullOrEmpty()) {
                            logW("健康检查: GATT服务列表为空，可能已断开")
                            // 进一步验证：通过 BluetoothManager 确认
                            try {
                                val btManager = MyApp.getInstance().getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                                val gattDevices = btManager?.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)
                                if (gattDevices?.any { it.address == device.address } != true) {
                                    reallyDisconnected = true
                                }
                            } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {
                        reallyDisconnected = true
                    }
                } else {
                    // 经典蓝牙 ACL 连接：通过 BluetoothProfile proxy 检查真实连接状态
                    // 注意：BluetoothManager.getConnectedDevices(A2DP) 在普通 app 中无法获取到
                    // 必须通过 getProfileProxy 打开 A2DP/HEADSET proxy 再调用 getConnectionState
                    try {
                        var found = false
                        val device = connectedDevice!!
                        val checkedProfiles = mutableListOf<String>()

                        for (profileId in intArrayOf(
                            android.bluetooth.BluetoothProfile.A2DP,
                            android.bluetooth.BluetoothProfile.HEADSET
                        )) {
                            try {
                                val isConnected = checkProfileConnection(device, profileId)
                                val pName = profileName(profileId)
                                checkedProfiles.add(pName)
                                if (isConnected) {
                                    found = true
                                    break
                                }
                            } catch (_: Exception) {
                                checkedProfiles.add(profileName(profileId))
                            }
                        }

                        // GATT 也通过 BluetoothManager 检查
                        try {
                            val btManager = MyApp.getInstance().getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
                            val gattDevices = btManager?.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)
                            if (gattDevices?.any { it.address == device.address } == true) {
                                found = true
                                checkedProfiles.add("GATT")
                            } else {
                                if (!checkedProfiles.contains("GATT")) checkedProfiles.add("GATT")
                            }
                        } catch (_: Exception) {}

                        if (!found) {
                            healthCheckMissCount++
                            if (healthCheckMissCount >= HEALTH_CHECK_MISS_THRESHOLD) {
                                log("健康检查: 设备 ${device.name} 连续 ${healthCheckMissCount} 次在所有 Profile [${checkedProfiles.joinToString(", ")}] 中均未找到，确认断开")
                                reallyDisconnected = true
                            } else {
                                logW("健康检查: 设备 ${device.name} 在 [${checkedProfiles.joinToString(", ")}] 中未找到，第 ${healthCheckMissCount}/${HEALTH_CHECK_MISS_THRESHOLD} 次，${HEALTH_CHECK_INTERVAL/1000}秒后再次确认")
                            }
                        } else {
                            if (healthCheckMissCount > 0) {
                                log("健康检查: 设备 ${device.name} 连接正常 (${checkedProfiles.joinToString(", ")})")
                            }
                            healthCheckMissCount = 0
                        }
                    } catch (_: Exception) {}
                }

                if (reallyDisconnected) {
                    log("健康检查: 确认设备 ${device.name} 已断开连接")
                    _isConnected = false
                    connectedDevice = null
                    writeCharacteristic = null
                    bluetoothGatt = null
                    healthCheckMissCount = 0
                    stopHealthCheck()
                    withContext(Dispatchers.Main) {
                        onConnectionStateChangedListener?.invoke(BluetoothProfile.STATE_DISCONNECTED)
                    }
                }
            }
        }
    }

    /**
     * 将 profile ID 转换为可读名称
     */
    private fun profileName(profile: Int): String = when (profile) {
        android.bluetooth.BluetoothProfile.A2DP -> "A2DP"
        android.bluetooth.BluetoothProfile.HEADSET -> "HEADSET"
        android.bluetooth.BluetoothProfile.GATT -> "GATT"
        else -> "Profile#$profile"
    }

    /**
     * 通过 BluetoothProfile proxy 检查设备在指定 profile 上的真实连接状态
     * 使用同步方式等待 proxy 就绪后检查
     */
    @SuppressLint("PrivateApi")
    private suspend fun checkProfileConnection(device: BluetoothDevice, profileId: Int): Boolean {
        return try {
            kotlinx.coroutines.withTimeout(5000) {
                suspendCancellableCoroutine { cont ->
                    val serviceListener = object : android.bluetooth.BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                            try {
                                val csMethod = proxy.javaClass.getMethod(
                                    "getConnectionState", BluetoothDevice::class.java
                                )
                                val state = csMethod.invoke(proxy, device) as? Int ?: -1
                                val connected = state == android.bluetooth.BluetoothProfile.STATE_CONNECTED
                                cont.resume(connected) {}
                            } catch (e: Exception) {
                                cont.resume(false) {}
                            }
                            try {
                                BluetoothAdapter.getDefaultAdapter()
                                    ?.closeProfileProxy(profileId, proxy)
                            } catch (_: Exception) {}
                        }

                        override fun onServiceDisconnected(profile: Int) {
                            if (cont.isActive) cont.resume(false) {}
                        }
                    }

                    val opened = BluetoothAdapter.getDefaultAdapter()
                        ?.getProfileProxy(MyApp.getInstance(), serviceListener, profileId) ?: false
                    if (!opened) {
                        cont.resume(false) {}
                    }

                    cont.invokeOnCancellation {
                        try {
                        BluetoothAdapter.getDefaultAdapter()
                            ?.closeProfileProxy(profileId, null)
                    } catch (_: Exception) {}
                }
            }
            } // withTimeout
        } catch (_: Exception) {
            false
        }
    }

    private fun stopHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = null
    }

    fun setOnDataReceivedListener(listener: (ByteArray) -> Unit) {
        onDataReceivedListener = listener
    }

    fun setOnConnectionStateChangedListener(listener: (Int) -> Unit) {
        onConnectionStateChangedListener = listener
    }

    fun setOnLogListener(listener: (String) -> Unit) {
        onLogListener = listener
    }

    private fun log(msg: String) {
        Log.e(TAG, msg)  // 使用 Error 级别，vivo 会过滤 Debug 级别日志
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

    fun getConnectedDevice(): BluetoothDevice? = connectedDevice

    /**
     * 检查系统中是否有已保存的设备处于连接状态
     * 通过 BluetoothProfile proxy 检查 A2DP/HEADSET 连接状态
     * @return 已连接的设备，未找到返回 null
     */
    @SuppressLint("MissingPermission")
    fun checkExistingConnection(): BluetoothDevice? {
        val savedDevices = MyApp.getInstance().getSavedDevices()
        if (savedDevices.isEmpty()) return null

        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) return null

        // 通过 BluetoothManager 检查 GATT 连接（GATT 可以直接查）
        try {
            val btManager = MyApp.getInstance()
                .getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            for (address in savedDevices) {
                val devices = btManager?.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)
                val found = devices?.find { it.address == address }
                if (found != null) {
                    log("检测到已连接设备: ${found.name} ($address) [GATT]")
                    return found
                }
            }
        } catch (_: Exception) {}

        // A2DP/HEADSET 需要通过 proxy 检查（同步方式）
        for (address in savedDevices) {
            val device = adapter.getRemoteDevice(address)
            for (profileId in intArrayOf(
                android.bluetooth.BluetoothProfile.A2DP,
                android.bluetooth.BluetoothProfile.HEADSET
            )) {
                try {
                    // 使用 CountDownLatch 同步等待 proxy 结果
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var connected = false
                    val serviceListener = object : android.bluetooth.BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                            try {
                                val csMethod = proxy.javaClass.getMethod(
                                    "getConnectionState", BluetoothDevice::class.java
                                )
                                val state = csMethod.invoke(proxy, device) as? Int ?: -1
                                connected = state == android.bluetooth.BluetoothProfile.STATE_CONNECTED
                            } catch (_: Exception) {}
                            try {
                                BluetoothAdapter.getDefaultAdapter()
                                    ?.closeProfileProxy(profileId, proxy)
                            } catch (_: Exception) {}
                            latch.countDown()
                        }
                        override fun onServiceDisconnected(profile: Int) {
                            latch.countDown()
                        }
                    }
                    val opened = adapter.getProfileProxy(
                        MyApp.getInstance(), serviceListener, profileId
                    )
                    if (opened) {
                        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                        if (connected) {
                            log("检测到已连接设备: ${device.name} ($address) [${profileName(profileId)}]")
                            return device
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        return null
    }

    /**
     * 自动连接已保存的设备（app 启动时调用）
     * 依次尝试已保存的设备列表，找到第一个连接成功的设备即停止
     * @param callback 连接结果回调
     */
    fun autoConnectSavedDevices(callback: ((Boolean) -> Unit)? = null) {
        val savedDevices = MyApp.getInstance().getSavedDevices()
        if (savedDevices.isEmpty()) {
            callback?.invoke(false)
            return
        }

        log("尝试自动连接已保存设备: ${savedDevices.joinToString(", ")}")

        scope.launch {
            var connected = false
            for (address in savedDevices) {
                if (!isActive) break

                log("自动连接: $address")
                connected = suspendCancellableCoroutine { cont ->
                    connectDevice(address) { success ->
                        cont.resume(success) {}
                    }
                }

                if (connected) {
                    log("✅ 自动连接成功: $address")
                    break
                }

                // 等一下再试下一个
                delay(2000)
            }

            if (!connected) {
                log("所有已保存设备连接失败")
            }
            withContext(Dispatchers.Main) {
                callback?.invoke(connected)
            }
        }
    }

    /**
     * 恢复已有连接状态（app 启动时检测到设备已连接）
     * 设置内部状态并启动健康检查
     */
    fun restoreConnection(device: BluetoothDevice) {
        connectedDevice = device
        _isConnected = true
        startHealthCheck()
    }

    /**
     * 扫描蓝牙设备（已配对设备 + BLE + 经典蓝牙）
     * @param duration 扫描时长（毫秒），默认 8 秒
     * @param callback 扫描完成回调，返回去重后的设备列表
     */
    fun scanDevices(duration: Long = 8000, callback: (List<BluetoothDevice>) -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            logE("蓝牙适配器不可用或未开启")
            callback(emptyList())
            return
        }

        log("开始扫描蓝牙设备...")

        val deviceMap = mutableMapOf<String, Pair<BluetoothDevice, Int>>() // address -> (device, rssi)
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

        // 0. 先加载已配对的设备
        @Suppress("DEPRECATION")
        val bondedDevices = adapter.bondedDevices
        for (device in bondedDevices) {
            if (!device.name.isNullOrBlank()) {
                deviceMap[device.address] = Pair(device, 0) // rssi 暂时为0，扫描中会被更新
                log("已配对设备: ${device.name} (${device.address})")
            }
        }

        // 1. BLE 扫描
        val bluetoothLeScanner = scanner
        var bleScanCallback: ScanCallback? = null
        if (bluetoothLeScanner != null) {
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bleScanCallback = object : ScanCallback() {
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

                override fun onScanFailed(errorCode: Int) {
                    logE("BLE扫描失败: error=$errorCode")
                }
            }

            try {
                bluetoothLeScanner.startScan(null, scanSettings, bleScanCallback)
                log("BLE扫描已启动")
            } catch (e: Exception) {
                logE("启动BLE扫描失败: ${e.message}")
            }
        }

        // 2. 经典蓝牙扫描（必须在主线程）
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
                            log("经典蓝牙发现: ${device.name} (${device.address}) rssi=$rssi")
                        }
                    }
                }
            }
            val filter = android.content.IntentFilter(BluetoothDevice.ACTION_FOUND)
            MyApp.getInstance().registerReceiver(classicReceiver, filter)
            adapter.startDiscovery()
            log("经典蓝牙发现已启动")
        } catch (e: Exception) {
            logE("启动经典蓝牙扫描失败: ${e.message}")
        }

        // 3. 定时结束扫描并回调
        scope.launch {
            delay(duration)

            // 停止 BLE 扫描
            bleScanCallback?.let { cb ->
                try { bluetoothLeScanner?.stopScan(cb) } catch (_: Exception) {}
            }

            // 停止经典蓝牙扫描（必须在主线程）
            mainHandler.post {
                try {
                    if (adapter.isDiscovering) {
                        adapter.cancelDiscovery()
                        log("经典蓝牙发现已停止")
                    }
                } catch (_: Exception) {}
                classicReceiver?.let { receiver ->
                    try { MyApp.getInstance().unregisterReceiver(receiver) } catch (_: Exception) {}
                }
            }

            // 稍等一下确保最后一个广播已处理
            delay(500)

            // 排序：有 rssi 的按信号排前面，rssi 为 0（仅配对未扫描到）排后面
            val sortedDevices = synchronized(deviceMap) {
                deviceMap.values
                    .sortedWith(compareByDescending<Pair<BluetoothDevice, Int>> { it.second > 0 }.thenByDescending { it.second })
                    .map { it.first }
            }

            Log.d(TAG, "Scan complete, found ${sortedDevices.size} devices: ${sortedDevices.map { it.name }}")

            log("扫描完成，发现 ${sortedDevices.size} 个设备")

            withContext(Dispatchers.Main) {
                callback(sortedDevices)
            }
        }
    }

    /**
     * 连接蓝牙设备（自动判断经典蓝牙/BLE）
     * 策略：先尝试 GATT 连接（BLE），5秒无回调则切换为经典蓝牙配对方式
     * @param address 设备 MAC 地址
     * @param callback 连接结果回调
     */
    fun connectDevice(address: String, callback: ((Boolean) -> Unit)? = null) {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            logE("蓝牙适配器不可用")
            callback?.invoke(false)
            return
        }

        val device = adapter.getRemoteDevice(address)
        disconnect()

        log("尝试连接: ${device.name} (${device.address}) 配对状态=${bondStateStr(device.bondState)}")

        // 如果已经配对，先尝试 GATT 连接（适合 BLE 设备）
        // 如果未配对，直接走配对流程
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            // 已配对设备：尝试 GATT 连接
            tryGattConnect(device, callback)
        } else {
            // 未配对设备：先配对
            bondAndConnect(device, callback)
        }
    }

    private fun bondStateStr(state: Int): String = when (state) {
        BluetoothDevice.BOND_BONDED -> "已配对"
        BluetoothDevice.BOND_BONDING -> "配对中"
        BluetoothDevice.BOND_NONE -> "未配对"
        else -> "未知($state)"
    }

    /**
     * 尝试 GATT 连接（BLE 设备）
     * 5秒内无回调则认为不支持 GATT，切换到经典蓝牙方式
     */
    private fun tryGattConnect(device: BluetoothDevice, callback: ((Boolean) -> Unit)?) {
        log("尝试GATT连接 ${device.name} (5秒超时)...")
        val timeoutJob = scope.launch {
            delay(5000)
            if (!_isConnected) {
                log("GATT连接超时，切换经典蓝牙方式 ${device.address}")
                // GATT 超时，尝试经典蓝牙方式
                try {
                    bluetoothGatt?.close()
                    bluetoothGatt = null
                } catch (_: Exception) {}
                bondAndConnect(device, callback)
            }
        }

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                log("GATT状态变化: ${gatt.device.name} status=$status state=${if (newState == BluetoothProfile.STATE_CONNECTED) "已连接" else "已断开"}")
                timeoutJob.cancel()

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        _isConnected = true
                        isGattPrimaryConnection = true  // GATT 是主连接方式
                        connectedDevice = gatt.device
                        bluetoothGatt = gatt
                        log("✅ GATT连接成功: ${gatt.device.name}")
                        onConnectionStateChangedListener?.invoke(newState)
                        gatt.discoverServices()
                        startHealthCheck()

                        scope.launch {
                            delay(2000)
                            withContext(Dispatchers.Main) {
                                callback?.invoke(true)
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        val wasConnected = _isConnected
                        // 只有 GATT 主连接方式才由 GATT 断开回调管理状态
                        // 经典蓝牙连接由 ACL 监听器管理，GATT 断开不影响
                        if (isGattPrimaryConnection) {
                            _isConnected = false
                            isGattPrimaryConnection = false
                            writeCharacteristic = null
                            stopHealthCheck()
                        }
                        bluetoothGatt = null
                        log("GATT断开: ${gatt.device.name} status=$status${if (!isGattPrimaryConnection) " (非主连接，忽略)" else ""}")
                        if (wasConnected) {
                            onConnectionStateChangedListener?.invoke(newState)
                        } else {
                            // GATT 连接失败，尝试经典蓝牙
                            log("GATT连接失败，切换经典蓝牙方式 ${device.address}")
                            bondAndConnect(device, callback)
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                log("服务发现完成: status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(UUID_SERVICE)
                    if (service != null) {
                        writeCharacteristic = service.getCharacteristic(UUID_CHARACTERISTIC)
                        if (writeCharacteristic != null) {
                            log("找到写特征 FFE1")
                            val gattChar = writeCharacteristic!!
                            gatt.setCharacteristicNotification(gattChar, true)
                            val descriptor = gattChar.getDescriptor(UUID_CCCD)
                            descriptor?.let {
                                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                gatt.writeDescriptor(it)
                            }
                        } else {
                            log("未找到FFE1特征（非BLE设备）")
                        }
                    } else {
                        log("未找到FFE0服务（非BLE设备）")
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                onDataReceivedListener?.invoke(characteristic.value)
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                onDataReceivedListener?.invoke(value)
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    logE("发送命令失败: status=$status")
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                log("描述符写入: status=$status")
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.connectGatt(MyApp.getInstance(), false, gattCallback, BluetoothDevice.TRANSPORT_AUTO)
            } else {
                device.connectGatt(MyApp.getInstance(), false, gattCallback)
            }
        } catch (e: Exception) {
            logE("GATT连接异常: ${e.message}")
            timeoutJob.cancel()
            bondAndConnect(device, callback)
        }
    }

    /**
     * 经典蓝牙连接方式：配对 + 监听 ACL 连接
     * 已配对设备：通过 BluetoothProfile 检查真实连接状态
     */
    private fun bondAndConnect(device: BluetoothDevice, callback: ((Boolean) -> Unit)?) {
        val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val targetAddress = device.address

        log("经典蓝牙连接流程: ${device.name} ($targetAddress)")

        // 监听配对状态变化和 ACL 连接
        val bondReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                        val prevBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE)
                        val bd = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (bd?.address == targetAddress) {
                            log("配对状态变化: ${bondStateStr(prevBondState)} -> ${bondStateStr(bondState)}")
                            if (bondState == BluetoothDevice.BOND_BONDED) {
                                log("配对成功，等待ACL连接...")
                            } else if (bondState == BluetoothDevice.BOND_NONE && prevBondState == BluetoothDevice.BOND_BONDING) {
                                logW("配对失败或取消: $targetAddress")
                                scope.launch(Dispatchers.Main) { callback?.invoke(false) }
                            }
                        }
                    }
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val bd = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (bd?.address == targetAddress) {
                            log("✅ ACL已连接: $targetAddress")
                            _isConnected = true
                            connectedDevice = device
                            startHealthCheck()
                            onConnectionStateChangedListener?.invoke(BluetoothProfile.STATE_CONNECTED)
                            scope.launch(Dispatchers.Main) { callback?.invoke(true) }
                        }
                    }
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        val bd = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                        if (bd?.address == targetAddress) {
                            log("ACL断开: $targetAddress")
                            if (_isConnected) {
                                _isConnected = false
                                connectedDevice = null
                                stopHealthCheck()
                                onConnectionStateChangedListener?.invoke(BluetoothProfile.STATE_DISCONNECTED)
                            }
                        }
                    }
                }
            }
        }

        try {
            val filter = android.content.IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
            MyApp.getInstance().registerReceiver(bondReceiver, filter)
        } catch (e: Exception) {
            logE("注册广播接收器失败: ${e.message}")
            scope.launch(Dispatchers.Main) { callback?.invoke(false) }
            return
        }

        // 超时保护：10 秒
        scope.launch {
            delay(10000)
            if (!_isConnected) {
                logW("连接超时: $targetAddress")
                mainHandler.post {
                    try { MyApp.getInstance().unregisterReceiver(bondReceiver) } catch (_: Exception) {}
                }
                withContext(Dispatchers.Main) {
                    callback?.invoke(false)
                }
            }
        }

        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            // 已配对设备：通过 BluetoothProfile 检查真实连接状态
            log("设备已配对，检查系统蓝牙真实连接状态...")
            val btManager = MyApp.getInstance().getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            // 检查 GATT 连接状态
            try {
                btManager?.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)?.let { gattConnected ->
                    if (gattConnected.any { it.address == targetAddress }) {
                        log("✅ 设备已通过GATT连接: $targetAddress")
                        _isConnected = true
                        connectedDevice = device
                        onConnectionStateChangedListener?.invoke(BluetoothProfile.STATE_CONNECTED)
                        scope.launch(Dispatchers.Main) { callback?.invoke(true) }
                        return@bondAndConnect
                    }
                }
            } catch (_: Exception) {}

            // 检查 A2DP 连接状态（部分设备不支持，用 try-catch 保护）
            var isA2DPConnected = false
            try {
                btManager?.getConnectedDevices(android.bluetooth.BluetoothProfile.A2DP)?.let { a2dpConnected ->
                    if (a2dpConnected.any { it.address == targetAddress }) {
                        isA2DPConnected = true
                    }
                }
            } catch (_: Exception) {}

            if (isA2DPConnected) {
                log("✅ 设备已通过A2DP连接: $targetAddress")
                _isConnected = true
                connectedDevice = device
                onConnectionStateChangedListener?.invoke(BluetoothProfile.STATE_CONNECTED)
                scope.launch(Dispatchers.Main) { callback?.invoke(true) }
                return@bondAndConnect
            }

            // 未在系统中连接，尝试重新建立连接
            log("设备未在系统中连接，尝试通过A2DP建立连接...")
            scope.launch {
                // 尝试通过 A2DP profile 连接
                val connected = tryA2DPConnect(device)
                if (connected) {
                    log("✅ A2DP连接成功: ${device.name}")
                    _isConnected = true
                    connectedDevice = device
                    startHealthCheck()
                    withContext(Dispatchers.Main) {
                        onConnectionStateChangedListener?.invoke(BluetoothProfile.STATE_CONNECTED)
                        callback?.invoke(true)
                    }
                } else {
                    log("A2DP连接失败，请在系统蓝牙设置中手动连接该设备")
                    mainHandler.post {
                        try { MyApp.getInstance().unregisterReceiver(bondReceiver) } catch (_: Exception) {}
                    }
                    withContext(Dispatchers.Main) {
                        callback?.invoke(false)
                    }
                }
            }
        } else {
            // 未配对，发起配对请求
            log("发起配对请求: ${device.address}")
            try {
                device.createBond()
            } catch (e: Exception) {
                logE("配对请求失败: ${e.message}")
                mainHandler.post {
                    try { MyApp.getInstance().unregisterReceiver(bondReceiver) } catch (_: Exception) {}
                }
                scope.launch(Dispatchers.Main) { callback?.invoke(false) }
            }
        }
    }

    /**
     * 尝试通过 A2DP profile 连接经典蓝牙设备
     */
    @SuppressLint("PrivateApi")
    private suspend fun tryA2DPConnect(device: BluetoothDevice): Boolean {
        return try {
            suspendCancellableCoroutine { cont ->
                val serviceListener = object : android.bluetooth.BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: android.bluetooth.BluetoothProfile) {
                        log("A2DP服务已连接，正在连接 ${device.name}...")
                        try {
                            // 反射调用 connect
                            try {
                                val connectMethod = proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                                val connected = connectMethod.invoke(proxy, device) as? Boolean ?: false
                                log("A2DP connect() 返回: $connected")
                            } catch (_: Exception) {
                                log("A2DP connect() 反射调用失败")
                            }

                            // 反射调用 setConnectionPolicy
                            try {
                                val policyMethod = proxy.javaClass.getMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                                policyMethod.invoke(proxy, device, 100) // CONNECTION_POLICY_ALLOWED
                                log("setConnectionPolicy(100) 已调用")
                            } catch (_: Exception) {}

                            // 等待检查连接状态
                            scope.launch {
                                delay(3000)
                                val isConnectedNow = try {
                                    val csMethod = proxy.javaClass.getMethod("getConnectionState", BluetoothDevice::class.java)
                                    val state = csMethod.invoke(proxy, device) as? Int ?: -1
                                    state == android.bluetooth.BluetoothProfile.STATE_CONNECTED
                                } catch (_: Exception) { false }

                                log("A2DP连接状态检查: $isConnectedNow")
                                if (isConnectedNow) {
                                    cont.resume(true) {}
                                } else {
                                    cont.resume(false) {}
                                }
                                try {
                                    android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                                        ?.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, proxy)
                                } catch (_: Exception) {}
                            }
                        } catch (e: Exception) {
                            logE("A2DP连接异常: ${e.message}")
                            cont.resume(false) {}
                            try {
                                android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                                    ?.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, proxy)
                            } catch (_: Exception) {}
                        }
                    }

                    override fun onServiceDisconnected(profile: Int) {
                        log("A2DP服务已断开")
                        if (cont.isActive) cont.resume(false) {}
                    }
                }

                val opened = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    ?.getProfileProxy(MyApp.getInstance(), serviceListener, android.bluetooth.BluetoothProfile.A2DP) ?: false
                if (!opened) {
                    cont.resume(false) {}
                }

                cont.invokeOnCancellation {
                    try {
                        android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                            ?.closeProfileProxy(android.bluetooth.BluetoothProfile.A2DP, null)
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            logE("A2DP连接失败: ${e.message}")
            false
        }
    }

    /**
     * 断开蓝牙连接
     */
    fun disconnect() {
        log("断开蓝牙连接")
        stopHealthCheck()
        isGattPrimaryConnection = false
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: Exception) {
            logE("断开连接异常: ${e.message}")
        }
        bluetoothGatt = null
        connectedDevice = null
        writeCharacteristic = null
        _isConnected = false
    }

    /**
     * 发送数据命令
     * @param data 字节数据
     */
    fun sendCommand(data: ByteArray) {
        val characteristic = writeCharacteristic
        val gatt = bluetoothGatt
        if (gatt == null || characteristic == null) {
            logW("无法发送命令: 未连接")
            return
        }

        try {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            characteristic.value = data
            gatt.writeCharacteristic(characteristic)
            log("发送命令: ${data.toHexString()}")
        } catch (e: Exception) {
            logE("发送命令失败: ${e.message}")
        }
    }

    /**
     * 启动自动连接（绑定设备列表中的设备）
     * - 从 SharedPreferences 读取已保存的设备列表
     * - 依次尝试连接，支持退避重试
     */
    fun startAutoConnect() {
        if (_isConnected) return  // 已连接，不需要自动连接
        if (isAutoConnecting) return
        isAutoConnecting = true
        currentReconnectRetry = 0

        log("启动自动连接...")

        autoConnectJob = scope.launch {
            while (isAutoConnecting && isActive) {
                if (!_isConnected) {
                    val devices = MyApp.getInstance().getSavedDevices()
                    if (devices.isNotEmpty()) {
                        var connected = false
                        for (deviceAddress in devices) {
                            if (!isAutoConnecting || !isActive) break

                            log("自动连接: $deviceAddress (第${currentReconnectRetry + 1}次)")

                            // 使用 suspendCancellableCoroutine 等待连接结果
                            connected = suspendCancellableCoroutine { cont ->
                                connectDevice(deviceAddress) { success ->
                                    cont.resume(success) {}
                                }
                            }

                            if (connected) {
                                currentReconnectRetry = 0
                                log("✅ 自动连接成功: $deviceAddress")
                                break
                            }

                            // 等待一段时间再试下一个
                            delay(3000)
                        }

                        if (!connected) {
                            currentReconnectRetry++
                            if (currentReconnectRetry > MAX_RECONNECT_RETRIES) {
                                currentReconnectRetry = 0
                            }
                            val delayMs = RECONNECT_DELAYS[minOf(currentReconnectRetry - 1, RECONNECT_DELAYS.lastIndex)]
                            logW("自动连接失败，${delayMs/1000}秒后重试")
                            delay(delayMs)
                        }
                    } else {
                        // 没有已保存设备，等待
                        delay(15000)
                    }
                } else {
                    // 已连接，定期检查
                    delay(10000)
                }
            }
        }
    }

    /**
     * 停止自动连接
     */
    fun stopAutoConnect() {
        isAutoConnecting = false
        autoConnectJob?.cancel()
        autoConnectJob = null
        log("自动连接已停止")
    }

    private fun ByteArray.toHexString(): String {
        return joinToString("") { "%02X".format(it) }
    }
}
