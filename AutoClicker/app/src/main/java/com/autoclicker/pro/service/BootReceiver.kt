package com.autoclicker.pro.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

private const val TAG = "BootReceiver"

/**
 * 開機廣播接收器
 * 系統重啟後可選擇恢復懸浮窗（需使用者手動設定是否啟用）
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i(TAG, "Boot completed received")
        // 根據 SharedPreferences 決定是否自動啟動
        val prefs = context.getSharedPreferences("autoclicker", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("auto_start_on_boot", false)
        if (autoStart) {
            val serviceIntent = Intent(context,
                com.autoclicker.pro.overlay.OverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}
