package com.jiang.auto.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jiang.auto.MyApp

/**
 * WiFi 热点状态变化广播接收器
 * 热点开启时自动启动本应用
 */
class WifiApStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WifiApStateReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null) return

        val state = intent?.getIntExtra("wifi_state", -1) ?: -1
        // wifi_state: 13=开启中, 12=已关闭, 11=关闭中
        if (state == 13) {
            Log.d(TAG, "WiFi AP turned ON, launching app")
            val app = MyApp.getInstance()
            if (app.isAutoConnectEnabled()) {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(launchIntent)
                }
            }
        }
    }
}
