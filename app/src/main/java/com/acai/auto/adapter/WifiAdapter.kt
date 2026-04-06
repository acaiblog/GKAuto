package com.acai.auto.adapter

import android.annotation.SuppressLint
import android.net.wifi.ScanResult
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.acai.auto.R

/**
 * WiFi热点扫描结果列表适配器
 */
class WifiAdapter(
    private val items: MutableList<ScanResult>,
    private val onItemClick: (ScanResult) -> Unit
) : RecyclerView.Adapter<WifiAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_wifi, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val scanResult = items[position]
        
        // WiFi名称
        holder.tvWifiName.text = if (scanResult.SSID.isNullOrEmpty()) {
            "<未知网络>"
        } else {
            scanResult.SSID
        }
        
        // 信号强度
        val rssi = scanResult.level
        holder.tvWifiSignal.text = "${rssi} dBm"
        holder.tvWifiSignal.setTextColor(
            when {
                rssi >= -50 -> 0xFF4CAF50.toInt()  // 绿色 - 强
                rssi >= -70 -> 0xFFFF9800.toInt()  // 橙色 - 中
                else -> 0xFFE53935.toInt()          // 红色 - 弱
            }
        )
        
        // 加密标识
        val security = getSecurityType(scanResult)
        holder.tvWifiSecure.text = if (security == "开放") "📶" else "🔒"
        holder.tvWifiSecure.visibility = View.VISIBLE
        
        holder.itemView.setOnClickListener {
            onItemClick(scanResult)
        }
    }
    
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

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvWifiName: TextView = view.findViewById(R.id.tvWifiName)
        val tvWifiSignal: TextView = view.findViewById(R.id.tvWifiSignal)
        val tvWifiSecure: TextView = view.findViewById(R.id.tvWifiSecure)
        val tvWifiIcon: TextView = view.findViewById(R.id.tvWifiIcon)
    }
}
