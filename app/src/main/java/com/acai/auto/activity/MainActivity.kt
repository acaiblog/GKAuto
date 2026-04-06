package com.acai.auto.activity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.acai.auto.MyApp
import com.acai.auto.R
import com.acai.auto.adapter.LogAdapter
import com.acai.auto.adapter.WifiAdapter
import com.acai.auto.ble.BleAutoConnectService

/**
 * 主界面 - 绑定应用
 * 蓝牙绑定 + WiFi热点管理 + 自动连接 + 开机自启
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var logAdapter: LogAdapter
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var isBtConnected = false
    private val handler = Handler(Looper.getMainLooper())
    // 保存已配对的设备（用于快速绑定）
    private val bondedDevices = mutableListOf<BluetoothDevice>()

    // 日志 RecyclerView 引用
    private var logRecyclerView: androidx.recyclerview.widget.RecyclerView? = null

    // WiFi 连接相关变量
    private lateinit var wifiAdapter: WifiAdapter
    private val wifiScanResults = mutableListOf<ScanResult>()
    private var wifiManager: WifiManager? = null
    private var selectedWifi: ScanResult? = null
    private var isWifiConnected = false
    private var isWifiConnecting = false
    private var currentConnectedSsid = ""

    // WiFi 连接回调
    private val wifiConnectivityCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                runOnUiThread {
                    isWifiConnected = true
                    isWifiConnecting = false
                    updateWifiStatus()
                    launchBoundAppOnWifiConnected()
                }
            }

            override fun onLost(network: Network) {
                super.onLost(network)
                runOnUiThread {
                    isWifiConnected = false
                    isWifiConnecting = false
                    updateWifiStatus()
                }
            }
        }
    } else null

    // WiFi 扫描广播接收器
    private val wifiScanReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (success) {
                    appendLog("[WiFi] 扫描成功，找到 ${wifiScanResults.size} 个网络")
                } else {
                    appendLog("[WiFi] 扫描失败")
                }
                // 更新列表
                wifiScanResults.clear()
                wifiManager?.let {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        wifiScanResults.addAll(it.scanResults.filter { r -> r.SSID.isNotEmpty() })
                    } else {
                        @Suppress("DEPRECATION")
                        wifiScanResults.addAll(it.scanResults.filter { r -> r.SSID.isNotEmpty() })
                    }
                }
                wifiAdapter.notifyDataSetChanged()
            }
        }
    }

    // WiFi 状态广播接收器
    private val wifiStateReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    val wifiInfo = intent.getParcelableExtra<WifiInfo>(WifiManager.EXTRA_WIFI_INFO)
                    if (wifiInfo != null && wifiInfo.networkId != -1) {
                        val ssid = wifiInfo.ssid?.replace("\"", "") ?: ""
                        if (ssid != "<unknown ssid>" && ssid.isNotEmpty()) {
                            currentConnectedSsid = ssid
                            isWifiConnected = true
                            isWifiConnecting = false
                            updateWifiStatus()
                            // 保存已连接的 WiFi
                            MyApp.getInstance().setApName(ssid)
                            updateConnectedWifiInfo()
                            // WiFi连接成功后自动启动绑定应用
                            launchBoundAppOnWifiConnected()
                        }
                    } else if (!isWifiConnecting) {
                        isWifiConnected = false
                        updateWifiStatus()
                    }
                }
            }
        }
    }

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
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            initBle()
        } else {
            Toast.makeText(this, "需要蓝牙和位置权限才能使用", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = btManager?.adapter ?: @Suppress("DEPRECATION") BluetoothAdapter.getDefaultAdapter()

        // 初始化 WiFi 管理器
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        setupLogPanel()
        setupWifiRecyclerView()
        setupAutoConnectSwitch()
        setupBootStartSwitch()
        setupAppBinding()
        setupHotspot()
        setupQuickActions()
        registerBtReceiver()
        registerWifiReceivers()
        checkPermissions()
        updateBoundAppInfo()
        updateVersionInfo()
        updateConnectedWifiInfo()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(wifiScanReceiver)
            unregisterReceiver(wifiStateReceiver)
            unregisterReceiver(btStatusReceiver)
        } catch (e: Exception) {}
        // 注销网络回调
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                wifiConnectivityCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
            } catch (e: Exception) {}
        }
        handler.removeCallbacksAndMessages(null)
    }

    private fun registerBtReceiver() {
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BleAutoConnectService.ACTION_BT_CONNECTED)
            addAction(BleAutoConnectService.ACTION_BT_DISCONNECTED)
            addAction(BleAutoConnectService.ACTION_BT_CONNECTING)
            addAction("com.acai.auto.LOG")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(btStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(btStatusReceiver, filter)
        }
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
            // 尝试 Global 设置
            val result = android.provider.Settings.Global.getInt(
                contentResolver,
                "bluetooth_on",
                0
            )
            result != 0
        } catch (e: Exception) {
            try {
                // 尝试 System 设置
                val result = android.provider.Settings.System.getInt(
                    contentResolver,
                    android.provider.Settings.System.BLUETOOTH_ON,
                    0
                )
                result != 0
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * 通过反射获取BluetoothAdapter的真实状态
     * 有些设备adapter.isEnabled()返回不准确，需要读取内部状态
     */
    private fun isBluetoothActuallyEnabled(): Boolean {
        val adapter = bluetoothAdapter ?: return false
        return try {
            // 方法1: adapter.isEnabled()
            if (adapter.isEnabled) {
                // 进一步检查是否是GKUI系统的蓝牙bug
                // 通过反射获取mService来判断真实状态
                val mServiceField = adapter.javaClass.getDeclaredField("mService")
                mServiceField.isAccessible = true
                val mService = mServiceField.get(adapter)
                if (mService != null) {
                    val getStateMethod = mService.javaClass.getMethod("getState")
                    val state = getStateMethod.invoke(mService) as Int
                    state == BluetoothAdapter.STATE_ON
                } else {
                    // mService为null，说明蓝牙确实没开启
                    false
                }
            } else {
                false
            }
        } catch (e: Exception) {
            // 反射失败时，使用adapter.isEnabled()结果
            adapter.isEnabled
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
        // 蓝牙状态已简化，不再使用UI更新
    }

    private fun setupWifiRecyclerView() {
        wifiAdapter = WifiAdapter(wifiScanResults) { scanResult ->
            selectedWifi = scanResult
            val ssid = if (scanResult.SSID.isNullOrEmpty()) "<未知网络>" else scanResult.SSID
            val security = getSecurityType(scanResult)
            appendLog("[WiFi] 选择网络: $ssid ($security)")
            
            // 如果是开放网络，直接连接
            if (security == "开放") {
                connectToWifi(scanResult, "")
            } else {
                // 弹出密码输入对话框
                showPasswordDialog(scanResult)
            }
        }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvWifiList)?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = wifiAdapter
        }
    }

    @SuppressLint("MissingPermission")
    private fun getSecurityType(scanResult: ScanResult): String {
        val capabilities = scanResult.capabilities
        return when {
            capabilities.contains("WPA3") -> "WPA3"
            capabilities.contains("WPA2") -> "WPA2"
            capabilities.contains("WPA") -> "WPA"
            capabilities.contains("WEP") -> "WEP"
            else -> "开放"
        }
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
     * 蓝牙连接成功后自动启动绑定的应用
     * 简化逻辑：只要有绑定应用就启动
     * 注意：目前主要通过热点触发，蓝牙触发作为备用
     */
    private fun launchBoundAppOnConnect() {
        val appName = MyApp.getInstance().getBoundAppName()
        val pkg = MyApp.getInstance().getBoundAppPackage()
        if (pkg.isEmpty()) {
            appendLog("[绑定] 未绑定应用，跳过自动启动")
            return
        }

        // 直接启动绑定应用，不再检查热点状态
        appendLog("[绑定] 蓝牙已连接，自动启动绑定应用...")
        doLaunchBoundApp()
    }

    /**
     * WiFi连接成功后，自动启动绑定的应用
     */
    private fun launchBoundAppOnWifiConnected() {
        val appName = MyApp.getInstance().getBoundAppName()
        val pkg = MyApp.getInstance().getBoundAppPackage()
        if (pkg.isEmpty()) {
            appendLog("[绑定] 未绑定应用，跳过自动启动")
            return
        }

        appendLog("[绑定] WiFi已连接，自动启动绑定应用...")
        doLaunchBoundApp()
    }

    /**
     * 实际启动绑定应用
     */
    private fun doLaunchBoundApp() {
        val appName = MyApp.getInstance().getBoundAppName()
        appendLog("[绑定] 正在启动: $appName ...")

        // 立即启动绑定应用，不延迟
        val success = MyApp.getInstance().launchBoundApp()
        if (success) {
            appendLog("[绑定] 已启动应用: $appName")
        } else {
            appendLog("[绑定] 启动应用失败: $appName (应用可能已卸载)")
            Toast.makeText(this, "启动应用失败，应用可能已卸载", Toast.LENGTH_SHORT).show()
        }
    }

    // ========== WiFi 连接管理 ==========

    private fun setupHotspot() {
        val btnToggle = findViewById<android.widget.Button>(R.id.btnToggleAp)
        val btnScanWifi = findViewById<android.widget.Button>(R.id.btnScanWifi)
        
        updateWifiStatus()

        // 扫描WiFi按钮
        btnScanWifi?.setOnClickListener {
            scanWifi()
        }

        btnToggle?.setOnClickListener {
            if (selectedWifi == null) {
                Toast.makeText(this, "请先选择要连接的WiFi网络", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            
            val ssid = selectedWifi?.SSID ?: ""
            if (isWifiConnected && currentConnectedSsid == ssid) {
                // 断开连接
                disconnectWifi()
            } else {
                // 连接WiFi
                val security = getSecurityType(selectedWifi!!)
                if (security == "开放") {
                    connectToWifi(selectedWifi!!, "")
                } else {
                    showPasswordDialog(selectedWifi!!)
                }
            }
        }
    }

    /**
     * 弹出密码输入对话框
     */
    private fun showPasswordDialog(scanResult: ScanResult) {
        val ssid = if (scanResult.SSID.isNullOrEmpty()) "<未知网络>" else scanResult.SSID
        
        val editText = android.widget.EditText(this).apply {
            hint = "输入WiFi密码"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 32)
        }
        
        android.app.AlertDialog.Builder(this)
            .setTitle("连接 $ssid")
            .setView(editText)
            .setPositiveButton("连接") { _, _ ->
                val password = editText.text.toString().trim()
                if (password.isNotEmpty()) {
                    connectToWifi(scanResult, password)
                } else {
                    Toast.makeText(this, "密码不能为空", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    @SuppressLint("MissingPermission")
    private fun scanWifi() {
        wifiManager?.let { wm ->
            if (!wm.isWifiEnabled) {
                appendLog("[WiFi] WiFi未开启，正在开启...")
                wm.isWifiEnabled = true
            }
            
            wifiScanResults.clear()
            
            // Android 5.1/6.0 需要特殊处理
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
                appendLog("[WiFi] Android 5.1/6.0 使用系统扫描结果...")
                // 直接获取系统已有的扫描结果
                @Suppress("DEPRECATION")
                val results = wm.scanResults.filter { it.SSID.isNotEmpty() }
                if (results.isNotEmpty()) {
                    wifiScanResults.addAll(results)
                    wifiAdapter.notifyDataSetChanged()
                    appendLog("[WiFi] 找到 ${results.size} 个网络")
                } else {
                    // 如果没有扫描结果，跳转系统WiFi设置
                    appendLog("[WiFi] 无扫描结果，跳转系统设置...")
                    openWifiSettings()
                }
            } else {
                // Android 7.0+ 尝试主动扫描
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val success = wm.startScan()
                    appendLog("[WiFi] 扫描${if (success) "启动成功" else "启动失败"}...")
                } else {
                    @Suppress("DEPRECATION")
                    val success = wm.startScan()
                    appendLog("[WiFi] 扫描${if (success) "启动成功" else "启动失败"}...")
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToWifi(scanResult: ScanResult, password: String) {
        val ssid = if (scanResult.SSID.isNullOrEmpty()) "<未知网络>" else scanResult.SSID
        val security = getSecurityType(scanResult)
        appendLog("[WiFi] 正在连接: $ssid...")
        isWifiConnecting = true
        updateWifiStatus()
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 使用 WifiNetworkSuggestion
                val suggestion = WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .apply {
                        if (security != "开放") {
                            setWpa2Passphrase(password)
                        }
                    }
                    .build()
                
                val status = wifiManager?.addNetworkSuggestions(listOf(suggestion))
                appendLog("[WiFi] addNetworkSuggestions: $status")
                
                if (status == android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                    Toast.makeText(this, "请在系统弹窗中选择连接", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, "添加网络建议失败: $status", Toast.LENGTH_SHORT).show()
                }
            } else {
                // Android 9 及以下使用传统方式
                @Suppress("DEPRECATION")
                connectWifiLegacy(scanResult, password)
            }
        } catch (e: Exception) {
            appendLog("[WiFi] 连接失败: ${e.message}")
            Toast.makeText(this, "连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        
        isWifiConnecting = false
        updateWifiStatus()
    }

    @Suppress("DEPRECATION")
    private fun connectWifiLegacy(scanResult: ScanResult, password: String) {
        try {
            val ssid = scanResult.SSID
            val config = WifiConfiguration().apply {
                this.SSID = "\"$ssid\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
                preSharedKey = "\"$password\""
            }
            
            val netId = wifiManager?.addNetwork(config) ?: -1
            if (netId != -1) {
                wifiManager?.enableNetwork(netId, true)
                wifiManager?.reconnect()
                appendLog("[WiFi] 正在连接: $ssid (netId=$netId)")
                
                // 保存连接信息
                MyApp.getInstance().setApName(ssid)
                MyApp.getInstance().setApPassword(password)
                updateConnectedWifiInfo()
            } else {
                appendLog("[WiFi] 添加网络配置失败")
                Toast.makeText(this, "添加网络配置失败", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            appendLog("[WiFi] 连接失败: ${e.message}")
            Toast.makeText(this, "连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectWifi() {
        try {
            wifiManager?.let { wm ->
                @Suppress("DEPRECATION")
                val currentNetId = wm.connectionInfo.networkId
                if (currentNetId != -1) {
                    wm.disableNetwork(currentNetId)
                    @Suppress("DEPRECATION")
                    wm.disconnect()
                    appendLog("[WiFi] 已断开连接")
                }
            }
            isWifiConnected = false
            currentConnectedSsid = ""
            updateWifiStatus()
            updateConnectedWifiInfo()
        } catch (e: Exception) {
            appendLog("[WiFi] 断开连接失败: ${e.message}")
        }
    }

    private fun updateWifiStatus() {
        val tvStatus = findViewById<TextView>(R.id.tvApStatus)
        val btnToggle = findViewById<android.widget.Button>(R.id.btnToggleAp)
        
        runOnUiThread {
            when {
                isWifiConnecting -> {
                    tvStatus?.text = "连接中..."
                    tvStatus?.setTextColor(Color.parseColor("#FF9800"))
                    btnToggle?.text = "取消连接"
                }
                isWifiConnected -> {
                    tvStatus?.text = "已连接: $currentConnectedSsid"
                    tvStatus?.setTextColor(Color.parseColor("#4CAF50"))
                    btnToggle?.text = "断开连接"
                }
                else -> {
                    tvStatus?.text = "未连接"
                    tvStatus?.setTextColor(Color.RED)
                    btnToggle?.text = "连接WiFi"
                }
            }
        }
    }

    private fun updateConnectedWifiInfo() {
        val tvConnectedWifi = findViewById<TextView>(R.id.tvConnectedWifi)
        val savedSsid = MyApp.getInstance().getApName()
        
        if (savedSsid.isNotEmpty() && savedSsid != "GKAuto_Hotspot") {
            tvConnectedWifi?.text = "已绑定: $savedSsid"
        } else {
            tvConnectedWifi?.text = "已绑定: 无"
        }
    }

    private fun registerWifiReceivers() {
        // 注册WiFi扫描广播
        val scanFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiScanReceiver, scanFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wifiScanReceiver, scanFilter)
        }
        
        // 注册WiFi状态广播
        val stateFilter = IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(wifiStateReceiver, stateFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(wifiStateReceiver, stateFilter)
        }
        
        // 注册网络回调 (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                wifiConnectivityCallback?.let { 
                    connectivityManager.registerNetworkCallback(request, it)
                }
            } catch (e: Exception) {
                appendLog("[WiFi] 注册网络回调失败: ${e.message}")
            }
        }
    }

    /**
     * 跳转系统WiFi设置页面
     */
    private fun openWifiSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            appendLog("[快捷] 打开WiFi设置失败: ${e.message}")
            try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            } catch (_: Exception) {}
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

        // 打开WiFi设置
        findViewById<android.widget.Button>(R.id.btnOpenHotspot)?.setOnClickListener {
            openWifiSettings()
            appendLog("[快捷] 已打开WiFi设置")
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
        updateWifiStatus()
        updateBoundAppInfo()
    }
}
