package com.s22ultra.callrecorder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * Dịch vụ Foreground Microphone chuẩn Android 14-16 (100% Tương thích App Ngân hàng & Google Play Protect):
 * - Hoàn toàn KHÔNG dùng AccessibilityService (Trợ năng), giúp các ứng dụng Ngân hàng (VCB, BIDV, MB, Techcombank, VNeID...)
 *   hoạt động bình thường 100% theo quy định bảo mật NHNN.
 * - Giám sát trạng thái AudioManager.MODE_IN_COMMUNICATION (Zalo, Viber, Telegram, WhatsApp) & MODE_IN_CALL (SIM)
 *   kết hợp nút bấm điều khiển nhanh [Ghi Âm Ngay / Dừng & Lưu] ngay trên thanh thông báo (Notification Shade).
 */
class CallMonitorForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "s22u_call_recorder_guard"
        private const val NOTIF_ID = 2201

        const val ACTION_START_GUARD = "com.s22ultra.callrecorder.ACTION_START_GUARD"
        const val ACTION_QUICK_RECORD = "com.s22ultra.callrecorder.ACTION_QUICK_RECORD"
        const val ACTION_STOP_RECORD = "com.s22ultra.callrecorder.ACTION_STOP_RECORD"
        const val ACTION_STOP_GUARD = "com.s22ultra.callrecorder.ACTION_STOP_GUARD"

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
        var lastDetectedApp: String = "Sẵn sàng trực chiến (An toàn App Ngân hàng)"

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

        if (action == ACTION_STOP_GUARD) {
            if (dspEngine.isRecording) {
                dspEngine.stopRecording()
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isGuardRunning) {
            val notif = buildNotification("Đang trực chiến tự động (An toàn App Ngân hàng)", false)
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
            ACTION_QUICK_RECORD -> {
                if (!dspEngine.isRecording) {
                    dspEngine.startRecording(
                        appTag = "OTT_Call",
                        remoteGainBoost = remoteGainBoost,
                        autoBoostEarpieceVolume = autoBoostVolume
                    )
                    lastDetectedApp = "Đang ghi âm cuộc gọi (48kHz Smooth DSP)"
                    updateNotification("ĐANG GHI ÂM CUỘC GỌI (48kHz DSP x$remoteGainBoost)...", true)
                }
            }
            ACTION_STOP_RECORD -> {
                if (dspEngine.isRecording) {
                    dspEngine.stopRecording()
                    lastDetectedApp = "Đã lưu bản ghi âm gần nhất"
                    updateNotification("Đang trực chiến tự động (An toàn App Ngân hàng)", false)
                }
            }
        }

        return START_STICKY
    }

    private fun checkAudioState() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = am.mode

        // MODE_IN_COMMUNICATION (3): Zalo, Viber, Telegram, WhatsApp
        // MODE_IN_CALL (2): Cuộc gọi SIM thông thường
        val isCall = (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL)

        if (isCall && !wasInActiveCall) {
            wasInActiveCall = true
            val tag = if (mode == AudioManager.MODE_IN_CALL) "SIM_Call" else "OTT_Call"
            lastDetectedApp = "$tag (Đang đàm thoại)"
            if (autoRecordOttEnabled && !dspEngine.isRecording) {
                dspEngine.startRecording(
                    appTag = tag,
                    remoteGainBoost = remoteGainBoost,
                    autoBoostEarpieceVolume = autoBoostVolume
                )
                updateNotification("ĐANG GHI ÂM $tag (48kHz DSP x$remoteGainBoost)...", true)
            }
        } else if (!isCall && wasInActiveCall) {
            wasInActiveCall = false
            if (dspEngine.isRecording && dspEngine.currentAppTag != "Manual") {
                dspEngine.stopRecording()
                lastDetectedApp = "Đã lưu cuộc gọi gần nhất"
                updateNotification("Đang trực chiến tự động (An toàn App Ngân hàng)", false)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "S22U Call Recorder Guard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Trực chiến ghi âm cuộc gọi và điều khiển nhanh trên thanh thông báo"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String, recordingNow: Boolean): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingLaunch = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("S22U Call Recorder (Banking-Safe)")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingLaunch)
            .setOngoing(true)

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

    private fun updateNotification(contentText: String, recordingNow: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID, buildNotification(contentText, recordingNow))
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
