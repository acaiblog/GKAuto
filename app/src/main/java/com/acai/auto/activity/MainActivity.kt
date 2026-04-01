package com.acai.auto.activity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.acai.auto.MyApp
import com.acai.auto.R
import com.acai.auto.adapter.DeviceAdapter
import com.acai.auto.adapter.LogAdapter
import com.acai.auto.ble.BleAutoConnectService

/**
 * 主界面 - 绑定应用
 * 蓝牙绑定 + WiFi热点管理 + 自动连接 + 开机自启
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var logAdapter: LogAdapter
    private var bluetoothAdapter: BluetoothAdapter? = null
    private val scanResults = mutableListOf<BluetoothDevice>()
    private var isScanning = false
    private var isBtConnected = false
    private val handler = Handler(Looper.getMainLooper())
    // 保存已配对的设备（用于快速绑定）
    private val bondedDevices = mutableListOf<BluetoothDevice>()

    // 日志 RecyclerView 引用
    private var logRecyclerView: androidx.recyclerview.widget.RecyclerView? = null

    // 按钮引用（供广播接收器使用）
    private var btnScan: android.widget.Button? = null

    // 标记是否在等待权限后重试扫描
    private var pendingScanAfterPermission = false

    // 监听 BleAutoConnectService 广播，更新 UI 连接状态
    private val btStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    // 蓝牙开关状态变化时，实时更新 UI
                    when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)) {
                        BluetoothAdapter.STATE_ON -> {
                            updateBleStatus("蓝牙已就绪", Color.parseColor("#4CAF50"))
                            appendLog("[蓝牙] 蓝牙已开启")
                        }
                        BluetoothAdapter.STATE_OFF -> {
                            updateBleStatus("蓝牙未开启", Color.RED)
                            appendLog("[蓝牙] 蓝牙已关闭")
                        }
                        BluetoothAdapter.STATE_TURNING_ON -> {
                            updateBleStatus("蓝牙开启中...", Color.parseColor("#FF9800"))
                        }
                        BluetoothAdapter.STATE_TURNING_OFF -> {
                            updateBleStatus("蓝牙关闭中...", Color.parseColor("#FF9800"))
                        }
                    }
                }
                BleAutoConnectService.ACTION_BT_CONNECTED -> {
                    val name = intent.getStringExtra(BleAutoConnectService.EXTRA_DEVICE_NAME) ?: "未知"
                    val addr = intent.getStringExtra(BleAutoConnectService.EXTRA_DEVICE_ADDRESS) ?: ""
                    isBtConnected = true
                    updateBleStatus("已连接: $name", Color.parseColor("#4CAF50"))
                    if (addr.isNotEmpty()) {
                        MyApp.getInstance().saveDevice(addr)
                        updateBoundDeviceInfo()
                    }
                    launchBoundAppOnConnect()
                }
                BleAutoConnectService.ACTION_BT_DISCONNECTED -> {
                    isBtConnected = false
                    updateBleStatus("蓝牙未连接", Color.RED)
                }
                BleAutoConnectService.ACTION_BT_CONNECTING -> {
                    val name = intent.getStringExtra(BleAutoConnectService.EXTRA_DEVICE_NAME) ?: ""
                    updateBleStatus("正在连接${if (name.isNotEmpty()) ": $name" else "..."}", Color.BLUE)
                }
                "com.acai.auto.LOG" -> {
                    val msg = intent.getStringExtra("msg") ?: return
                    appendLog(msg)
                }
                BluetoothDevice.ACTION_FOUND -> {
                    // 发现蓝牙设备
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE)
                    if (device != null) {
                        val name = device.name ?: "未知设备"
                        val address = device.address
                        appendLog("[蓝牙] 发现设备: $name [$address] RSSI: $rssi")
                        if (scanResults.none { it.address == device.address }) {
                            scanResults.add(device)
                            deviceAdapter.notifyDataSetChanged()
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    appendLog("[蓝牙] 扫描已启动")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    appendLog("[蓝牙] 扫描完成，发现 ${scanResults.size} 个设备")
                    isScanning = false
                    runOnUiThread {
                        btnScan?.text = "扫描设备"
                    }
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            initBle()
            // 如果之前在等待权限后重试扫描，现在执行
            if (pendingScanAfterPermission) {
                pendingScanAfterPermission = false
                performBluetoothScan()
            }
        } else {
            Toast.makeText(this, "需要蓝牙和位置权限才能使用", Toast.LENGTH_LONG).show()
            pendingScanAfterPermission = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()

        setupLogPanel()
        setupRecyclerView()
        setupScanButton()
        setupAutoConnectSwitch()
        setupBootStartSwitch()
        setupAppBinding()
        setupHotspot()
        setupQuickActions()
        registerBtReceiver()
        checkPermissions()
        updateBoundDeviceInfo()
        updateBoundAppInfo()
        updateVersionInfo()
    }

    private fun registerBtReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BleAutoConnectService.ACTION_BT_CONNECTED)
            addAction(BleAutoConnectService.ACTION_BT_DISCONNECTED)
            addAction(BleAutoConnectService.ACTION_BT_CONNECTING)
            addAction("com.acai.auto.LOG")
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(btStatusReceiver, filter)
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        } else {
            initBle()
        }
    }

    private fun initBle() {
        // 每次调用时重新获取蓝牙适配器状态
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()

        if (bluetoothAdapter == null) {
            updateBleStatus("蓝牙不可用", Color.RED)
            appendLog("[蓝牙] 未检测到蓝牙模块")
            return
        }

        // 使用多种方式检测蓝牙状态：优先使用 settings 值（更可靠）
        val adapter = bluetoothAdapter!!
        val isEnabledByAdapter = adapter.isEnabled
        val isEnabledBySettings = isBluetoothEnabledBySettings()

        appendLog("[蓝牙] 状态检测: adapter=$isEnabledByAdapter, settings=$isEnabledBySettings")

        // 任一方式显示开启即认为已开启
        if (!isEnabledByAdapter && !isEnabledBySettings) {
            updateBleStatus("蓝牙未开启", Color.parseColor("#FF9800"))
            appendLog("[蓝牙] 蓝牙未开启，请先开启蓝牙")
        } else {
            updateBleStatus("蓝牙已就绪", Color.parseColor("#4CAF50"))
            appendLog("[蓝牙] 蓝牙已就绪")
            // 刷新已配对设备列表
            refreshBondedDevices()
        }

        // 如果自动连接开启且有已保存设备，启动服务
        if (MyApp.getInstance().isAutoConnectEnabled() && MyApp.getInstance().getSavedDevices().isNotEmpty()) {
            BleAutoConnectService.start(this)
        }
    }

    /**
     * 通过系统 settings 检查蓝牙状态（更可靠）
     */
    @SuppressLint("WrongConstant")
    private fun isBluetoothEnabledBySettings(): Boolean {
        return try {
            val result = android.provider.Settings.System.getInt(
                contentResolver,
                android.provider.Settings.System.BLUETOOTH_ON,
                0
            )
            result != 0
        } catch (e: Exception) {
            try {
                val result = android.provider.Settings.Global.getInt(
                    contentResolver,
                    "bluetooth_on",
                    0
                )
                result != 0
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * 刷新已配对设备列表
     */
    private fun refreshBondedDevices() {
        val adapter = bluetoothAdapter ?: return
        bondedDevices.clear()
        adapter.bondedDevices?.let { bonded ->
            bondedDevices.addAll(bonded)
            appendLog("[蓝牙] 已配对设备: ${bonded.size} 个")
        }
    }

    private fun updateBleStatus(text: String, color: Int) {
        runOnUiThread {
            findViewById<TextView>(R.id.tvBleStatus)?.apply {
                this.text = text
                setTextColor(color)
            }
        }
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(scanResults) { device ->
            val address = device.address
            val name = device.name ?: address
            appendLog("[蓝牙] 选中设备: $name [$address]，已保存并启动自动连接")
            MyApp.getInstance().saveDevice(address)
            updateBoundDeviceInfo()
            deviceAdapter.notifyDataSetChanged()
            // 启动/重启自动连接服务
            BleAutoConnectService.start(this)
            Toast.makeText(this, "已保存设备，正在连接: $name", Toast.LENGTH_SHORT).show()
        }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvDevices)?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }
    }

    private fun setupScanButton() {
        btnScan = findViewById<android.widget.Button>(R.id.btnScan)
        val btnBindBluetooth = findViewById<android.widget.Button>(R.id.btnBindBluetooth)

        // 扫描广播已在 registerBtReceiver 中注册 (ACTION_FOUND, ACTION_DISCOVERY_FINISHED)

        btnScan?.setOnClickListener {
            if (isBtConnected) {
                // 停止服务断开连接
                stopService(Intent(this, BleAutoConnectService::class.java))
                isBtConnected = false
                updateBleStatus("蓝牙未连接", Color.RED)
                appendLog("[蓝牙] 已断开连接")
                return@setOnClickListener
            }

            if (isScanning) {
                bluetoothAdapter?.cancelDiscovery()
                isScanning = false
                btnScan?.text = "扫描设备"
                appendLog("[蓝牙] 用户停止扫描")
                return@setOnClickListener
            }

            performBluetoothScan()
        }

        // 绑定蓝牙按钮：直接从已配对设备列表中选择绑定
        btnBindBluetooth?.setOnClickListener {
            // 重新获取蓝牙适配器
            val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothAdapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()

            val adapter = bluetoothAdapter
            if (adapter == null) {
                Toast.makeText(this, "未检测到蓝牙模块", Toast.LENGTH_SHORT).show()
                appendLog("[蓝牙] 未检测到蓝牙模块")
                return@setOnClickListener
            }

            // 使用双重检测
            val isEnabledByAdapter = adapter.isEnabled
            val isEnabledBySettings = isBluetoothEnabledBySettings()

            if (!isEnabledByAdapter && !isEnabledBySettings) {
                Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
                appendLog("[蓝牙] 检测到蓝牙未开启，请先开启蓝牙")
                return@setOnClickListener
            }

            // 获取已配对设备
            val bonded = adapter.bondedDevices?.toList() ?: emptyList()
            if (bonded.isEmpty()) {
                Toast.makeText(this, "没有已配对的设备，请先扫描并连接", Toast.LENGTH_SHORT).show()
                appendLog("[蓝牙] 没有已配对设备，请先扫描")
                return@setOnClickListener
            }

            // 显示已配对设备选择对话框
            showBondedDevicePicker(bonded)
        }
    }

    /**
     * 执行蓝牙扫描（提取为独立方法，便于权限授予后重试）
     */
    private fun performBluetoothScan() {
        // 每次点击时重新获取蓝牙适配器，确保状态最新
        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()

        val adapter = bluetoothAdapter
        if (adapter == null) {
            Toast.makeText(this, "未检测到蓝牙模块", Toast.LENGTH_SHORT).show()
            appendLog("[蓝牙] 未检测到蓝牙模块")
            return
        }

        // Android 5.1 需要位置权限才能扫描蓝牙
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
                appendLog("[蓝牙] Android 5.1 需要位置权限，正在请求...")
                pendingScanAfterPermission = true
                permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
                return
            }
        }

        // 使用双重检测：adapter.isEnabled + settings值
        val isEnabledByAdapter = adapter.isEnabled
        val isEnabledBySettings = isBluetoothEnabledBySettings()
        appendLog("[蓝牙] 扫描前状态: adapter=$isEnabledByAdapter, settings=$isEnabledBySettings")
        appendLog("[蓝牙] API版本: ${Build.VERSION.SDK_INT}, 设备名称: ${adapter.name}")

        if (!isEnabledByAdapter && !isEnabledBySettings) {
            Toast.makeText(this, "请先开启蓝牙", Toast.LENGTH_SHORT).show()
            appendLog("[蓝牙] 检测到蓝牙未开启，请先开启蓝牙")
            return
        }

        scanResults.clear()
        deviceAdapter.notifyDataSetChanged()
        isScanning = true
        btnScan?.text = "停止扫描"
        appendLog("[蓝牙] 开始扫描设备...")

        // 先添加已配对设备
        val bonded = adapter.bondedDevices
        if (bonded != null) {
            for (dev in bonded) {
                if (scanResults.none { it.address == dev.address }) {
                    scanResults.add(dev)
                }
            }
            deviceAdapter.notifyDataSetChanged()
            appendLog("[蓝牙] 已配对设备: ${bonded.size} 个")
            bonded.forEach { dev ->
                appendLog("[蓝牙]   - ${dev.name ?: "未知"} [${dev.address}]")
            }
        }

        // 检查扫描是否正在进行，如果是，先取消
        if (adapter.isDiscovering) {
            appendLog("[蓝牙] 正在取消之前的扫描...")
            adapter.cancelDiscovery()
        }

        val started = adapter.startDiscovery()
        appendLog("[蓝牙] startDiscovery() 返回: $started")

        if (!started) {
            appendLog("[蓝牙] 启动扫描失败，尝试强制启用蓝牙...")
            // 尝试通过 Settings 强制开启蓝牙
            try {
                val success = android.provider.Settings.Global.putInt(
                    contentResolver,
                    "bluetooth_on",
                    1
                )
                appendLog("[蓝牙] 强制开启蓝牙设置: $success")
                if (success) {
                    // 等待1秒后重试
                    handler.postDelayed({
                        val retryStarted = adapter.startDiscovery()
                        appendLog("[蓝牙] 重试 startDiscovery() 返回: $retryStarted")
                        if (retryStarted) {
                            startScanTimeout(adapter)
                            return@postDelayed
                        }
                        // 还是失败，使用反射强制开启
                        forceEnableBluetooth()
                    }, 1000)
                    return
                }
            } catch (e: Exception) {
                appendLog("[蓝牙] 强制开启蓝牙失败: ${e.message}")
            }

            appendLog("[蓝牙] 启动扫描失败，请检查蓝牙是否正常工作")
            isScanning = false
            btnScan?.text = "扫描设备"
            Toast.makeText(this, "启动扫描失败，请在系统设置中开启蓝牙", Toast.LENGTH_LONG).show()
            return
        }

        startScanTimeout(adapter)
    }

    /**
     * 反射强制开启蓝牙（作为最后手段）
     */
    private fun forceEnableBluetooth() {
        appendLog("[蓝牙] 尝试反射强制开启蓝牙...")
        try {
            val adapter = bluetoothAdapter ?: return
            val method = adapter.javaClass.getMethod("enable")
            method.invoke(adapter)
            appendLog("[蓝牙] 反射调用 enable() 成功")

            // 等待2秒后重试扫描
            handler.postDelayed({
                val retryStarted = adapter.startDiscovery()
                appendLog("[蓝牙] 反射后 startDiscovery() 返回: $retryStarted")
                if (retryStarted) {
                    startScanTimeout(adapter)
                } else {
                    isScanning = false
                    btnScan?.text = "扫描设备"
                    appendLog("[蓝牙] 所有方法均失败，建议手动开启蓝牙")
                    Toast.makeText(this, "请在系统设置中开启蓝牙后重试", Toast.LENGTH_LONG).show()
                }
            }, 2000)
        } catch (e: Exception) {
            appendLog("[蓝牙] 反射强制开启蓝牙失败: ${e.message}")
            isScanning = false
            btnScan?.text = "扫描设备"
        }
    }

    /**
     * 启动扫描超时定时器
     */
    private fun startScanTimeout(adapter: BluetoothAdapter) {
        handler.postDelayed({
            if (isScanning) {
                appendLog("[蓝牙] 扫描超时，自动停止")
                try {
                    adapter.cancelDiscovery()
                } catch (_: Exception) {}
                isScanning = false
                btnScan?.text = "扫描设备"
            }
        }, 12000)
    }

    /**
     * 显示已配对设备选择对话框
     */
    private fun showBondedDevicePicker(devices: List<BluetoothDevice>) {
        if (devices.isEmpty()) return

        val deviceNames = devices.map { it.name ?: it.address }.toTypedArray()
        val deviceAddresses = devices.map { it.address }

        android.app.AlertDialog.Builder(this)
            .setTitle("选择要绑定的蓝牙设备")
            .setItems(deviceNames) { _, which ->
                val address = deviceAddresses[which]
                val name = deviceNames[which]
                // 保存设备并启动自动连接
                MyApp.getInstance().saveDevice(address)
                appendLog("[蓝牙] 已绑定设备: $name [$address]")
                Toast.makeText(this, "已绑定: $name，正在连接...", Toast.LENGTH_SHORT).show()
                updateBoundDeviceInfo()

                // 启用自动连接并启动服务
                MyApp.getInstance().setAutoConnectEnabled(true)
                findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swAutoConnect)?.isChecked = true
                BleAutoConnectService.start(this)
                appendLog("[蓝牙] 已启动自动连接服务")
            }
            .setNegativeButton("取消", null)
            .show()
    }
    private fun setupAutoConnectSwitch() {
        val sw = findViewById<SwitchCompat>(R.id.swAutoConnect)
        sw?.apply {
            setOnCheckedChangeListener(null)
            isChecked = MyApp.getInstance().isAutoConnectEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                MyApp.getInstance().setAutoConnectEnabled(isChecked)
                if (isChecked) {
                    BleAutoConnectService.start(this@MainActivity)
                    Toast.makeText(this@MainActivity, "已开启自动连接", Toast.LENGTH_SHORT).show()
                } else {
                    stopService(Intent(this@MainActivity, BleAutoConnectService::class.java))
                    Toast.makeText(this@MainActivity, "已关闭自动连接", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    private fun setupBootStartSwitch() {
        val sw = findViewById<SwitchCompat>(R.id.swBootStart)
        sw?.apply {
            isChecked = MyApp.getInstance().isBootStartEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                MyApp.getInstance().setBootStartEnabled(isChecked)
                Toast.makeText(
                    this@MainActivity,
                    if (isChecked) "已开启开机自启" else "已关闭开机自启",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    // ========== 绑定应用管理 ==========

    /**
     * 选择应用的 Activity 返回结果
     */
    private val appPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.let { data ->
                val pkg = data.`package` // 被选中的应用包名
                if (!pkg.isNullOrEmpty()) {
                    try {
                        val appInfo = packageManager.getApplicationInfo(pkg, 0)
                        val appName = packageManager.getApplicationLabel(appInfo).toString()
                        MyApp.getInstance().setBoundApp(pkg, appName)
                        appendLog("[绑定] 已绑定应用: $appName ($pkg)")
                        Toast.makeText(this, "已绑定: $appName", Toast.LENGTH_SHORT).show()
                        updateBoundAppInfo()
                    } catch (e: Exception) {
                        Toast.makeText(this, "获取应用信息失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun setupAppBinding() {
        // 选择应用按钮
        findViewById<android.widget.Button>(R.id.btnBindApp)?.setOnClickListener {
            try {
                // 使用 ACTION_PICK_ACTIVITY 或自定义方式让用户选择应用
                // 使用系统应用选择器：列出所有可启动的应用
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    // 使用 createChooser 没法拿到包名，改用 queryIntentActivities 自定义列表
                }
                // 用自定义方式：直接跳到应用详情页或自定义选择
                showAppPicker()
            } catch (e: Exception) {
                Toast.makeText(this, "打开应用列表失败: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        // 清除绑定按钮
        findViewById<android.widget.Button>(R.id.btnClearApp)?.setOnClickListener {
            MyApp.getInstance().clearBoundApp()
            updateBoundAppInfo()
            appendLog("[绑定] 已清除绑定应用")
            Toast.makeText(this, "已清除绑定应用", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 显示应用选择列表（使用 AlertDialog 列出所有可启动应用）
     */
    @SuppressLint("QueryPermissionsNeeded")
    private fun showAppPicker() {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = packageManager.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo != null && it.activityInfo.packageName != packageName }
            .sortedByDescending { it.loadLabel(packageManager).toString() }
            .map { it }

        if (resolveInfos.isEmpty()) {
            Toast.makeText(this, "未找到可启动的应用", Toast.LENGTH_SHORT).show()
            return
        }

        val appNames = resolveInfos.map { "${it.loadLabel(packageManager)}" }.toTypedArray()
        val appPackages = resolveInfos.map { it.activityInfo.packageName }

        android.app.AlertDialog.Builder(this)
            .setTitle("选择要绑定的应用")
            .setItems(appNames) { _, which ->
                val pkg = appPackages[which]
                val name = appNames[which]
                MyApp.getInstance().setBoundApp(pkg, name)
                appendLog("[绑定] 已绑定应用: $name ($pkg)")
                Toast.makeText(this, "已绑定: $name", Toast.LENGTH_SHORT).show()
                updateBoundAppInfo()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun updateBoundAppInfo() {
        val tvBoundApp = findViewById<TextView>(R.id.tvBoundApp)
        val appName = MyApp.getInstance().getBoundAppName()
        val appPkg = MyApp.getInstance().getBoundAppPackage()
        if (appName.isEmpty()) {
            tvBoundApp?.text = "未绑定应用"
            tvBoundApp?.setTextColor(Color.parseColor("#666666"))
        } else {
            tvBoundApp?.text = "已绑定: $appName ($appPkg)"
            tvBoundApp?.setTextColor(Color.parseColor("#4CAF50"))
        }
    }

    /**
     * 蓝牙连接成功后自动启动绑定的应用
     */
    /**
     * 蓝牙连接成功后，检查热点状态是否满足启动条件
     * 需要同时满足：蓝牙已连接 + 热点已开启，才会启动绑定应用
     */
    private fun launchBoundAppOnConnect() {
        val appName = MyApp.getInstance().getBoundAppName()
        val pkg = MyApp.getInstance().getBoundAppPackage()
        if (pkg.isEmpty()) {
            appendLog("[绑定] 未绑定应用，跳过自动启动")
            return
        }

        if (!isApEnabled) {
            appendLog("[绑定] 等待热点开启...")
            return
        }

        doLaunchBoundApp()
    }

    /**
     * 热点开启成功后，检查蓝牙连接状态是否满足启动条件
     */
    private fun launchBoundAppOnHotspot() {
        val appName = MyApp.getInstance().getBoundAppName()
        val pkg = MyApp.getInstance().getBoundAppPackage()
        if (pkg.isEmpty()) {
            return
        }

        if (!isBtConnected) {
            appendLog("[绑定] 等待蓝牙连接...")
            return
        }

        doLaunchBoundApp()
    }

    /**
     * 实际启动绑定应用（蓝牙+热点均已就绪时调用）
     */
    private fun doLaunchBoundApp() {
        val appName = MyApp.getInstance().getBoundAppName()
        appendLog("[绑定] 蓝牙+热点就绪，正在启动: $appName ...")
        // 延迟1秒启动，给状态稳定的时间
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            val success = MyApp.getInstance().launchBoundApp()
            if (success) {
                appendLog("[绑定] ✅ 已启动应用: $appName")
            } else {
                appendLog("[绑定] ❌ 启动应用失败: $appName (应用可能已卸载)")
                Toast.makeText(this, "启动应用失败，应用可能已卸载", Toast.LENGTH_SHORT).show()
            }
        }, 1000)
    }

    // ========== WiFi 热点管理 ==========

    @Volatile
    private var isApEnabled = false

    private fun setupHotspot() {
        val btnToggle = findViewById<android.widget.Button>(R.id.btnToggleAp)
        updateApStatus()

        // 密码显示/隐藏切换
        val etPassword = findViewById<EditText>(R.id.etApPassword)
        val btnTogglePwd = findViewById<android.widget.TextView>(R.id.btnTogglePassword)
        btnTogglePwd?.setOnClickListener {
            if (etPassword?.inputType == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                (android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)) {
                etPassword.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                btnTogglePwd.text = "显示"
            } else {
                etPassword?.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                btnTogglePwd.text = "隐藏"
            }
            etPassword?.setSelection(etPassword.text.length)
        }

        btnToggle?.setOnClickListener {
            val etName = findViewById<EditText>(R.id.etApName)
            val etPass = findViewById<EditText>(R.id.etApPassword)
            val name = etName?.text.toString().trim()
            val password = etPass?.text.toString().trim()

            if (name.length < 3) {
                Toast.makeText(this, "热点名称至少3位", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (password.length < 8) {
                Toast.makeText(this, "热点密码至少8位", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 保存热点配置
            MyApp.getInstance().setApName(name)
            MyApp.getInstance().setApPassword(password)

            if (!isApEnabled) {
                enableHotspot(name, password)
            } else {
                hotspotDisabling = true
                disableHotspot()
            }
        }

        // 自动开启热点：读取已保存的热点配置，如果系统热点未开启则自动启动
        handler.postDelayed({
            val savedName = MyApp.getInstance().getApName()
            val savedPass = MyApp.getInstance().getApPassword()
            if (savedName.length >= 3 && savedPass.length >= 8 && !isSystemHotspotEnabled()) {
                hotspotLog("检测到已保存热点配置，自动开启...")
                enableHotspot(savedName, savedPass)
            }
        }, 500)
    }

    /**
     * 热点日志输出到日志面板
     */
    private fun hotspotLog(msg: String) {
        android.util.Log.d("Hotspot", msg)
        appendLog("[热点] $msg")
    }

    private fun hotspotLogE(msg: String) {
        android.util.Log.e("Hotspot", msg)
        appendLog("[热点] ❌ $msg")
    }

    /**
     * 开启 WiFi 热点
     * Android 12+: 先用 SoftApConfiguration 配置 SSID/密码，再用 startTethering
     * Android 8-11: 用 startTethering 反射
     * Android 7-: 用 setWifiApEnabled 反射
     * 所有方法失败后 → 跳转系统热点设置引导用户手动开启
     */
    @SuppressLint("PrivateApi")
    private fun enableHotspot(ssid: String, password: String) {
        hotspotLog("正在开启热点: $ssid (API ${Build.VERSION.SDK_INT})...")

        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            // Android 12+ (API 31): 设置热点配置
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    val builderClass = Class.forName("android.net.wifi.WifiManager\$SoftApConfiguration\$Builder")
                    val builder = builderClass.getDeclaredConstructor().newInstance()
                    val setSsid = builderClass.getMethod("setSsid", String::class.java)
                    setSsid.invoke(builder, ssid)
                    val setPassphrase = builderClass.getMethod("setWpa2Passphrase", String::class.java)
                    setPassphrase.invoke(builder, password)
                    val buildMethod = builderClass.getMethod("build")
                    val softApConfig = buildMethod.invoke(builder)
                    val setConfigMethod = wifiManager.javaClass.getMethod("setSoftApConfiguration", softApConfig.javaClass)
                    setConfigMethod.invoke(wifiManager, softApConfig)
                    hotspotLog("SoftApConfiguration 设置成功: $ssid")
                } catch (e: Exception) {
                    hotspotLogE("SoftApConfiguration 设置失败: ${e.message}")
                }
            }

            // Android 8+ (API 26): 使用 startTethering
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    val callbackClass = Class.forName("android.net.wifi.WifiManager\$OnStartTetheringCallback")
                    val callback = java.lang.reflect.Proxy.newProxyInstance(
                        callbackClass.classLoader, arrayOf(callbackClass)
                    ) { _, method, _ ->
                        if (method?.name == "onTetheringStarted") {
                            hotspotLog("✅ 热点已开启 (startTethering)")
                            runOnUiThread {
                                isApEnabled = true
                                updateApStatus()
                                Toast.makeText(this@MainActivity, "热点开启成功", Toast.LENGTH_SHORT).show()
                                launchBoundAppOnHotspot()
                            }
                        } else if (method?.name == "onTetheringFailed") {
                            hotspotLogE("startTethering 回调失败，引导手动开启...")
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, "自动开启失败，请在系统设置中手动开启热点", Toast.LENGTH_LONG).show()
                                openSystemHotspotSettings()
                            }
                        }
                        null
                    }
                    val startMethod = wifiManager.javaClass.getMethod(
                        "startTethering",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        callbackClass
                    )
                    startMethod.invoke(wifiManager, 0, true, callback)
                    hotspotLog("正在开启热点 (startTethering)...")
                    updateBleStatus("正在开启热点...", Color.BLUE)
                    return
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    val cause = e.targetException
                    hotspotLogE("startTethering 权限不足: ${cause?.message ?: e.message}")
                    runOnUiThread {
                        Toast.makeText(this, "自动开启热点需要系统权限，请手动开启", Toast.LENGTH_LONG).show()
                        openSystemHotspotSettings()
                    }
                    return
                } catch (e: Exception) {
                    hotspotLogE("startTethering 反射失败: ${e.message}")
                }
            }

            // 旧版 Android (API < 26): 使用 setWifiApEnabled 反射
            try {
                val config = android.net.wifi.WifiConfiguration().apply {
                    this.SSID = "\"$ssid\""
                    this.preSharedKey = "\"$password\""
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA2_PSK)
                    allowedAuthAlgorithms.set(android.net.wifi.WifiConfiguration.AuthAlgorithm.OPEN)
                    allowedProtocols.set(android.net.wifi.WifiConfiguration.Protocol.RSN)
                }
                val method = wifiManager.javaClass.getMethod(
                    "setWifiApEnabled",
                    android.net.wifi.WifiConfiguration::class.java,
                    Boolean::class.javaPrimitiveType
                )
                method.isAccessible = true
                method.invoke(wifiManager, config, true)
                // 不立即设 isApEnabled=true，等广播或轮询确认
                hotspotLog("热点开启中 (setWifiApEnabled)...")
                Toast.makeText(this, "热点开启中...", Toast.LENGTH_SHORT).show()

                // 轮询检测热点是否真正开启（最多等 10 秒）
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                var pollCount = 0
                val maxPolls = 20 // 20 * 500ms = 10s
                val pollRunnable = object : Runnable {
                    override fun run() {
                        pollCount++
                        if (isSystemHotspotEnabled()) {
                            isApEnabled = true
                            updateApStatus()
                            hotspotLog("✅ 热点已开启 (轮询确认)")
                            Toast.makeText(this@MainActivity, "热点开启成功", Toast.LENGTH_SHORT).show()
                            launchBoundAppOnHotspot()
                        } else if (pollCount < maxPolls) {
                            handler.postDelayed(this, 500)
                        } else {
                            hotspotLogE("热点开启超时（10秒未检测到开启）")
                            updateApStatus()
                        }
                    }
                }
                handler.postDelayed(pollRunnable, 1000) // 1秒后开始检查
                return
            } catch (e: Exception) {
                hotspotLogE("setWifiApEnabled 失败: ${e.message}")
            }

            // 所有方法都失败
            hotspotLog("所有自动开启方法均失败，跳转系统设置...")
            runOnUiThread {
                Toast.makeText(this, "自动开启热点失败，请手动开启", Toast.LENGTH_LONG).show()
                openSystemHotspotSettings()
            }
        } catch (e: Exception) {
            hotspotLogE("enableHotspot 异常: ${e.message}")
            Toast.makeText(this, "开启热点失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 关闭 WiFi 热点
     */
    @SuppressLint("PrivateApi")
    private fun disableHotspot() {
        hotspotLog("正在关闭热点...")
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (Build.VERSION.SDK_INT >= 26) {
                try {
                    val method = wifiManager.javaClass.getMethod("stopTethering")
                    method.invoke(wifiManager)
                    hotspotLog("热点关闭中 (stopTethering)")
                } catch (e: Exception) {
                    hotspotLogE("stopTethering 失败: ${e.message}")
                    // fallback
                    try {
                        val method2 = wifiManager.javaClass.getMethod(
                            "setWifiApEnabled",
                            android.net.wifi.WifiConfiguration::class.java,
                            Boolean::class.javaPrimitiveType
                        )
                        method2.isAccessible = true
                        method2.invoke(wifiManager, null, false)
                        hotspotLog("热点关闭中 (setWifiApEnabled fallback)")
                    } catch (e2: Exception) {
                        hotspotLogE("setWifiApEnabled fallback 也失败: ${e2.message}")
                    }
                }
            } else {
                val method = wifiManager.javaClass.getMethod(
                    "setWifiApEnabled",
                    android.net.wifi.WifiConfiguration::class.java,
                    Boolean::class.javaPrimitiveType
                )
                method.isAccessible = true
                method.invoke(wifiManager, null, false)
                hotspotLog("热点关闭中 (setWifiApEnabled)")
            }
            isApEnabled = false
            updateApStatus()
            Toast.makeText(this, "热点已关闭", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            hotspotLogE("关闭热点失败: ${e.message}")
            Toast.makeText(this, "关闭热点失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 跳转系统热点设置页面，引导用户手动开启
     * 注意：不触发 onRestart/onResume 中的自动启动应用逻辑
     */
    private var hotspotSettingsOpened = false
    /**
     * 标记用户是否正在主动关闭热点，期间不触发 launchBoundAppOnHotspot
     */
    private var hotspotDisabling = false

    private fun openSystemHotspotSettings() {
        try {
            hotspotSettingsOpened = true
            val intent = Intent("android.settings.TETHER_SETTINGS")
            startActivity(intent)
            hotspotLog("已跳转系统热点设置页面")
        } catch (e: Exception) {
            hotspotLogE("跳转系统设置失败: ${e.message}")
            // fallback: 跳转 WiFi 设置
            try {
                hotspotSettingsOpened = true
                startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    /**
     * 通过反射读取系统真实热点状态
     * @return true = 已开启, false = 已关闭
     */
    @SuppressLint("PrivateApi")
    private fun isSystemHotspotEnabled(): Boolean {
        return try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wifiManager.javaClass.getMethod("isWifiApEnabled")
            method.invoke(wifiManager) as? Boolean ?: false
        } catch (e: Exception) {
            // API 31+ 可能没有 isWifiApEnabled，尝试 getSoftApConfiguration
            try {
                val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val method = wifiManager.javaClass.getMethod("getSoftApConfiguration")
                val config = method.invoke(wifiManager)
                config != null
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun updateApStatus() {
        runOnUiThread {
            // 从系统读取真实热点状态（覆盖手动设置的 isApEnabled）
            val systemEnabled = isSystemHotspotEnabled()
            val wasEnabled = isApEnabled
            if (systemEnabled != isApEnabled) {
                isApEnabled = systemEnabled
                hotspotLog("检测到系统热点状态变化: ${if (systemEnabled) "已开启" else "已关闭"}")
            }
            val tvStatus = findViewById<TextView>(R.id.tvApStatus)
            val btnToggle = findViewById<android.widget.Button>(R.id.btnToggleAp)
            tvStatus?.text = if (isApEnabled) "热点已开启" else "热点未开启"
            tvStatus?.setTextColor(if (isApEnabled) Color.parseColor("#4CAF50") else Color.RED)
            btnToggle?.text = if (isApEnabled) "关闭热点" else "开启热点"
            // 热点从关闭变为开启时，检查是否可以启动绑定应用
            // 但如果是用户主动关闭热点后的状态抖动，不触发
            if (!wasEnabled && isApEnabled && !hotspotDisabling) {
                launchBoundAppOnHotspot()
            }
        }
    }

    private fun setupLogPanel() {
        logAdapter = LogAdapter()
        logRecyclerView = findViewById(R.id.rvLog)
        logRecyclerView?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = logAdapter
        }
        logAdapter.appendLog("等待操作...")
        findViewById<TextView>(R.id.btnClearLog)?.setOnClickListener {
            logAdapter.clear()
            logAdapter.appendLog("日志已清除")
        }
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            logAdapter.appendLog("[$time] $msg")
            // 自动滚动到底部
            logRecyclerView?.post {
                val count = logAdapter.itemCount
                if (count > 0) {
                    logRecyclerView?.scrollToPosition(count - 1)
                }
            }
        }
    }

    private fun updateBoundDeviceInfo() {
        val devices = MyApp.getInstance().getSavedDevices()
        val tvBound = findViewById<TextView>(R.id.tvBoundDevice)
        tvBound?.text = if (devices.isEmpty()) {
            "已绑定设备: 无"
        } else {
            "已绑定设备: ${devices.joinToString(", ")}"
        }
    }

    // ========== 快捷操作按钮 ==========

    private fun setupQuickActions() {
        // 打开 ADB 调试设置
        findViewById<android.widget.Button>(R.id.btnOpenAdb)?.setOnClickListener {
            try {
                val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                startActivity(intent)
                appendLog("[快捷] 已打开开发者选项 (ADB调试)")
            } catch (e: Exception) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    appendLog("[快捷] 已打开系统设置（开发者选项不可用，已跳转主设置）")
                } catch (_: Exception) {}
            }
        }

        // 打开蓝牙设置
        findViewById<android.widget.Button>(R.id.btnOpenBluetooth)?.setOnClickListener {
            try {
                val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                startActivity(intent)
                appendLog("[快捷] 已打开蓝牙设置")
            } catch (e: Exception) {
                appendLog("[快捷] 打开蓝牙设置失败: ${e.message}")
            }
        }

        // 打开热点设置
        findViewById<android.widget.Button>(R.id.btnOpenHotspot)?.setOnClickListener {
            openSystemHotspotSettings()
            appendLog("[快捷] 已打开热点设置")
        }

        // 打开绑定应用（底部快捷按钮）
        findViewById<android.widget.Button>(R.id.btnLaunchBoundApp)?.setOnClickListener {
            val pkg = MyApp.getInstance().getBoundAppPackage()
            val name = MyApp.getInstance().getBoundAppName()
            if (pkg.isEmpty()) {
                Toast.makeText(this, "请先在「绑定应用」中选择应用", Toast.LENGTH_SHORT).show()
                appendLog("[快捷] 未绑定应用，请先选择")
                return@setOnClickListener
            }
            val success = MyApp.getInstance().launchBoundApp()
            if (success) {
                appendLog("[快捷] ✅ 已启动应用: $name")
                Toast.makeText(this, "已启动: $name", Toast.LENGTH_SHORT).show()
            } else {
                appendLog("[快捷] ❌ 启动应用失败: $name")
                Toast.makeText(this, "启动失败，应用可能已卸载", Toast.LENGTH_SHORT).show()
            }
        }

        // 重启车机 - 使用 su root reboot
        findViewById<android.widget.Button>(R.id.btnReboot)?.setOnClickListener {
            appendLog("[快捷] 正在重启车机...")
            android.app.AlertDialog.Builder(this)
                .setTitle("确认重启")
                .setMessage("确定要重启车机吗？")
                .setPositiveButton("确定") { _, _ ->
                    Thread {
                        try {
                            appendLog("[快捷] 尝试方式1: su root reboot")
                            Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot"))
                        } catch (e: Exception) {
                            appendLog("[快捷] 方式1失败: ${e.message}")
                            try {
                                appendLog("[快捷] 尝试方式2: su root /system/bin/reboot")
                                Runtime.getRuntime().exec(arrayOf("su", "-c", "/system/bin/reboot"))
                            } catch (e2: Exception) {
                                appendLog("[快捷] 方式2失败: ${e2.message}")
                                try {
                                    appendLog("[快捷] 尝试方式3: reboot")
                                    Runtime.getRuntime().exec("reboot")
                                } catch (e3: Exception) {
                                    appendLog("[快捷] 所有方式均失败: ${e3.message}")
                                    runOnUiThread {
                                        Toast.makeText(this, "重启失败，请手动重启", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }.start()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun updateVersionInfo() {
        val tvVersion = findViewById<TextView>(R.id.tvVersion)
        tvVersion?.text = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            "v${pInfo.versionName}"
        } catch (_: Exception) {
            "v1.0.9"
        }
    }

    override fun onResume() {
        super.onResume()
        updateApStatus()
        updateBoundDeviceInfo()
        updateBoundAppInfo()
        hotspotSettingsOpened = false
    }

    override fun onDestroy() {
        try { unregisterReceiver(btStatusReceiver) } catch (_: Exception) {}
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
