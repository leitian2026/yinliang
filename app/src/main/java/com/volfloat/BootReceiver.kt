package com.volfloat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机后自动启动浮窗服务（在主界面关闭「启用」开关则不启动） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences("cfg", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("run_on", true)) return
        try {
            context.startForegroundService(Intent(context, FloatService::class.java))
        } catch (_: Exception) {
        }
    }
}
