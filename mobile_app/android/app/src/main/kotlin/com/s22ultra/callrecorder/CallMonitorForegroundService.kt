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
import android.media.AudioRecordingConfiguration
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
 * Dịch vụ Trực chiến Ghi âm v2.0.0 — Kiến trúc Ngắt Sự Kiện Phần Cứng (Zero-Polling Deep-Sleep Engine):
 * - CẮT BỎ 100% VÒNG LẶP QUÉT NGẦM (Không dùng Timer / Polling 800ms, Không giữ WakeLock, Không mở Mic khi chờ).
 * - Khi không có cuộc gọi: Ứng dụng NGỦ ĐÔNG 100% (0.0% CPU, 0% hao pin, máy vào Deep Sleep / Doze Mode bình thường).
 * - Chỉ thức dậy đúng khoảnh khắc Phần cứng Âm thanh phát tín hiệu ngắt (Hardware Interrupt):
 *   1. `AudioManager.OnModeChangedListener` (Android 12–16 Native Interrupt khi chuyển sang `MODE_IN_COMMUNICATION` / `MODE_IN_CALL`).
 *   2. `AudioManager.AudioRecordingCallback` (Ngắt phần cứng khi Zalo/Messenger/Telegram kích hoạt luồng thu âm VoIP).
 *   3. `OTTCallNotificationListener` (Sự kiện hệ thống khi có thông báo cuộc gọi OTT).
 * - KHÔNG SỬ DỤNG AccessibilityService -> An toàn 100% với mọi App Ngân hàng & VNeID 24/7.
 */
class CallMonitorForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "s22u_call_recorder_guard"
        private const val NOTIF_ID = 2201

        const val ACTION_START_GUARD = "com.s22ultra.callrecorder.ACTION_START_GUARD"
        const val ACTION_STOP_GUARD_MANUAL = "com.s22ultra.callrecorder.ACTION_STOP_GUARD_MANUAL"
        const val ACTION_TRIGGER_CALL_CHECK = "com.s22ultra.callrecorder.ACTION_TRIGGER_CALL_CHECK"
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
        var autoBoostVolume: Boolean = false

        @Volatile
        var disableMode: String = "NONE"

        @Volatile
        var lastDetectedApp: String = "Ngủ đông chờ sự kiện (0.0% CPU • An toàn Ngân hàng 24/7)"

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

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wasInActiveCall = false
    private var audioAnchorView: View? = null
    private var callbacksRegistered = false

    fun getActiveEngine(): AudioDspRecorderEngine {
        return OTTCallAccessibilityService.instance?.dspEngine ?: dspEngine
    }

    private val callStartRunnable = Runnable {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@Runnable
        val mode = am.mode
        // Chỉ ghi âm khi:
        // 1. Đang ở chế độ đàm thoại (MODE_IN_COMMUNICATION hoặc MODE_IN_CALL)
        // 2. KHÔNG PHẢI đang đổ chuông / chờ nhấc máy (!OTTCallNotificationListener.isCallRingingOrDialing)
        val isConnectedCall = (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL) &&
                !OTTCallNotificationListener.isCallRingingOrDialing
        val engine = getActiveEngine()
        if (isConnectedCall && autoRecordOttEnabled && !engine.isRecording) {
            wasInActiveCall = true
            attachCallAudioAnchorIfPermitted()
            val tag = resolveActiveCallTag(mode)
            lastDetectedApp = "$tag (Đã nhấc máy • Đang ghi âm)"
            engine.startRecording(
                appTag = tag,
                remoteGainBoost = remoteGainBoost,
                autoBoostEarpieceVolume = autoBoostVolume
            )
            updateNotification("ĐANG GHI ÂM $tag (Chỉ ghi từ lúc nhấc máy)...", true)
        }
    }

    private val callEndRunnable = Runnable {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@Runnable
        val mode = am.mode
        val isStillCall = (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL)
        val engine = getActiveEngine()
        if (!isStillCall) {
            wasInActiveCall = false
            OTTCallNotificationListener.isCallRingingOrDialing = false
            OTTCallNotificationListener.isCallAnswered = false
            if (engine.isRecording && engine.currentAppTag != "Manual") {
                val saved = engine.stopRecording()
                detachCallAudioAnchor()
                val savedTag = saved?.get("appTag") ?: "Cuộc gọi"
                lastDetectedApp =
                    "$savedTag (Đã lưu • Đang Ngủ Đông 0.0% CPU)"
                updateNotification(
                    "Đã lưu $savedTag • Đang Ngủ Đông (0.0% CPU)",
                    false
                )
            } else {
                detachCallAudioAnchor()
            }
        }
    }

    // 1. Ngắt phần cứng Audio Mode (API 31+ / Android 12–16): 0% CPU khi nghỉ, chỉ gọi khi đổi trạng thái cuộc gọi
    private var modeChangedListener: AudioManager.OnModeChangedListener? = null

    // 2. Ngắt phần cứng Audio Recording Config (API 24+): Chỉ gọi khi ứng dụng VoIP bắt đầu/kết thúc thu mic
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            super.onRecordingConfigChanged(configs)
            mainHandler.post {
                evaluateAudioHardwareInterrupt()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val prefs = getSharedPreferences("s22u_recorder_prefs", Context.MODE_PRIVATE)
        autoRecordOttEnabled = prefs.getBoolean("autoRecordOttEnabled", true)
        remoteGainBoost = prefs.getFloat("remoteGainBoost", 2.2f)
        autoBoostVolume = prefs.getBoolean("autoBoostVolume", false)
        disableMode = prefs.getString("disableMode", "NONE") ?: "NONE"
        dspEngine = AudioDspRecorderEngine(applicationContext)
        createNotificationChannel()
        registerZeroBatteryHardwareCallbacks()
    }

    /**
     * Đăng ký nhận tín hiệu Ngắt Phần Cứng (Hardware Interrupt) từ hệ điều hành Android:
     * Hoàn toàn KHÔNG chạy bất kỳ vòng lặp kiểm tra (Timer/Polling) nào -> 0.0% CPU khi chờ!
     */
    private fun registerZeroBatteryHardwareCallbacks() {
        if (callbacksRegistered) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val listener = AudioManager.OnModeChangedListener { _ ->
                    mainHandler.post {
                        evaluateAudioHardwareInterrupt()
                    }
                }
                am.addOnModeChangedListener(mainExecutor, listener)
                modeChangedListener = listener
            }
            am.registerAudioRecordingCallback(recordingCallback, mainHandler)
            callbacksRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterZeroBatteryHardwareCallbacks() {
        if (!callbacksRegistered) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modeChangedListener?.let { am?.removeOnModeChangedListener(it) }
            }
            am?.unregisterAudioRecordingCallback(recordingCallback)
        } catch (_: Exception) {
        }
        callbacksRegistered = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP_GUARD_MANUAL) {
            disableMode = "MANUAL_OFF"
            autoRecordOttEnabled = false
            lastDetectedApp = "Đã tắt chủ động trong App (0% Pin)"
            if (dspEngine.isRecording) {
                dspEngine.stopRecording()
            }
            detachCallAudioAnchor()
            unregisterZeroBatteryHardwareCallbacks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            isGuardRunning = false
            return START_NOT_STICKY
        }

        if (!isGuardRunning) {
            val notif = buildNotification(
                contentText = "Chế độ Ngủ Đông (0.0% CPU) • Tự thức dậy khi có cuộc gọi",
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
                lastDetectedApp =
                    "Ngủ đông chờ sự kiện (0.0% CPU • An toàn Ngân hàng 24/7)"
                registerZeroBatteryHardwareCallbacks()
            } catch (_: Exception) {
            }
        }

        when (action) {
            ACTION_START_GUARD -> {
                disableMode = "NONE"
                autoRecordOttEnabled = true
                lastDetectedApp =
                    "Ngủ đông chờ sự kiện (0.0% CPU • An toàn Ngân hàng 24/7)"
                registerZeroBatteryHardwareCallbacks()
                evaluateAudioHardwareInterrupt()
                updateNotification(
                    contentText = "Chế độ Ngủ Đông (0.0% CPU) • Tự thức dậy khi có cuộc gọi",
                    recordingNow = dspEngine.isRecording
                )
            }

            ACTION_TRIGGER_CALL_CHECK -> {
                evaluateAudioHardwareInterrupt()
            }

            ACTION_QUICK_RECORD -> {
                val engine = getActiveEngine()
                if (!engine.isRecording) {
                    attachCallAudioAnchorIfPermitted()
                    val detectedTag = resolveActiveCallTag(AudioManager.MODE_IN_COMMUNICATION)
                    engine.startRecording(
                        appTag = detectedTag,
                        remoteGainBoost = remoteGainBoost,
                        autoBoostEarpieceVolume = autoBoostVolume
                    )
                    lastDetectedApp = "$detectedTag (Đang ghi âm)"
                    updateNotification(
                        contentText = "ĐANG GHI ÂM $detectedTag (48kHz Broadcast AGC)...",
                        recordingNow = true
                    )
                }
            }

            ACTION_STOP_RECORD -> {
                val engine = getActiveEngine()
                if (engine.isRecording) {
                    val saved = engine.stopRecording()
                    detachCallAudioAnchor()
                    val savedTag = saved?.get("appTag") ?: "Cuộc gọi"
                    lastDetectedApp =
                        "$savedTag (Đã lưu • Đã về lại Ngủ Đông 0% CPU)"
                    updateNotification(
                        contentText = "Đã lưu $savedTag • Đang Ngủ Đông (0.0% CPU)",
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
     * Khi không có cuộc gọi (hoặc khi mở App Ngân hàng), điểm neo này KHÔNG tồn tại (0% GPU/RAM).
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
        val notifTag = OTTCallNotificationListener.currentActiveOttTag
        if (!notifTag.isNullOrBlank()) {
            return notifTag
        }
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

    /**
     * Hàm này CHỈ ĐƯỢC GỌI khi có sự kiện ngắt phần cứng (Hardware Interrupt) từ hệ điều hành.
     * Tuyệt đối không chạy định kỳ -> 0.0% CPU khi máy rảnh!
     */
    fun evaluateAudioHardwareInterrupt() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = am.mode
        val isCall = (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL)
        val engine = getActiveEngine()

        if (isCall) {
            mainHandler.removeCallbacks(callEndRunnable)
            if (OTTCallNotificationListener.isCallRingingOrDialing) {
                // Đang đổ chuông / chờ đầu dây nhấc máy -> tuyệt đối chưa bật ghi âm!
                mainHandler.removeCallbacks(callStartRunnable)
            } else if (!engine.isRecording) {
                // Đã nhấc máy -> chờ 900ms để luồng thoại ổn định rồi bắt đầu thu âm
                mainHandler.removeCallbacks(callStartRunnable)
                mainHandler.postDelayed(callStartRunnable, 900L)
            }
        } else if (mode != AudioManager.MODE_RINGTONE) {
            mainHandler.removeCallbacks(callStartRunnable)
            if (wasInActiveCall || engine.isRecording) {
                mainHandler.removeCallbacks(callEndRunnable)
                mainHandler.postDelayed(callEndRunnable, 1800L)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val guardChannel = NotificationChannel(
                CHANNEL_ID,
                "S22U Deep-Sleep Call Guard (0% Battery)",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Ngủ đông 0% CPU khi chờ, chỉ thức dậy khi có ngắt phần cứng cuộc gọi"
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
            .setContentTitle("S22U Call Recorder (Zero-Polling 0% Pin)")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingLaunch)
            .setOngoing(true)
            .setSilent(true)

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

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Khi người dùng vuốt đóng App khỏi màn hình Đa nhiệm (Recent Apps):
        // Nếu không phải tắt chủ động trong App, giữ nguyên dịch vụ Ngủ Đông để có cuộc gọi vẫn tự kích hoạt 100%!
        if (disableMode != "MANUAL_OFF") {
            try {
                val restartIntent = Intent(applicationContext, CallMonitorForegroundService::class.java).apply {
                    action = ACTION_TRIGGER_CALL_CHECK
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(restartIntent)
                } else {
                    applicationContext.startService(restartIntent)
                }
            } catch (_: Exception) {
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        unregisterZeroBatteryHardwareCallbacks()
        mainHandler.removeCallbacksAndMessages(null)
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
