package com.s22ultra.callrecorder

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Dịch vụ Hệ thống Nhận diện Cuộc gọi (`NotificationListenerService` + Ngắt Phần cứng Audio):
 * - Được hệ điều hành Android giữ kết nối 24/7 kể cả khi người dùng vuốt đóng (tắt) hoàn toàn App khỏi Đa nhiệm!
 * - Khi có cuộc gọi đến/đi (Zalo, Viber, Telegram, WhatsApp, Messenger hoặc SIM) lúc App đang tắt:
 *   tự động đánh thức `CallMonitorForegroundService` để ghi âm ngay lập tức!
 * - HOÀN TOÀN KHÔNG DÙNG TRỢ NĂNG (`Zero-Accessibility`) -> An toàn 100% với mọi App Ngân hàng & VNeID.
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private val recCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            super.onRecordingConfigChanged(configs)
            mainHandler.post {
                onHardwareAudioInterrupt()
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        registerHardwareWakeupInterrupts()
        ensureForegroundGuardRunning()
    }

    override fun onListenerDisconnected() {
        isConnected = false
        unregisterHardwareWakeupInterrupts()
        super.onListenerDisconnected()
    }

    private fun registerHardwareWakeupInterrupts() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modeListener == null) {
                val l = AudioManager.OnModeChangedListener { _ ->
                    mainHandler.post {
                        onHardwareAudioInterrupt()
                    }
                }
                am.addOnModeChangedListener(mainExecutor, l)
                modeListener = l
            }
            am.registerAudioRecordingCallback(recCallback, mainHandler)
        } catch (_: Exception) {
        }
    }

    private fun unregisterHardwareWakeupInterrupts() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modeListener?.let { am?.removeOnModeChangedListener(it) }
            }
            am?.unregisterAudioRecordingCallback(recCallback)
        } catch (_: Exception) {
        }
        modeListener = null
    }

    /**
     * Khi App đã bị vuốt tắt khỏi Đa nhiệm nhưng có cuộc gọi Zalo/SIM nổ ra:
     * Ngắt phần cứng này tự động khởi chạy lại `CallMonitorForegroundService` và thu âm ngay lập tức!
     */
    private fun onHardwareAudioInterrupt() {
        val prefs = getSharedPreferences("s22u_recorder_prefs", Context.MODE_PRIVATE)
        if (prefs.getString("disableMode", "NONE") == "MANUAL_OFF") return

        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = am.mode
        if (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) {
            ensureForegroundGuardRunning()
            CallMonitorForegroundService.instance?.evaluateAudioHardwareInterrupt()
        } else {
            CallMonitorForegroundService.instance?.evaluateAudioHardwareInterrupt()
        }
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
            CallMonitorForegroundService.instance?.evaluateAudioHardwareInterrupt()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val pkg = sbn.packageName ?: return
        if (OTT_PACKAGES.containsKey(pkg)) {
            if (sbn.isOngoing || sbn.notification?.category == Notification.CATEGORY_CALL) {
                currentActiveOttTag = null
                currentCallerTitle = ""
                CallMonitorForegroundService.instance?.evaluateAudioHardwareInterrupt()
            }
        }
    }

    private fun ensureForegroundGuardRunning() {
        val prefs = getSharedPreferences("s22u_recorder_prefs", Context.MODE_PRIVATE)
        if (prefs.getString("disableMode", "NONE") == "MANUAL_OFF") return
        if (CallMonitorForegroundService.disableMode == "MANUAL_OFF") return
        if (!CallMonitorForegroundService.isGuardRunning) {
            try {
                val svcIntent = Intent(this, CallMonitorForegroundService::class.java).apply {
                    action = CallMonitorForegroundService.ACTION_TRIGGER_CALL_CHECK
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
