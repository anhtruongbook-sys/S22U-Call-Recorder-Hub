package com.s22ultra.callrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Dịch vụ Điều phối Nền & Tự động Khôi phục Trợ năng sau khi dùng App Ngân hàng (v1.5.0):
 * - Khi OTTCallAccessibilityService tự động tắt (`disableSelf()`) lúc người dùng mở App Ngân hàng:
 *   1. Nếu máy có quyền WRITE_SECURE_SETTINGS: Tự động bật lại ngầm Trợ năng ngay khi có cuộc gọi mới!
 *   2. Nếu máy chưa cấp WRITE_SECURE_SETTINGS: Hiển thị nút 1-chạm [Bật lại Trợ năng] ngay trên thanh thông báo
 *      và vẫn duy trì thu âm dự phòng qua Foreground Microphone.
 */
class CallMonitorForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "s22u_call_recorder_guard"
        private const val NOTIF_ID = 2201

        const val ACTION_START_GUARD = "com.s22ultra.callrecorder.ACTION_START_GUARD"
        const val ACTION_BANKING_STEALTH_TRIGGERED = "com.s22ultra.callrecorder.ACTION_BANKING_STEALTH_TRIGGERED"
        const val ACTION_QUICK_RECORD = "com.s22ultra.callrecorder.ACTION_QUICK_RECORD"
        const val ACTION_STOP_RECORD = "com.s22ultra.callrecorder.ACTION_STOP_RECORD"

        @Volatile
        var instance: CallMonitorForegroundService? = null
            private set

        @Volatile
        var isGuardRunning: Boolean = false
            private set

        @Volatile
        var autoRecordOttEnabled: Boolean = true

        @Volatile
        var remoteGainBoost: Float = 2.2f

        @Volatile
        var autoBoostVolume: Boolean = true

        @Volatile
        var lastDetectedApp: String = "Sẵn sàng (Smart Banking Stealth)"

        val SUPPORTED_OTT_PACKAGES = mapOf(
            "com.zing.zalo" to "Zalo",
            "com.viber.voip" to "Viber",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business"
        )
    }

    lateinit var dspEngine: AudioDspRecorderEngine
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var wasInActiveCall = false

    private val audioModePoller = object : Runnable {
        override fun run() {
            checkAudioState()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        dspEngine = AudioDspRecorderEngine(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (!isGuardRunning) {
            val notif = buildNotification("Trực chiến thông minh (Tự ẩn khi mở App Ngân hàng)", false, false)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIF_ID,
                        notif,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                        } else {
                            0
                        }
                    )
                } else {
                    startForeground(NOTIF_ID, notif)
                }
                isGuardRunning = true
                handler.removeCallbacks(audioModePoller)
                handler.post(audioModePoller)
            } catch (_: Exception) {
            }
        }

        when (action) {
            ACTION_BANKING_STEALTH_TRIGGERED -> {
                val pkg = intent.getStringExtra("bankingPkg") ?: "Ngân hàng"
                lastDetectedApp = "Đã tự tắt Trợ năng cho $pkg"
                updateNotification(
                    "Đã tự tắt Trợ năng để bạn dùng App Ngân hàng. Xong bấm [Bật Lại Trợ Năng]",
                    recordingNow = false,
                    showReEnableAccessibility = true
                )
            }
            ACTION_QUICK_RECORD -> {
                val activeEngine = OTTCallAccessibilityService.instance?.dspEngine ?: dspEngine
                if (!activeEngine.isRecording) {
                    activeEngine.startRecording(
                        appTag = "OTT_Call",
                        remoteGainBoost = OTTCallAccessibilityService.remoteGainBoost,
                        autoBoostEarpieceVolume = OTTCallAccessibilityService.autoBoostVolume
                    )
                    updateNotification("ĐANG GHI ÂM CUỘC GỌI (48kHz Smooth DSP)...", true, false)
                }
            }
            ACTION_STOP_RECORD -> {
                val activeEngine = OTTCallAccessibilityService.instance?.dspEngine ?: dspEngine
                if (activeEngine.isRecording) {
                    activeEngine.stopRecording()
                    updateNotification("Đã lưu cuộc gọi gần nhất", false, false)
                }
            }
        }

        return START_STICKY
    }

    private fun tryAutoRestoreAccessibilityIfPermitted() {
        try {
            val comp = ComponentName(this, OTTCallAccessibilityService::class.java).flattenToString()
            val current = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            if (!current.contains(comp)) {
                val updated = if (current.isBlank()) comp else "$current:$comp"
                Settings.Secure.putString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
                Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
        } catch (_: Exception) {
        }
    }

    private fun checkAudioState() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = am.mode
        val isCall = (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL)

        if (isCall && !wasInActiveCall) {
            wasInActiveCall = true
            // Nếu máy có quyền WRITE_SECURE_SETTINGS, tự động bật lại Trợ năng ngay khi có cuộc gọi!
            if (OTTCallAccessibilityService.instance == null) {
                tryAutoRestoreAccessibilityIfPermitted()
            }
            // Nếu OTTCallAccessibilityService chưa bật lại, ForegroundService tự động thu âm luôn!
            if (OTTCallAccessibilityService.instance == null && autoRecordOttEnabled && !dspEngine.isRecording) {
                val tag = if (mode == AudioManager.MODE_IN_CALL) "SIM_Call" else "OTT_Call"
                dspEngine.startRecording(
                    appTag = tag,
                    remoteGainBoost = OTTCallAccessibilityService.remoteGainBoost,
                    autoBoostEarpieceVolume = OTTCallAccessibilityService.autoBoostVolume
                )
                updateNotification("ĐANG GHI ÂM $tag (48kHz Smooth DSP)...", true, false)
            }
        } else if (!isCall && wasInActiveCall) {
            wasInActiveCall = false
            if (dspEngine.isRecording && dspEngine.currentAppTag != "Manual") {
                dspEngine.stopRecording()
                updateNotification("Đã lưu cuộc gọi gần nhất", false, false)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "S22U Smart Banking Stealth Guard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tự động tắt Trợ năng khi mở App Ngân hàng và thông báo bật lại 1-chạm"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        contentText: String,
        recordingNow: Boolean,
        showReEnableAccessibility: Boolean
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingLaunch = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("S22U Call Recorder (Smart Banking Stealth)")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingLaunch)
            .setOngoing(true)

        if (showReEnableAccessibility) {
            val accIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingAcc = PendingIntent.getActivity(
                this,
                3,
                accIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_lock_power_off, "Bật Lại Trợ Năng", pendingAcc)
        }

        if (recordingNow) {
            val stopIntent = Intent(this, CallMonitorForegroundService::class.java).apply {
                action = ACTION_STOP_RECORD
            }
            val pendingStop = PendingIntent.getService(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_media_pause, "Dừng & Lưu File", pendingStop)
        } else {
            val recIntent = Intent(this, CallMonitorForegroundService::class.java).apply {
                action = ACTION_QUICK_RECORD
            }
            val pendingRec = PendingIntent.getService(
                this,
                2,
                recIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_btn_speak_now, "Ghi Âm Ngay", pendingRec)
        }

        return builder.build()
    }

    private fun updateNotification(
        contentText: String,
        recordingNow: Boolean,
        showReEnableAccessibility: Boolean
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID, buildNotification(contentText, recordingNow, showReEnableAccessibility))
    }

    override fun onDestroy() {
        handler.removeCallbacks(audioModePoller)
        if (::dspEngine.isInitialized && dspEngine.isRecording) {
            dspEngine.stopRecording()
        }
        isGuardRunning = false
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
