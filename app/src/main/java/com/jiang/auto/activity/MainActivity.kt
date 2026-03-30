package com.jiang.auto.activity

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.jiang.auto.MyApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.jiang.auto.R
import com.jiang.auto.adapter.DeviceAdapter
import com.jiang.auto.adapter.LogAdapter
import com.jiang.auto.ble.BleAutoConnectService
import com.jiang.auto.ble.BleManager

/**
 * 主界面 - 绑定应用
 * 蓝牙绑定 + WiFi热点管理 + 自动连接 + 开机自启
 */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var bleManager: BleManager
    private lateinit var deviceAdapter: DeviceAdapter
    private lateinit var logAdapter: LogAdapter
    private val scanResults = mutableListOf<android.bluetooth.BluetoothDevice>()
    private var isScanning = false

    // 日志 RecyclerView 引用
    private var logRecyclerView: androidx.recyclerview.widget.RecyclerView? = null

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

        bleManager = BleManager.getInstance()
        bleManager.init(this)

        setupLogPanel()
        setupRecyclerView()
        setupScanButton()
        setupAutoConnectSwitch()
        setupBootStartSwitch()
        setupAppBinding()
        setupHotspot()
        checkPermissions()
        updateBoundDeviceInfo()
        updateBoundAppInfo()
        updateVersionInfo()
    }

    private fun checkPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED
            ) {
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
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter == null || !adapter.isEnabled) {
            updateBleStatus("蓝牙未开启", Color.RED)
        } else {
            setupBleListeners()
            bleManager.setOnLogListener { msg -> appendLog(msg) }
            updateBleStatus("蓝牙已就绪", Color.parseColor("#4CAF50"))

            // 异步检查蓝牙连接状态，避免阻塞主线程
            MainScope().launch(Dispatchers.IO) {
                // 检查是否有已连接的设备
                val existingDevice = bleManager.checkExistingConnection()
                withContext(Dispatchers.Main) {
                    if (existingDevice != null) {
                        bleManager.restoreConnection(existingDevice)
                        updateBleStatus(
                            "已连接: ${existingDevice.name ?: existingDevice.address}",
                            Color.parseColor("#4CAF50")
                        )
                        findViewById<android.widget.Button>(R.id.btnScan)?.text = "断开连接"
                        appendLog("检测到蓝牙已连接: ${existingDevice.name} (${existingDevice.address})")
                    } else {
                        // 没有已连接设备，尝试自动连接已保存的设备
                        val savedDevices = MyApp.getInstance().getSavedDevices()
                        if (savedDevices.isNotEmpty()) {
                            appendLog("检测到 ${savedDevices.size} 个已保存设备，尝试自动连接...")
                            bleManager.autoConnectSavedDevices { success ->
                                runOnUiThread {
                                    if (success) {
                                        val device = bleManager.getConnectedDevice()
                                        updateBleStatus(
                                            "已连接: ${device?.name ?: device?.address}",
                                            Color.parseColor("#4CAF50")
                                        )
                                        findViewById<android.widget.Button>(R.id.btnScan)?.text = "断开连接"
                                        launchBoundAppOnConnect()
                                    } else {
                                        updateBleStatus("自动连接失败，请手动扫描", Color.parseColor("#FF9800"))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateBleStatus(text: String, color: Int) {
        findViewById<TextView>(R.id.tvBleStatus)?.apply {
            this.text = text
            setTextColor(color)
        }
    }

    private fun setupBleListeners() {
        bleManager.setOnConnectionStateChangedListener { state ->
            runOnUiThread {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        val device = bleManager.getConnectedDevice()
                        updateBleStatus(
                            "已连接: ${device?.name ?: device?.address}",
                            Color.parseColor("#4CAF50")
                        )
                        findViewById<android.widget.Button>(R.id.btnScan)?.text = "断开连接"
                        device?.address?.let {
                            MyApp.getInstance().saveDevice(it)
                            deviceAdapter.notifyDataSetChanged()
                            updateBoundDeviceInfo()
                        }
                        // 蓝牙连接成功 → 自动启动绑定应用
                        launchBoundAppOnConnect()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        updateBleStatus("蓝牙未连接", Color.RED)
                        findViewById<android.widget.Button>(R.id.btnScan)?.text = "扫描蓝牙设备"
                    }
                }
            }
        }

        bleManager.setOnDataReceivedListener { data ->
            runOnUiThread {
                Toast.makeText(
                    this,
                    "收到数据: ${data.joinToString("") { "%02X".format(it) }}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun setupRecyclerView() {
        deviceAdapter = DeviceAdapter(scanResults) { device ->
            val address = device.address
            val name = device.name ?: address
            updateBleStatus("正在连接 $name...", Color.BLUE)
            bleManager.connectDevice(address) { success ->
                runOnUiThread {
                    if (success) {
                        // 连接成功，保存设备
                        MyApp.getInstance().saveDevice(address)
                        deviceAdapter.notifyDataSetChanged()
                        updateBoundDeviceInfo()
                        val btnScan = findViewById<android.widget.Button>(R.id.btnScan)
                        btnScan?.text = "断开连接"
                    } else {
                        updateBleStatus("连接失败: $name", Color.RED)
                        Toast.makeText(this, "连接 $name 失败，请确认设备已开启", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rvDevices)?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = deviceAdapter
        }
    }

    private fun setupScanButton() {
        findViewById<android.widget.Button>(R.id.btnScan)?.setOnClickListener {
            if (bleManager.isConnected) {
                bleManager.disconnect()
                return@setOnClickListener
            }

            if (isScanning) return@setOnClickListener
            isScanning = true

            updateBleStatus("扫描中...", Color.BLUE)
            scanResults.clear()
            deviceAdapter.notifyDataSetChanged()

            bleManager.scanDevices(8000) { devices ->
                isScanning = false
                scanResults.clear()
                scanResults.addAll(devices)
                deviceAdapter.notifyDataSetChanged()
                if (devices.isEmpty()) {
                    updateBleStatus("未发现设备", Color.parseColor("#FF9800"))
                } else {
                    updateBleStatus("发现 ${devices.size} 个设备", Color.parseColor("#FF9800"))
                }
            }
        }
    }

    private fun setupAutoConnectSwitch() {
        val sw = findViewById<SwitchCompat>(R.id.swAutoConnect)
        sw?.apply {
            // 先移除监听器，避免设置 isChecked 时触发回调
            setOnCheckedChangeListener(null)
            isChecked = MyApp.getInstance().isAutoConnectEnabled()
            setOnCheckedChangeListener { _, isChecked ->
                MyApp.getInstance().setAutoConnectEnabled(isChecked)
                if (isChecked) {
                    if (bleManager.isConnected) {
                        bleManager.getConnectedDevice()?.address?.let {
                            MyApp.getInstance().saveDevice(it)
                            updateBoundDeviceInfo()
                        }
                    }
                    val serviceIntent = Intent(this@MainActivity, BleAutoConnectService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                    Toast.makeText(this@MainActivity, "已开启自动连接", Toast.LENGTH_SHORT).show()
                } else {
                    bleManager.stopAutoConnect()
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

        if (!bleManager.isConnected) {
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
        hotspotLog("正在开启热点: $ssid ...")

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
                        // 只处理真正的回调方法，忽略 equals/hashCode/toString 等自动调用的方法
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
                        // 所有回调方法都返回 null（void 方法）
                        null
                    }
                    val startMethod = wifiManager.javaClass.getMethod(
                        "startTethering",
                        Int::class.javaPrimitiveType,
                        Boolean::class.javaPrimitiveType,
                        callbackClass
                    )
                    startMethod.invoke(wifiManager, 0, true, callback)
                    hotspotLog("正在开启热点 (startTethering, API ${Build.VERSION.SDK_INT})...")
                    updateBleStatus("正在开启热点...", Color.BLUE)
                    return
                } catch (e: java.lang.reflect.InvocationTargetException) {
                    // startTethering 抛出异常（通常是 SecurityException: 没有系统权限）
                    val cause = e.targetException
                    hotspotLogE("startTethering 权限不足: ${cause?.message ?: e.message}")
                    // 直接跳转系统设置，不继续尝试其他方法
                    hotspotLog("跳转系统热点设置页面，请手动开启热点")
                    runOnUiThread {
                        Toast.makeText(this, "自动开启热点需要系统权限，请手动开启", Toast.LENGTH_LONG).show()
                        openSystemHotspotSettings()
                    }
                    return
                } catch (e: Exception) {
                    hotspotLogE("startTethering 反射失败: ${e.message}")
                }
            }

            // 旧版 Android: 使用 setWifiApEnabled 反射
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
                isApEnabled = true
                hotspotLog("热点开启中 (setWifiApEnabled, API ${Build.VERSION.SDK_INT})...")
                Toast.makeText(this, "热点开启中...", Toast.LENGTH_SHORT).show()
                android.os.Handler().postDelayed({
                    updateApStatus()
                    launchBoundAppOnHotspot()
                }, 2000)
                return
            } catch (e: Exception) {
                hotspotLogE("setWifiApEnabled 失败: ${e.message}")
            }

            // 所有方法都失败，引导用户手动开启
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
        bleManager.stopAutoConnect()
        super.onDestroy()
    }
}
