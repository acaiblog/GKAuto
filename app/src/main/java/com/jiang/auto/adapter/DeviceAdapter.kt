package com.jiang.auto.adapter

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.jiang.auto.MyApp
import com.jiang.auto.R

/**
 * 蓝牙设备扫描结果列表适配器
 */
class DeviceAdapter(
    private val items: MutableList<BluetoothDevice>,
    private val onItemClick: (BluetoothDevice) -> Unit
) : RecyclerView.Adapter<DeviceAdapter.ViewHolder>() {

    // 存储点击时的 rssi 值（由 MainActivity 传入）
    private val rssiMap = mutableMapOf<String, Int>()

    fun updateRssi(address: String, rssi: Int) {
        rssiMap[address] = rssi
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("MissingPermission")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = items[position]
        holder.tvDeviceName.text = device.name ?: "未知设备"
        holder.tvDeviceMac.text = device.address

        // 区分已配对和扫描到的新设备
        val bondState = device.bondState
        when (bondState) {
            BluetoothDevice.BOND_BONDED -> {
                holder.tvRssi.text = "已配对"
                holder.tvRssi.setTextColor(0xFF999999.toInt())
            }
            BluetoothDevice.BOND_BONDING -> {
                holder.tvRssi.text = "配对中..."
                holder.tvRssi.setTextColor(0xFFFF9800.toInt())
            }
            else -> {
                holder.tvRssi.text = ""
            }
        }

        // 标记已绑定设备
        val savedDevices = MyApp.getInstance().getSavedDevices()
        if (device.address in savedDevices) {
            holder.tvDeviceName.text = "⭐ ${holder.tvDeviceName.text}"
        }

        holder.itemView.setOnClickListener {
            onItemClick(device)
        }

        // 长按删除绑定
        holder.itemView.setOnLongClickListener {
            val address = device.address
            MyApp.getInstance().removeDevice(address)
            notifyDataSetChanged()
            true
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDeviceName: TextView = view.findViewById(R.id.tvDeviceName)
        val tvDeviceMac: TextView = view.findViewById(R.id.tvDeviceMac)
        val tvRssi: TextView = view.findViewById(R.id.tvRssi)
    }
}
