package com.s22ultra.callrecorder

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Dịch vụ nhận diện cuộc gọi Zalo, Viber, Telegram, WhatsApp, Messenger qua Thông báo hệ thống
 * (`NotificationListenerService`) — HOÀN TOÀN KHÔNG DÙNG TRỢ NĂNG (ZERO ACCESSIBILITY):
 * - Không bao giờ bị VCB Digibank, MB Bank, BIDV, Techcombank, VNeID chặn!
 * - Không bao giờ bị Google Play Protect cảnh báo!
 * - Luôn bật 24/7 vĩnh viễn: Không cần tắt khi vào Ngân hàng và KHÔNG BAO GIỜ phải bật lại bằng tay!
 */
class OTTCallNotificationListener : NotificationListenerService() {

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set

        @Volatile
        var currentActiveOttTag: String? = null

        @Volatile
        var currentCallerTitle: String = ""

        val OTT_PACKAGES = mapOf(
            "com.zing.zalo" to "Zalo",
            "com.viber.voip" to "Viber",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp_Business",
            "com.facebook.orca" to "Messenger",
            "com.samsung.android.incallui" to "SIM_Call",
            "com.samsung.android.dialer" to "SIM_Call"
        )

        fun isNotificationListenerEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val cn = ComponentName(context, OTTCallNotificationListener::class.java).flattenToString()
            return flat.contains(cn) || flat.contains("${context.packageName}/.OTTCallNotificationListener")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        ensureForegroundGuardRunning()
    }

    override fun onListenerDisconnected() {
        isConnected = false
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        val appName = OTT_PACKAGES[pkg] ?: return

        val notif = sbn.notification ?: return
        val extras = notif.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val combined = "$title $text".lowercase()

        val isOngoingOrCall = sbn.isOngoing ||
                notif.category == Notification.CATEGORY_CALL ||
                combined.contains("cuộc gọi") ||
                combined.contains("đang gọi") ||
                combined.contains("incoming call") ||
                combined.contains("ongoing call") ||
                combined.contains("voice call") ||
                combined.contains("video call") ||
                combined.contains("thoại")

        if (isOngoingOrCall) {
            val cleanCaller = title
                .replace(Regex("[^\\p{L}\\p{N}_\\- ]"), "")
                .trim()
                .take(24)
                .replace(" ", "_")
            currentCallerTitle = cleanCaller
            currentActiveOttTag = if (cleanCaller.isNotEmpty() && !cleanCaller.equals(appName, ignoreCase = true)) {
                "${appName}_$cleanCaller"
            } else {
                appName
            }
            CallMonitorForegroundService.lastDetectedApp = "Phát hiện cuộc gọi: $appName ($title)"
            ensureForegroundGuardRunning()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (OTT_PACKAGES.containsKey(pkg)) {
            if (sbn.isOngoing || sbn.notification?.category == Notification.CATEGORY_CALL) {
                currentActiveOttTag = null
                currentCallerTitle = ""
            }
        }
    }

    private fun ensureForegroundGuardRunning() {
        if (!CallMonitorForegroundService.isGuardRunning) {
            try {
                val svcIntent = Intent(this, CallMonitorForegroundService::class.java).apply {
                    action = CallMonitorForegroundService.ACTION_START_GUARD
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svcIntent)
                } else {
                    startService(svcIntent)
                }
            } catch (_: Exception) {
            }
        }
    }
}
