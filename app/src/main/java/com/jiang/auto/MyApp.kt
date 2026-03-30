package com.jiang.auto

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences

class MyApp : Application() {

    companion object {
        const val BLE_AUTO = "BLE_AUTO"
        private const val PREFS_NAME = "ble_devices"
        private const val KEY_DEVICE_LIST = "device_list"
        private const val KEY_AUTO_CONNECT = "auto_connect_enabled"
        private const val KEY_BOOT_START = "boot_start_enabled"
        private const val KEY_AP_NAME = "ap_name"
        private const val KEY_AP_PASSWORD = "ap_password"
        private const val KEY_BOUND_APP_PACKAGE = "bound_app_package"
        private const val KEY_BOUND_APP_NAME = "bound_app_name"

        @Volatile
        private lateinit var instance: MyApp

        fun getInstance(): MyApp = instance
    }

    var bluetoothAdapter: BluetoothAdapter? = null
        private set

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        instance = this
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ========== 设备绑定 ==========

    fun getSavedDevices(): List<String> {
        val list = prefs.getString(KEY_DEVICE_LIST, "") ?: ""
        if (list.isEmpty()) return emptyList()
        return list.split(",").filter { it.isNotEmpty() }
    }

    fun saveDevice(macAddress: String) {
        val current = getSavedDevices().toMutableList()
        if (macAddress !in current) {
            current.add(macAddress)
            prefs.edit().putString(KEY_DEVICE_LIST, current.joinToString(",")).apply()
        }
    }

    fun removeDevice(macAddress: String) {
        val current = getSavedDevices().toMutableList()
        current.remove(macAddress)
        prefs.edit().putString(KEY_DEVICE_LIST, current.joinToString(",")).apply()
    }

    // ========== 自动连接 ==========

    fun isAutoConnectEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_CONNECT, false)
    }

    fun setAutoConnectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_CONNECT, enabled).apply()
    }

    // ========== 开机自启 ==========

    fun isBootStartEnabled(): Boolean {
        return prefs.getBoolean(KEY_BOOT_START, false)
    }

    fun setBootStartEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_BOOT_START, enabled).apply()
    }

    // ========== 热点配置 ==========

    fun getApName(): String {
        return prefs.getString(KEY_AP_NAME, "GKAuto_Hotspot") ?: "GKAuto_Hotspot"
    }

    fun setApName(name: String) {
        prefs.edit().putString(KEY_AP_NAME, name).apply()
    }

    fun getApPassword(): String {
        return prefs.getString(KEY_AP_PASSWORD, "12345678") ?: "12345678"
    }

    fun setApPassword(password: String) {
        prefs.edit().putString(KEY_AP_PASSWORD, password).apply()
    }

    fun getBleAutoMode(): String {
        return BLE_AUTO
    }

    // ========== 绑定应用 ==========

    fun getBoundAppPackage(): String {
        return prefs.getString(KEY_BOUND_APP_PACKAGE, "") ?: ""
    }

    fun getBoundAppName(): String {
        return prefs.getString(KEY_BOUND_APP_NAME, "") ?: ""
    }

    fun setBoundApp(packageName: String, appName: String) {
        prefs.edit()
            .putString(KEY_BOUND_APP_PACKAGE, packageName)
            .putString(KEY_BOUND_APP_NAME, appName)
            .apply()
    }

    fun clearBoundApp() {
        prefs.edit()
            .remove(KEY_BOUND_APP_PACKAGE)
            .remove(KEY_BOUND_APP_NAME)
            .apply()
    }

    /**
     * 启动绑定的应用，成功返回 true
     */
    fun launchBoundApp(): Boolean {
        val pkg = getBoundAppPackage()
        if (pkg.isEmpty()) return false
        return try {
            val intent = instance.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                instance.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }
}
