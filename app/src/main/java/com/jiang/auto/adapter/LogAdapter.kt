package com.jiang.auto.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * 日志列表适配器 - 每条日志一行，支持上下滚动查看历史
 */
class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {

    private val logs = mutableListOf<String>()

    fun appendLog(msg: String, maxLines: Int = 500) {
        logs.add(msg)
        // 保留最新 maxLines 行
        while (logs.size > maxLines) {
            logs.removeAt(0)
        }
        notifyItemInserted(logs.size - 1)
    }

    fun clear() {
        val size = logs.size
        logs.clear()
        notifyItemRangeRemoved(0, size)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(com.jiang.auto.R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(logs[position])
    }

    override fun getItemCount(): Int = logs.size

    class LogViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        private val tvLog: android.widget.TextView = view.findViewById(com.jiang.auto.R.id.tvLogItem)

        fun bind(log: String) {
            tvLog.text = log
        }
    }
}
