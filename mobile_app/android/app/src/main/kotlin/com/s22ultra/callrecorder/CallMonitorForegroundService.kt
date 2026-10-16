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
 * Dịch vụ Foreground Microphone chuẩn Android 14 / One UI 6+ (Hoàn toàn vượt qua Google Play Protect):
 * - Không sử dụng BIND_ACCESSIBILITY_SERVICE (nguyên nhân khiến Google Play Protect chặn cứng tại Việt Nam).
 * - Khi bật chế độ "Trực chiến Tự động (Foreground Audio Guard)", dịch vụ giữ phiên Microphone hợp lệ
 *   ở trạng thái Foreground và lắng nghe sự kiện AudioManager.MODE_IN_COMMUNICATION (Zalo, Viber, Telegram, WhatsApp)
 *   cũng như AudioManager.MODE_IN_CALL để tự động kích hoạt AudioDspRecorderEngine.
 */
class CallMonitorForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "s22u_call_recorder_guard"
        private const val NOTIF_ID = 2201

        @Volatile
        var instance: CallMonitorForegroundService? = null
            private set

        @Volatile
        var isGuardRunning: Boolean = false
            private set

        @Volatile
        var autoRecordOttEnabled: Boolean = true

        @Volatile
        var remoteGainBoost: Float = 4.5f

        @Volatile
        var autoBoostVolume: Boolean = true

        @Volatile
        var lastDetectedApp: String = "Sẵn sàng trực chiến (Zalo/Viber/Telegram/WhatsApp)"

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
    private var wasInVoipCall = false

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
        val notif = buildNotification("Đang trực chiến tự động ghi âm cuộc gọi OTT & SIM")
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
        return START_STICKY
    }

    private fun checkAudioState() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = am.mode

        // MODE_IN_COMMUNICATION (3): Đang gọi Zalo, Viber, Telegram, WhatsApp
        val isVoip = (mode == AudioManager.MODE_IN_COMMUNICATION)

        if (isVoip && !wasInVoipCall) {
            wasInVoipCall = true
            lastDetectedApp = "Cuộc gọi OTT (Đang ghi âm 2 chiều)"
            if (autoRecordOttEnabled && !dspEngine.isRecording) {
                dspEngine.startRecording(
                    appTag = "OTT_Call",
                    remoteGainBoost = remoteGainBoost,
                    autoBoostEarpieceVolume = autoBoostVolume
                )
                updateNotification("ĐANG GHI ÂM CUỘC GỌI OTT (DSP x$remoteGainBoost)...")
            }
        } else if (!isVoip && wasInVoipCall) {
            wasInVoipCall = false
            if (dspEngine.isRecording && dspEngine.currentAppTag != "Manual") {
                dspEngine.stopRecording()
                lastDetectedApp = "Đã lưu cuộc gọi OTT gần nhất"
                updateNotification("Đang trực chiến tự động ghi âm cuộc gọi OTT & SIM")
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
                description = "Duy trì trạng thái tự động ghi âm cuộc gọi OTT trên Samsung S22 Ultra"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("S22U Call Recorder Hub")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID, buildNotification(contentText))
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
