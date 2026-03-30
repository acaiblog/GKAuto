package com.jiang.auto.ble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.jiang.auto.activity.MainActivity
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * BLE 自动连接前台服务
 * 在后台持续运行，尝试连接已保存的 BLE 设备
 */
class BleAutoConnectService : Service() {

    companion object {
        private const val TAG = "BleAutoConnectService"
        private const val CHANNEL_ID = "ble_auto"
        private const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("BLE自动连接运行中"))
        BleManager.getInstance().init(this)
        // 只有未连接时才启动自动连接，避免与 MainActivity 的连接逻辑冲突
        if (!BleManager.getInstance().isConnected) {
            BleManager.getInstance().startAutoConnect()
            Log.d(TAG, "Service started, auto-connect enabled")
        } else {
            Log.d(TAG, "Service started, already connected, skip auto-connect")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        BleManager.getInstance().stopAutoConnect()
        Log.d(TAG, "Service stopped, auto-connect disabled")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE自动连接",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "BLE自动连接后台服务"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            flags
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("GK Auto")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("GK Auto")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pendingIntent)
                .setAutoCancel(false)
                .build()
        }
    }
}
