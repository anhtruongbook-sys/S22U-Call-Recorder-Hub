package com.s22ultra.callrecorder

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * Dịch vụ Trực chiến Ghi âm 24/7 Vĩnh viễn (Zero-Accessibility Architecture v1.9.0):
 * - KHÔNG SỬ DỤNG AccessibilityService -> Mở VCB Digibank, MB Bank, BIDV, Techcombank, VNeID 24/7
 *   mà KHÔNG BAO GIỜ bị chặn, KHÔNG CẦN TẮT và KHÔNG BAO GIỜ PHẢI BẬT LẠI BẰNG TAY!
 * - Kết hợp 3 tầng công nghệ hợp chuẩn Android 16:
 *   1. `FOREGROUND_SERVICE_TYPE_MICROPHONE` + `AudioManager` (`MODE_IN_COMMUNICATION` / `MODE_IN_CALL`)
 *   2. `OTTCallNotificationListener` + `UsageStatsManager` để nhận diện chính xác tên ứng dụng
 *      (`Zalo`, `Viber`, `Telegram`, `WhatsApp`, `Messenger`) và tên người gọi.
 *   3. `1x1 Pixel Transparent Audio Anchor` (`TYPE_APPLICATION_OVERLAY` chỉ gắn trong lúc đang có cuộc gọi)
 *      giúp Android 16 cấp quyền thu âm song song (`hasOverlayUi = true`) cùng lúc với Zalo/OTT.
 */
class CallMonitorForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "s22u_call_recorder_guard"
        private const val NOTIF_ID = 2201

        const val ACTION_START_GUARD = "com.s22ultra.callrecorder.ACTION_START_GUARD"
        const val ACTION_STOP_GUARD_MANUAL = "com.s22ultra.callrecorder.ACTION_STOP_GUARD_MANUAL"
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
        var disableMode: String = "NONE"

        val SUPPORTED_OTT_PACKAGES = mapOf(
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

        fun hasOverlayPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Settings.canDrawOverlays(context)
            } else {
                true
            }
        }

        fun hasUsageStatsPermission(context: Context): Boolean {
            return try {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
                val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    appOps.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName
                    )
                } else {
                    @Suppress("DEPRECATION")
                    appOps.checkOpNoThrow(
                        AppOpsManager.OPSTR_GET_USAGE_STATS,
                        Process.myUid(),
                        context.packageName
                    )
                }
                mode == AppOpsManager.MODE_ALLOWED
            } catch (_: Exception) {
                false
            }
        }
    }

    lateinit var dspEngine: AudioDspRecorderEngine
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var wasInActiveCall = false
    private var audioAnchorView: View? = null

    private val guardPoller = object : Runnable {
        override fun run() {
            checkAudioState()
            handler.postDelayed(this, 800L)
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

        if (action == ACTION_STOP_GUARD_MANUAL) {
            disableMode = "MANUAL_OFF"
            autoRecordOttEnabled = false
            OTTCallAccessibilityService.autoRecordOttEnabled = false
            OTTCallAccessibilityService.lastDetectedApp = "Đã tắt chủ động trong App"
            if (dspEngine.isRecording) {
                dspEngine.stopRecording()
            }
            detachCallAudioAnchor()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            isGuardRunning = false
            return START_NOT_STICKY
        }

        if (!isGuardRunning) {
            val notif = buildNotification(
                contentText = "Đang trực chiến 24/7 (An toàn 100% với mọi App Ngân hàng & VNeID)",
                recordingNow = false
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
                disableMode = "NONE"
                OTTCallAccessibilityService.lastDetectedApp =
                    "Trực chiến 24/7 ĐANG BẬT (Dùng Ngân hàng & VNeID thoải mái)"
                handler.removeCallbacks(guardPoller)
                handler.post(guardPoller)
            } catch (_: Exception) {
            }
        }

        when (action) {
            ACTION_START_GUARD -> {
                disableMode = "NONE"
                autoRecordOttEnabled = true
                OTTCallAccessibilityService.autoRecordOttEnabled = true
                OTTCallAccessibilityService.lastDetectedApp =
                    "Trực chiến 24/7 ĐANG BẬT (Dùng Ngân hàng & VNeID thoải mái)"
                updateNotification(
                    contentText = "Đang trực chiến 24/7 (An toàn 100% với mọi App Ngân hàng & VNeID)",
                    recordingNow = dspEngine.isRecording
                )
            }

            ACTION_QUICK_RECORD -> {
                if (!dspEngine.isRecording) {
                    attachCallAudioAnchorIfPermitted()
                    val detectedTag = resolveActiveCallTag(AudioManager.MODE_IN_COMMUNICATION)
                    dspEngine.startRecording(
                        appTag = detectedTag,
                        remoteGainBoost = remoteGainBoost,
                        autoBoostEarpieceVolume = autoBoostVolume
                    )
                    OTTCallAccessibilityService.lastDetectedApp = "$detectedTag (Đang ghi âm)"
                    updateNotification(
                        contentText = "ĐANG GHI ÂM $detectedTag (48kHz Smooth DSP)...",
                        recordingNow = true
                    )
                }
            }

            ACTION_STOP_RECORD -> {
                if (dspEngine.isRecording) {
                    val saved = dspEngine.stopRecording()
                    detachCallAudioAnchor()
                    val savedTag = saved?.get("appTag") ?: "Cuộc gọi"
                    OTTCallAccessibilityService.lastDetectedApp =
                        "$savedTag (Đã lưu vào /Download/S22U_Call_Recordings)"
                    updateNotification(
                        contentText = "Đã lưu $savedTag • Trực chiến 24/7 đang bật",
                        recordingNow = false
                    )
                }
            }
        }

        return START_STICKY
    }

    /**
     * Gắn điểm neo cửa sổ 1x1 trong suốt CHỈ TRONG LÚC ĐANG CÓ CUỘC GỌI để Android 16 cấp quyền
     * thu âm Microphone song song (`hasOverlayUi = true`) cùng với Zalo/Viber/Telegram/WhatsApp.
     * Khi không có cuộc gọi (hoặc khi mở App Ngân hàng), điểm neo này KHÔNG tồn tại.
     */
    private fun attachCallAudioAnchorIfPermitted() {
        if (audioAnchorView != null || !hasOverlayPermission(this)) return
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
            val view = View(this)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val params = WindowManager.LayoutParams(
                1,
                1,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
            }
            wm.addView(view, params)
            audioAnchorView = view
        } catch (_: Exception) {
        }
    }

    private fun detachCallAudioAnchor() {
        val view = audioAnchorView ?: return
        audioAnchorView = null
        try {
            val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            wm?.removeView(view)
        } catch (_: Exception) {
        }
    }

    private fun resolveActiveCallTag(audioMode: Int): String {
        // Ưu tiên 1: Lấy tên ứng dụng + tên người gọi từ OTTCallNotificationListener
        val notifTag = OTTCallNotificationListener.currentActiveOttTag
        if (!notifTag.isNullOrBlank()) {
            return notifTag
        }
        // Ưu tiên 2: Lấy tên ứng dụng đang mở trên màn hình từ UsageStatsManager
        if (hasUsageStatsPermission(this)) {
            val fg = queryCurrentForegroundOttPackage()
            if (fg != null) return fg
        }
        return if (audioMode == AudioManager.MODE_IN_CALL) "SIM_Call" else "Zalo_OTT"
    }

    private fun queryCurrentForegroundOttPackage(): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 180_000L, now)
            val event = UsageEvents.Event()
            var latestOttLabel: String? = null
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    val pkg = event.packageName ?: ""
                    if (SUPPORTED_OTT_PACKAGES.containsKey(pkg) && event.timeStamp >= latestTime) {
                        latestTime = event.timeStamp
                        latestOttLabel = SUPPORTED_OTT_PACKAGES[pkg]
                    }
                }
            }
            latestOttLabel
        } catch (_: Exception) {
            null
        }
    }

    private fun checkAudioState() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = am.mode
        val isCall = (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL)

        if (isCall && !wasInActiveCall) {
            wasInActiveCall = true
            if (autoRecordOttEnabled && !dspEngine.isRecording) {
                attachCallAudioAnchorIfPermitted()
                val tag = resolveActiveCallTag(mode)
                OTTCallAccessibilityService.lastDetectedApp = "$tag (Đang ghi âm tự động)"
                dspEngine.startRecording(
                    appTag = tag,
                    remoteGainBoost = remoteGainBoost,
                    autoBoostEarpieceVolume = autoBoostVolume
                )
                updateNotification("ĐANG GHI ÂM $tag (48kHz Smooth DSP)...", true)
            }
        } else if (!isCall && wasInActiveCall) {
            wasInActiveCall = false
            if (dspEngine.isRecording && dspEngine.currentAppTag != "Manual") {
                val saved = dspEngine.stopRecording()
                detachCallAudioAnchor()
                val savedTag = saved?.get("appTag") ?: "Cuộc gọi"
                OTTCallAccessibilityService.lastDetectedApp =
                    "$savedTag (Đã lưu vào /Download/S22U_Call_Recordings)"
                updateNotification(
                    "Đã lưu $savedTag • Trực chiến 24/7 đang bật",
                    false
                )
            } else {
                detachCallAudioAnchor()
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val guardChannel = NotificationChannel(
                CHANNEL_ID,
                "S22U 24/7 Call Recorder Guard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Trực chiến tự động ghi âm cuộc gọi 24/7 tương thích 100% App Ngân hàng & VNeID"
                setShowBadge(false)
            }
            nm?.createNotificationChannel(guardChannel)
        }
    }

    private fun buildNotification(
        contentText: String,
        recordingNow: Boolean
    ): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingLaunch = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("S22U Call Recorder (An toàn Ngân hàng 24/7)")
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
        recordingNow: Boolean
    ) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        nm?.notify(NOTIF_ID, buildNotification(contentText, recordingNow))
    }

    override fun onDestroy() {
        handler.removeCallbacks(guardPoller)
        detachCallAudioAnchor()
        if (::dspEngine.isInitialized && dspEngine.isRecording) {
            dspEngine.stopRecording()
        }
        isGuardRunning = false
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
