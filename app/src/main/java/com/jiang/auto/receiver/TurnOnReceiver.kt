package com.jiang.auto.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.jiang.auto.MyApp
import com.jiang.auto.ble.BleAutoConnectService

/**
 * 开机广播接收器
 * 开机后自动: 1. 启动本应用 2. 开启蓝牙 3. 开启热点 4. 触发BLE自动连接
 */
class TurnOnReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TurnOnReceiver"
    }

    @SuppressLint("MissingPermission", "PrivateApi")
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (context == null) return

        Log.d(TAG, "Boot completed received")

        val app = MyApp.getInstance()

        // 1. 启动本应用
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
            Log.d(TAG, "App launched on boot")
        }

        // 2. 开启蓝牙
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter != null && !adapter.isEnabled) {
                adapter.enable()
                Log.d(TAG, "Bluetooth enable requested")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable bluetooth", e)
        }

        // 3. 开启 WiFi 热点
        if (app.isAutoConnectEnabled()) {
            try {
                val apName = app.getApName()
                val apPassword = app.getApPassword()
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

                // Android 12+: 先配置热点名称和密码
                if (Build.VERSION.SDK_INT >= 31) {
                    try {
                        val builderClass = Class.forName("android.net.wifi.WifiManager\$SoftApConfiguration\$Builder")
                        val builder = builderClass.getDeclaredConstructor().newInstance()
                        builderClass.getMethod("setSsid", String::class.java).invoke(builder, apName)
                        builderClass.getMethod("setWpa2Passphrase", String::class.java).invoke(builder, apPassword)
                        val softApConfig = builderClass.getMethod("build").invoke(builder)
                        wifiManager.javaClass.getMethod("setSoftApConfiguration", softApConfig.javaClass)
                            .invoke(wifiManager, softApConfig)
                        Log.d(TAG, "Boot: SoftApConfiguration set: $apName")
                    } catch (e: Exception) {
                        Log.e(TAG, "Boot: SoftApConfiguration failed: ${e.message}")
                    }
                }

                // Android 8+: 使用 startTethering
                if (Build.VERSION.SDK_INT >= 26) {
                    try {
                        val callbackClass = Class.forName("android.net.wifi.WifiManager\$OnStartTetheringCallback")
                        val callback = java.lang.reflect.Proxy.newProxyInstance(
                            callbackClass.classLoader, arrayOf(callbackClass)
                        ) { _, method, _ ->
                            when (method?.name) {
                                "onTetheringStarted" -> Log.d(TAG, "Boot: 热点开启成功")
                                "onTetheringFailed" -> Log.e(TAG, "Boot: 热点开启失败")
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
                        Log.d(TAG, "Boot: WiFi AP starting (startTethering)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Boot: startTethering failed", e)
                    }
                } else {
                    // 旧版: setWifiApEnabled
                    try {
                        val config = android.net.wifi.WifiConfiguration().apply {
                            SSID = "\"$apName\""
                            preSharedKey = "\"$apPassword\""
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
                        Log.d(TAG, "Boot: WiFi AP starting (setWifiApEnabled)")
                    } catch (e: Exception) {
                        Log.e(TAG, "Boot: setWifiApEnabled failed", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to enable WiFi AP on boot", e)
            }

            // 4. 启动 BLE 自动连接服务
            val devices = app.getSavedDevices()
            if (devices.isNotEmpty()) {
                Log.d(TAG, "Starting auto-connect service with ${devices.size} devices")
                val serviceIntent = Intent(context, BleAutoConnectService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            }
        }
    }
}
