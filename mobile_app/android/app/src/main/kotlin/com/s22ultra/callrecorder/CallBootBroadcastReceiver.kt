package com.s22ultra.callrecorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Bộ thu tín hiệu hệ thống tự động đánh thức Dịch vụ Ghi âm khi:
 * 1. Khởi động lại điện thoại (`BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED`)
 * 2. Cập nhật ứng dụng (`MY_PACKAGE_REPLACED`)
 * 3. Có cuộc gọi SIM đến/đi (`android.intent.action.PHONE_STATE`) kể cả khi đã vuốt tắt hoàn toàn App!
 */
class CallBootBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val prefs = context.getSharedPreferences("s22u_recorder_prefs", Context.MODE_PRIVATE)
        val disableMode = prefs.getString("disableMode", "NONE") ?: "NONE"
        if (disableMode == "MANUAL_OFF") return

        try {
            val svcIntent = Intent(context, CallMonitorForegroundService::class.java).apply {
                action = CallMonitorForegroundService.ACTION_TRIGGER_CALL_CHECK
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svcIntent)
            } else {
                context.startService(svcIntent)
            }
        } catch (_: Exception) {
        }
    }
}
