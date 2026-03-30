package com.jiang.auto.receiver

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.jiang.auto.MyApp
import com.jiang.auto.ble.BleAutoConnectService
import com.jiang.auto.ble.BleManager

/**
 * 蓝牙状态变化广播接收器
 * 蓝牙开启时: 自动启动应用 + 触发BLE自动连接
 * 蓝牙关闭时: 停止自动连接
 */
class BleStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BleStateReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
        if (context == null) return

        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
        when (state) {
            BluetoothAdapter.STATE_ON -> {
                Log.d(TAG, "Bluetooth turned ON")
                val app = MyApp.getInstance()

                // 自动启动应用
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                }

                // 触发 BLE 自动连接
                if (app.isAutoConnectEnabled()) {
                    val devices = app.getSavedDevices()
                    if (devices.isNotEmpty()) {
                        val serviceIntent = Intent(context, BleAutoConnectService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    }
                }
            }
            BluetoothAdapter.STATE_OFF -> {
                Log.d(TAG, "Bluetooth turned OFF")
                BleManager.getInstance().stopAutoConnect()
                context.stopService(Intent(context, BleAutoConnectService::class.java))
            }
        }
    }
}
