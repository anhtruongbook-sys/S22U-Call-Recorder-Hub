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
import java.text.Normalizer

/**
 * Dịch vụ Hệ thống Nhận diện Cuộc gọi (`NotificationListenerService` + Phân biệt Đang Đổ Chuông vs Đã Nhấc Máy):
 * - Phân biệt chính xác 2 giai đoạn của cuộc gọi Zalo / Messenger / Viber / Telegram / WhatsApp / SIM:
 *   1. Giai đoạn CHƯA NHẤC MÁY (`isCallRingingOrDialing = true`): Thông báo đang báo "Đang gọi...", "Đang đổ chuông...",
 *      "Cuộc gọi đến...", "Đang kết nối..." và chưa có đồng hồ đếm giây (`EXTRA_SHOW_CHRONOMETER == false`) -> TUYỆT ĐỐI CHƯA BẬT GHI ÂM!
 *   2. Giai đoạn ĐÃ NHẤC MÁY (`isCallAnswered = true`): Khi đầu dây bên kia nhấc máy, thông báo chuyển sang đồng hồ đếm giờ
 *      (`EXTRA_SHOW_CHRONOMETER == true` hoặc hết chữ "đang gọi/đổ chuông/cuộc gọi đến") -> BẮT ĐẦU GHI ÂM NGAY LẬP TỨC!
 * - Giữ nguyên 100% chữ Tiếng Việt có dấu chuẩn Unicode NFC cho tên người gọi (ví dụ: `Zalo_Trương_Thành_Long`).
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

        @Volatile
        var isCallRingingOrDialing: Boolean = false

        @Volatile
        var isCallAnswered: Boolean = false

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

        private val RINGING_OR_DIALING_KEYWORDS = listOf(
            "đang gọi",
            "đang đổ chuông",
            "đổ chuông",
            "cuộc gọi đến",
            "cuộc gọi thoại đến",
            "cuộc gọi video đến",
            "đang kết nối",
            "đang chờ",
            "calling",
            "ringing",
            "incoming call",
            "incoming voice call",
            "incoming video call",
            "connecting",
            "dialing"
        )

        fun sanitizeVietnameseText(input: String): String {
            val normalized = Normalizer.normalize(input.trim(), Normalizer.Form.NFC)
            val sb = StringBuilder()
            for (ch in normalized) {
                if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == ' ') {
                    sb.append(ch)
                }
            }
            return sb.toString()
                .trim()
                .replace(Regex("\\s+"), "_")
                .replace(Regex("_+"), "_")
                .take(32)
        }

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
        val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim() ?: ""
        val combinedLower = "$title $text $subText".lowercase()
        val textLower = "$text $subText".lowercase()

        val showChronometer = extras?.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) == true
        val hasTimerPattern = Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(textLower)

        val isCallRelated = sbn.isOngoing ||
                notif.category == Notification.CATEGORY_CALL ||
                combinedLower.contains("cuộc gọi") ||
                combinedLower.contains("đang gọi") ||
                combinedLower.contains("incoming call") ||
                combinedLower.contains("ongoing call") ||
                combinedLower.contains("voice call") ||
                combinedLower.contains("video call") ||
                combinedLower.contains("thoại")

        if (isCallRelated) {
            val cleanCaller = sanitizeVietnameseText(title)
            currentCallerTitle = cleanCaller
            currentActiveOttTag = if (cleanCaller.isNotEmpty() && !cleanCaller.equals(appName, ignoreCase = true)) {
                "${appName}_$cleanCaller"
            } else {
                appName
            }

            // Kiểm tra xem cuộc gọi đang ở giai đoạn ĐỔ CHUÔNG / CHƯA NHẤC MÁY hay ĐÃ NHẤC MÁY:
            val hasRingingKeyword = RINGING_OR_DIALING_KEYWORDS.any { kw -> textLower.contains(kw) }
            if (showChronometer || hasTimerPattern) {
                // Có bộ đếm thời gian cuộc gọi -> Chắc chắn 100% ĐÃ NHẤC MÁY!
                isCallRingingOrDialing = false
                isCallAnswered = true
                CallMonitorForegroundService.lastDetectedApp =
                    "Đã nhấc máy: $appName ($title) • Đang ghi âm"
            } else if (hasRingingKeyword) {
                // Đang gọi đi / Đang đổ chuông / Cuộc gọi đến (chưa nhấc máy) -> CHỜ NHẤC MÁY!
                isCallRingingOrDialing = true
                isCallAnswered = false
                CallMonitorForegroundService.lastDetectedApp =
                    "Đang chờ nhấc máy: $appName ($title) • Chưa ghi âm"
            } else {
                // Thông báo cuộc gọi đang diễn ra không còn từ khóa đổ chuông -> ĐÃ NHẤC MÁY!
                isCallRingingOrDialing = false
                isCallAnswered = true
                CallMonitorForegroundService.lastDetectedApp =
                    "Đang đàm thoại: $appName ($title)"
            }

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
                isCallRingingOrDialing = false
                isCallAnswered = false
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
