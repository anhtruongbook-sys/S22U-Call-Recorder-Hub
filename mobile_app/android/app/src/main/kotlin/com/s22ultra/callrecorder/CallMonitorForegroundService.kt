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

class CallMonitorForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "s22u_call_recorder_guard"
        private const val NOTIF_ID = 2201

        const val ACTION_START_GUARD = "com.s22ultra.callrecorder.ACTION_START_GUARD"
        const val ACTION_BANKING_STEALTH_TRIGGERED = "com.s22ultra.callrecorder.ACTION_BANKING_STEALTH_TRIGGERED"
        const val ACTION_DISABLE_ACCESSIBILITY_NOW = "com.s22ultra.callrecorder.ACTION_DISABLE_ACCESSIBILITY_NOW"
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
        val isAccNow = OTTCallAccessibilityService.instance != null

        if (!isGuardRunning) {
            val notif = buildNotification(
                contentText = if (isAccNow) {
                    "Trợ năng ĐANG BẬT • Chạm icon Ngân hàng sẽ tự tắt hoặc bấm nút dưới"
                } else {
                    "Trợ năng ĐANG TẮT (Dùng Ngân hàng thoải mái) • Bấm dưới để Bật lại"
                },
                recordingNow = false,
                accessibilityCurrentlyOn = isAccNow
            )
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
            ACTION_START_GUARD -> {
                val accOn = OTTCallAccessibilityService.instance != null
                updateNotification(
                    contentText = if (accOn) {
                        "Trợ năng ĐANG BẬT • Chạm icon Ngân hàng sẽ tự tắt hoặc bấm nút dưới"
                    } else {
                        "Trợ năng ĐANG TẮT (Dùng Ngân hàng thoải mái) • Bấm dưới để Bật lại"
                    },
                    recordingNow = false,
                    accessibilityCurrentlyOn = accOn
                )
            }
            ACTION_DISABLE_ACCESSIBILITY_NOW -> {
                OTTCallAccessibilityService.instance?.triggerInstantSelfDisable("Nút 1-chạm")
                updateNotification(
                    contentText = "ĐÃ TẮT TRỢ NĂNG! Bạn có thể mở mọi App Ngân hàng ngay bây giờ.",
                    recordingNow = false,
                    accessibilityCurrentlyOn = false
                )
            }
            ACTION_BANKING_STEALTH_TRIGGERED -> {
                val pkg = intent.getStringExtra("bankingPkg") ?: "Ngân hàng"
                lastDetectedApp = "Đã tự tắt Trợ năng cho $pkg"
                updateNotification(
                    contentText = "ĐÃ TẮT TRỢ NĂNG ($pkg). Giao dịch xong bấm [Bật Lại Trợ Năng]",
                    recordingNow = false,
                    accessibilityCurrentlyOn = false
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
                    updateNotification(
                        contentText = "ĐANG GHI ÂM CUỘC GỌI (48kHz Smooth DSP)...",
                        recordingNow = true,
                        accessibilityCurrentlyOn = OTTCallAccessibilityService.instance != null
                    )
                }
            }
            ACTION_STOP_RECORD -> {
                val activeEngine = OTTCallAccessibilityService.instance?.dspEngine ?: dspEngine
                if (activeEngine.isRecording) {
                    activeEngine.stopRecording()
                    val accOn = OTTCallAccessibilityService.instance != null
                    updateNotification(
                        contentText = "Đã lưu cuộc gọi gần nhất",
                        recordingNow = false,
                        accessibilityCurrentlyOn = accOn
                    )
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
            if (OTTCallAccessibilityService.instance == null) {
                tryAutoRestoreAccessibilityIfPermitted()
            }
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
                updateNotification(
                    "Đã lưu cuộc gọi gần nhất",
                    false,
                    OTTCallAccessibilityService.instance != null
                )
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
                description = "Tự động tắt Trợ năng khi mở App Ngân hàng và nút Bật/Tắt 1-chạm trên thanh thông báo"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        contentText: String,
        recordingNow: Boolean,
        accessibilityCurrentlyOn: Boolean
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingLaunch = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("S22U Call Recorder Hub")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingLaunch)
            .setOngoing(true)

        // Nút 1: Nếu Trợ năng đang BẬT -> Hiện nút [Tắt Trợ Năng (Dùng Ngân Hàng)] (tắt tức thì trong 0.001s không cần vào Settings!)
        //        Nếu Trợ năng đang TẮT -> Hiện nút [Bật Lại Trợ Năng]
        if (accessibilityCurrentlyOn) {
            val disableIntent = Intent(this, CallMonitorForegroundService::class.java).apply {
                action = ACTION_DISABLE_ACCESSIBILITY_NOW
            }
            val pendingDisable = PendingIntent.getService(
                this,
                4,
                disableIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_secure,
                "Tắt Trợ Năng (Dùng NH)",
                pendingDisable
            )
        } else {
            val accIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pendingAcc = PendingIntent.getActivity(
                this,
                3,
                accIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_lock_power_off,
                "Bật Lại Trợ Năng",
                pendingAcc
            )
        }

        // Nút 2: Ghi Âm Ngay / Dừng & Lưu File
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
            builder.addAction(android.R.drawable.ic_media_pause, "Dừng & Lưu", pendingStop)
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
            builder.addAction(android.R.drawable.ic_btn_speak_now, "Ghi Ngay", pendingRec)
        }

        return builder.build()
    }

    private fun updateNotification(
        contentText: String,
        recordingNow: Boolean,
        accessibilityCurrentlyOn: Boolean
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID, buildNotification(contentText, recordingNow, accessibilityCurrentlyOn))
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
