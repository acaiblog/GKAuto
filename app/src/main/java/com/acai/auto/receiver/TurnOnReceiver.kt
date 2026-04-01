package com.acai.auto.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.acai.auto.MyApp
import com.acai.auto.ble.BleAutoConnectService

/**
 * 开机广播接收器
 * 参考 CarConnectApp BootReceiver，修复热点开机自启问题
 * 流程：收到 BOOT_COMPLETED → 延迟等待系统稳定 → 开启蓝牙 → 开启热点 → 启动蓝牙服务 → 启动主界面
 */
class TurnOnReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TurnOnReceiver"
        // 立即启动，不等待
        private const val BOOT_DELAY_MS = 0L
    }

    @SuppressLint("MissingPermission", "PrivateApi")
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action
        // 兼容部分车机的快速启动广播
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON") return
        if (context == null) return

        Log.d(TAG, "收到开机广播: $action")

        val app = MyApp.getInstance()

        // 延迟等系统框架稳定后再执行
        Handler(Looper.getMainLooper()).postDelayed({
            // 1. 开启蓝牙
            try {
                val adapter = BluetoothAdapter.getDefaultAdapter()
                if (adapter != null && !adapter.isEnabled) {
                    @Suppress("DEPRECATION")
                    adapter.enable()
                    Log.d(TAG, "蓝牙已请求开启")
                }
            } catch (e: Exception) {
                Log.e(TAG, "开启蓝牙失败: ${e.message}")
            }

            // 2. 开启 WiFi 热点（如果开机自启已开启）
            if (app.isBootStartEnabled()) {
                enableWifiHotspot(context, app)
            }

            // 3. 启动蓝牙自动连接服务（如果自动连接已开启）
            if (app.isAutoConnectEnabled()) {
                val devices = app.getSavedDevices()
                if (devices.isNotEmpty()) {
                    Log.d(TAG, "启动蓝牙自动连接服务，已保存设备数: ${devices.size}")
                    BleAutoConnectService.start(context)
                }
            }

            // 4. 启动主界面（如果开机自启已开启）
            if (app.isBootStartEnabled()) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                launchIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(it)
                    Log.d(TAG, "主界面已启动")
                }
            }
        }, BOOT_DELAY_MS)
    }

    @SuppressLint("PrivateApi")
    private fun enableWifiHotspot(context: Context, app: MyApp) {
        try {
            val apName = app.getApName()
            val apPassword = app.getApPassword()
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            when {
                // Android 12+ (API 31+): SoftApConfiguration
                Build.VERSION.SDK_INT >= 31 -> {
                    try {
                        val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
                        val builder = builderClass.getDeclaredConstructor().newInstance()
                        builderClass.getMethod("setSsid", String::class.java).invoke(builder, apName)
                        builderClass.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
                            .invoke(builder, apPassword, 2) // SECURITY_TYPE_WPA2_PSK = 2
                        val config = builderClass.getMethod("build").invoke(builder)
                        wifiManager.javaClass.getMethod("setSoftApConfiguration", config.javaClass)
                            .invoke(wifiManager, config)
                        Log.d(TAG, "API31+ SoftApConfiguration 已设置: $apName")
                    } catch (e: Exception) {
                        Log.e(TAG, "API31+ SoftApConfiguration 失败，尝试旧接口: ${e.message}")
                        enableHotspotLegacy(wifiManager, apName, apPassword)
                    }
                    // startTethering
                    startTethering(wifiManager)
                }
                // Android 8+ (API 26+): startTethering
                Build.VERSION.SDK_INT >= 26 -> {
                    try {
                        // 先通过 setWifiApConfiguration 设置名称密码
                        @Suppress("DEPRECATION")
                        val wifiConfig = android.net.wifi.WifiConfiguration().apply {
                            SSID = apName
                            preSharedKey = apPassword
                            allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA2_PSK)
                        }
                        wifiManager.javaClass.getMethod("setWifiApConfiguration", android.net.wifi.WifiConfiguration::class.java)
                            .invoke(wifiManager, wifiConfig)
                    } catch (_: Exception) {}
                    startTethering(wifiManager)
                }
                // Android 7 及以下: setWifiApEnabled
                else -> {
                    enableHotspotLegacy(wifiManager, apName, apPassword)
                }
            }
            Log.d(TAG, "热点开启请求已发送: $apName")
        } catch (e: Exception) {
            Log.e(TAG, "开启热点失败: ${e.message}")
        }
    }

    private fun startTethering(wifiManager: WifiManager) {
        try {
            val callbackClass = Class.forName("android.net.wifi.WifiManager\$OnStartTetheringCallback")
            val callback = java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader, arrayOf(callbackClass)
            ) { _, method, _ ->
                when (method?.name) {
                    "onTetheringStarted" -> Log.d(TAG, "热点开启成功")
                    "onTetheringFailed" -> Log.e(TAG, "热点开启失败")
                }
                null
            }
            wifiManager.javaClass.getMethod(
                "startTethering",
                Int::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                callbackClass
            ).invoke(wifiManager, 0, true, callback)
            Log.d(TAG, "startTethering 已调用")
        } catch (e: Exception) {
            Log.e(TAG, "startTethering 失败: ${e.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun enableHotspotLegacy(wifiManager: WifiManager, apName: String, apPassword: String) {
        try {
            val config = android.net.wifi.WifiConfiguration().apply {
                SSID = apName
                preSharedKey = apPassword
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
            Log.d(TAG, "setWifiApEnabled 已调用: $apName")
        } catch (e: Exception) {
            Log.e(TAG, "setWifiApEnabled 失败: ${e.message}")
        }
    }
}
