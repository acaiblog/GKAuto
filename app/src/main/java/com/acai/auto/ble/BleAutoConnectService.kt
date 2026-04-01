package com.acai.auto.ble

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.acai.auto.MyApp
import com.acai.auto.activity.MainActivity

/**
 * 经典蓝牙自动连接前台服务
 * 参考 CarConnectApp 蓝牙方案：
 * - 优先检查已配对设备直连 A2DP + HFP
 * - 未配对则扫描发现目标设备
 * - 支持自动重试，断线自动重连
 */
@SuppressLint("MissingPermission")
class BleAutoConnectService : Service() {

    companion object {
        private const val TAG = "BleAutoConnectSvc"
        private const val CHANNEL_ID = "ble_auto"
        private const val NOTIFICATION_ID = 1001
        private const val MAX_RETRY = 10
        private const val RETRY_INTERVAL_MS = 8000L

        // 广播 Action（供 MainActivity 监听更新 UI）
        const val ACTION_BT_CONNECTED    = "com.acai.auto.BT_CONNECTED"
        const val ACTION_BT_DISCONNECTED = "com.acai.auto.BT_DISCONNECTED"
        const val ACTION_BT_CONNECTING   = "com.acai.auto.BT_CONNECTING"
        const val EXTRA_DEVICE_NAME      = "device_name"
        const val EXTRA_DEVICE_ADDRESS   = "device_address"

        fun start(context: Context) {
            val intent = Intent(context, BleAutoConnectService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private val handler = Handler(Looper.getMainLooper())
    private val app get() = MyApp.getInstance()

    private var bluetoothA2dp: BluetoothA2dp? = null
    private var bluetoothHeadset: BluetoothHeadset? = null
    private var targetDevice: BluetoothDevice? = null

    private var retryCount = 0
    private var isConnected = false
    private var isDiscovering = false

    // ========== 蓝牙广播接收器 ==========

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    if (state == BluetoothAdapter.STATE_ON) {
                        log("蓝牙已开启，开始自动连接")
                        handler.postDelayed({ startConnectFlow() }, 1000)
                    } else if (state == BluetoothAdapter.STATE_OFF) {
                        log("蓝牙已关闭")
                        isConnected = false
                        broadcastStatus(ACTION_BT_DISCONNECTED)
                    }
                }
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    val name = device.name ?: return
                    log("发现设备: $name [${device.address}]")
                    if (!isConnected && isTargetDevice(device)) {
                        log("✅ 发现目标设备: $name，取消扫描并连接")
                        targetDevice = device
                        bluetoothAdapter?.cancelDiscovery()
                        connectToDevice(device)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    isDiscovering = true
                    log("蓝牙扫描已开始")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    isDiscovering = false
                    log("蓝牙扫描结束")
                    if (!isConnected && retryCount < MAX_RETRY) {
                        scheduleRetry()
                    } else if (retryCount >= MAX_RETRY) {
                        log("已达最大重试次数 ($MAX_RETRY)，停止自动连接")
                        broadcastStatus(ACTION_BT_DISCONNECTED)
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    } ?: return
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    when (bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            log("设备配对成功: ${device.name}，连接 Profile")
                            connectProfiles(device)
                        }
                        BluetoothDevice.BOND_BONDING -> log("正在配对: ${device.name}")
                        BluetoothDevice.BOND_NONE -> log("配对失败/取消: ${device.name}")
                    }
                }
                BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, BluetoothAdapter.STATE_DISCONNECTED)
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    handleConnectionStateChange(state, device)
                }
            }
        }
    }

    // ========== A2DP / HFP Profile 监听器 ==========

    private val a2dpListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            bluetoothA2dp = proxy as BluetoothA2dp
            log("A2DP Profile 已就绪")
            targetDevice?.let { connectA2dp(it) }
        }
        override fun onServiceDisconnected(profile: Int) {
            bluetoothA2dp = null
        }
    }

    private val headsetListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            bluetoothHeadset = proxy as BluetoothHeadset
            log("HFP Profile 已就绪")
            targetDevice?.let { connectHeadset(it) }
        }
        override fun onServiceDisconnected(profile: Int) {
            bluetoothHeadset = null
        }
    }

    // ========== 生命周期 ==========

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("蓝牙自动连接服务"))
        registerReceivers()

        // 获取 Profile 代理
        bluetoothAdapter?.run {
            getProfileProxy(this@BleAutoConnectService, a2dpListener, BluetoothProfile.A2DP)
            getProfileProxy(this@BleAutoConnectService, headsetListener, BluetoothProfile.HEADSET)
        }
        log("蓝牙服务已启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 每次启动都重置重试并开始连接流程
        retryCount = 0
        handler.postDelayed({ startConnectFlow() }, 500)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceivers()
        bluetoothAdapter?.run {
            if (isDiscovering) cancelDiscovery()
            bluetoothA2dp?.let { closeProfileProxy(BluetoothProfile.A2DP, it) }
            bluetoothHeadset?.let { closeProfileProxy(BluetoothProfile.HEADSET, it) }
        }
        log("蓝牙服务已停止")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ========== 连接流程 ==========

    private fun startConnectFlow() {
        if (!app.isAutoConnectEnabled()) {
            log("自动连接未开启，跳过")
            return
        }
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            log("蓝牙未开启，等待蓝牙开启后自动连接")
            return
        }
        if (isConnected) {
            log("蓝牙已连接，跳过")
            return
        }

        // 1. 优先检查已配对设备
        val savedMacs = app.getSavedDevices()
        val bonded = adapter.bondedDevices ?: emptySet()
        for (device in bonded) {
            if (savedMacs.contains(device.address) || isTargetDevice(device)) {
                log("在已配对列表中找到目标设备: ${device.name} [${device.address}]")
                targetDevice = device
                connectToDevice(device)
                return
            }
        }

        // 2. 扫描发现
        log("未在已配对设备中找到目标，开始扫描 (第 ${retryCount + 1}/$MAX_RETRY 次)")
        startDiscovery()
    }

    private fun startDiscovery() {
        val adapter = bluetoothAdapter ?: return
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        val started = adapter.startDiscovery()
        log("蓝牙扫描${if (started) "已开始" else "启动失败"}")
    }

    private fun connectToDevice(device: BluetoothDevice) {
        broadcastStatus(ACTION_BT_CONNECTING, device)
        updateNotification("正在连接: ${device.name ?: device.address}")
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                log("设备已配对，直接连接 Profile: ${device.name}")
                connectProfiles(device)
            }
            BluetoothDevice.BOND_NONE -> {
                log("设备未配对，开始配对: ${device.name}")
                device.createBond()
            }
            BluetoothDevice.BOND_BONDING -> {
                log("设备配对中: ${device.name}")
            }
        }
    }

    private fun connectProfiles(device: BluetoothDevice) {
        targetDevice = device
        bluetoothA2dp?.let { connectA2dp(device) }
        bluetoothHeadset?.let { connectHeadset(device) }
    }

    private fun connectA2dp(device: BluetoothDevice) {
        try {
            val a2dp = bluetoothA2dp ?: return
            // 设置连接策略（优先级）
            setPriorityHighest(a2dp, device)
            val method = a2dp.javaClass.getDeclaredMethod("connect", BluetoothDevice::class.java)
            method.isAccessible = true
            val result = method.invoke(a2dp, device)
            log("A2DP 连接请求: $result (${device.name})")
        } catch (e: Exception) {
            log("A2DP 连接异常: ${e.message}")
        }
    }

    private fun connectHeadset(device: BluetoothDevice) {
        try {
            val headset = bluetoothHeadset ?: return
            setPriorityHighest(headset, device)
            val method = headset.javaClass.getDeclaredMethod("connect", BluetoothDevice::class.java)
            method.isAccessible = true
            val result = method.invoke(headset, device)
            log("HFP 连接请求: $result (${device.name})")
        } catch (e: Exception) {
            log("HFP 连接异常: ${e.message}")
        }
    }

    private fun setPriorityHighest(proxy: Any, device: BluetoothDevice) {
        try {
            // Android 10 以下用 setPriority
            val m = proxy.javaClass.getDeclaredMethod("setPriority", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
            m.isAccessible = true
            m.invoke(proxy, device, 1000) // PRIORITY_AUTO_CONNECT
        } catch (_: Exception) {
            try {
                // Android 10+ 用 setConnectionPolicy
                val m = proxy.javaClass.getDeclaredMethod("setConnectionPolicy", BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(proxy, device, 100) // CONNECTION_POLICY_ALLOWED
            } catch (_: Exception) {}
        }
    }

    private fun handleConnectionStateChange(state: Int, device: BluetoothDevice?) {
        when (state) {
            BluetoothAdapter.STATE_CONNECTED -> {
                isConnected = true
                retryCount = 0
                val name = device?.name ?: "未知设备"
                log("✅ 蓝牙已连接: $name")
                updateNotification("已连接: $name")
                broadcastStatus(ACTION_BT_CONNECTED, device)
                // 连接成功后，若有绑定 APP 则启动
                app.launchBoundApp()
            }
            BluetoothAdapter.STATE_DISCONNECTED -> {
                if (isConnected) {
                    isConnected = false
                    log("蓝牙已断开，准备重连")
                    broadcastStatus(ACTION_BT_DISCONNECTED, device)
                    updateNotification("蓝牙已断开，重连中...")
                    // 断连后自动重试
                    retryCount = 0
                    handler.postDelayed({ startConnectFlow() }, 3000)
                }
            }
            BluetoothAdapter.STATE_CONNECTING -> {
                log("蓝牙连接中: ${device?.name}")
            }
        }
    }

    private fun scheduleRetry() {
        retryCount++
        if (retryCount >= MAX_RETRY) return
        log("${RETRY_INTERVAL_MS / 1000}秒后重试 ($retryCount/$MAX_RETRY)")
        updateNotification("重试中 ($retryCount/$MAX_RETRY)...")
        handler.postDelayed({ startConnectFlow() }, RETRY_INTERVAL_MS)
    }

    private fun isTargetDevice(device: BluetoothDevice): Boolean {
        val savedMacs = app.getSavedDevices()
        return savedMacs.contains(device.address)
    }

    // ========== 广播 / 通知 ==========

    private fun broadcastStatus(action: String, device: BluetoothDevice? = null) {
        val intent = Intent(action).apply {
            device?.let {
                putExtra(EXTRA_DEVICE_NAME, it.name ?: "未知")
                putExtra(EXTRA_DEVICE_ADDRESS, it.address)
            }
        }
        sendBroadcast(intent)
    }

    private fun registerReceivers() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        registerReceiver(btReceiver, filter)
    }

    private fun unregisterReceivers() {
        try { unregisterReceiver(btReceiver) } catch (_: Exception) {}
    }

    // ========== 通知 ==========

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "蓝牙连接服务", NotificationManager.IMPORTANCE_LOW)
            channel.description = "蓝牙自动连接后台服务"
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("GK Auto")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("GK Auto")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        // 广播日志给 MainActivity
        sendBroadcast(Intent("com.acai.auto.LOG").apply { putExtra("msg", msg) })
    }
}
