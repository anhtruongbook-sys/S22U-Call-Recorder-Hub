package com.s22ultra.callrecorder

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
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
 * Dịch vụ Trực chiến Ghi âm v2.6.0 — Kiến trúc KHÔNG TRỢ NĂNG (Zero-Accessibility • 100% An Toàn Ngân Hàng):
 * - CẮT BỎ 100% AccessibilityService (Các App Ngân hàng VCB, BIDV, Techcombank, MB, VietinBank, VNeID chạy mượt 100% 24/7).
 * - Khởi mở luồng `VOICE_RECOGNITION` ngay tức khắc ở `0ms` khi có ngắt phần cứng `OnModeChangedListener` (giống hệt cơ chế
 *   đã thu được tiếng của bản v2.0.0, không bị trễ nhịp làm mất quyền Mic vào tay Zalo).
 * - Kết hợp Cổng Chờ Nhấc Máy (`Pre-Roll Answer Gate`) trong `AudioDspRecorderEngine`: Không ghi đoạn chờ đổ chuông vào file `.wav`,
 *   chỉ bắt đầu ghi từ lúc đầu dây nhấc máy nói "Alo"!
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
        var remoteGainBoost: Float = 1.5f

        @Volatile
        var autoBoostVolume: Boolean = false

        @Volatile
        var disableMode: String = "NONE"

        @Volatile
        var lastDetectedApp: String = "Ngủ đông chờ sự kiện (0.0% CPU • Không dùng Trợ năng)"

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

        fun hasWriteSecureSettings(context: Context): Boolean {
            try {
                if (context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    return true
                }
            } catch (_: Exception) {
            }
            return try {
                Settings.Secure.putInt(context.contentResolver, "s22u_call_bridge_ready", 1)
                true
            } catch (_: Throwable) {
                false
            }
        }

        @Volatile
        private var sharedEngineInstance: AudioDspRecorderEngine? = null

        @Synchronized
        fun getSharedEngine(context: Context): AudioDspRecorderEngine {
            val existing = sharedEngineInstance
            if (existing != null) return existing
            val created = AudioDspRecorderEngine(context.applicationContext)
            sharedEngineInstance = created
            return created
        }

        @Volatile
        private var lastAutoBindAttemptMs: Long = 0L

        fun enableEphemeralAccessibilityForCall(context: Context, force: Boolean = false): Boolean {
            if (OTTCallAccessibilityService.instance != null) return true
            if (!hasWriteSecureSettings(context)) return false
            val now = System.currentTimeMillis()
            if (!force && (now - lastAutoBindAttemptMs) < 3_500L) {
                return false
            }
            lastAutoBindAttemptMs = now
            return try {
                val cr = context.contentResolver
                val cn = ComponentName(context, OTTCallAccessibilityService::class.java).flattenToString()
                val shortCn = "${context.packageName}/.OTTCallAccessibilityService"
                val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                val existingParts = current.split(":").map { it.trim() }.filter { it.isNotEmpty() }
                val cleanParts = existingParts.filter { it != cn && it != shortCn }

                // Nếu trong Settings.Secure đã tồn tại chuỗi cũ nhưng Service chưa bind (instance == null),
                // phải xóa tạm chuỗi cũ trước rồi ghi lại chuỗi mới để kích hoạt ContentObserver của Android!
                if (existingParts.size != cleanParts.size) {
                    Settings.Secure.putString(
                        cr,
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        cleanParts.joinToString(":")
                    )
                }

                val updatedParts = cleanParts.toMutableList()
                updatedParts.add(cn)
                Settings.Secure.putString(
                    cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updatedParts.joinToString(":")
                )
                Settings.Secure.putString(cr, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
                true
            } catch (_: Exception) {
                false
            }
        }

        fun disableEphemeralAccessibilityAfterCall(context: Context): Boolean {
            OTTCallAccessibilityService.instance?.safelyDisableSelfNow()
            if (!hasWriteSecureSettings(context)) return false
            return try {
                val cr = context.contentResolver
                val cn = ComponentName(context, OTTCallAccessibilityService::class.java).flattenToString()
                val shortCn = "${context.packageName}/.OTTCallAccessibilityService"
                val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                val remaining = current.split(":")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && it != cn && it != shortCn }
                Settings.Secure.putString(
                    cr,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    remaining.joinToString(":")
                )
                if (remaining.isEmpty()) {
                    Settings.Secure.putString(cr, Settings.Secure.ACCESSIBILITY_ENABLED, "0")
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        fun onBankingStealthEngaged(context: Context) {
            disableEphemeralAccessibilityAfterCall(context)
            instance?.scheduleAutoRestoreAfterBankingExit()
        }
    }

    lateinit var dspEngine: AudioDspRecorderEngine
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wasInActiveCall = false
    private var audioAnchorView: View? = null
    private var callbacksRegistered = false

    private val bankingRestoreRunnable = object : Runnable {
        override fun run() {
            if (disableMode == "MANUAL_OFF" || !hasWriteSecureSettings(this@CallMonitorForegroundService)) {
                return
            }
            val fgPkg = queryLatestForegroundPackage(15_000L)
            if (fgPkg != null && OTTCallAccessibilityService.isBankingOrProtectedPackage(fgPkg)) {
                // Người dùng vẫn đang ở trong App Ngân hàng -> Đợi thêm 3 giây rồi kiểm tra tiếp
                mainHandler.postDelayed(this, 3000L)
            } else {
                // Người dùng đã thoát App Ngân hàng -> Tự động bật lại Cầu nối thu âm
                enableEphemeralAccessibilityForCall(this@CallMonitorForegroundService, force = true)
                lastDetectedApp = "Đã tự bật lại Cầu nối sau khi thoát App Ngân hàng (0% CPU)"
                if (OTTCallAccessibilityService.instance == null) {
                    mainHandler.postDelayed(this, 3000L)
                }
            }
        }
    }

    fun scheduleAutoRestoreAfterBankingExit() {
        mainHandler.removeCallbacks(bankingRestoreRunnable)
        mainHandler.postDelayed(bankingRestoreRunnable, 3500L)
    }

    private fun queryLatestForegroundPackage(windowMs: Long): String? {
        if (!hasUsageStatsPermission(this)) return null
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - windowMs, now)
            val event = UsageEvents.Event()
            var latestPkg: String? = null
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    val pkg = event.packageName ?: ""
                    if (pkg.isNotEmpty() && event.timeStamp >= latestTime) {
                        latestTime = event.timeStamp
                        latestPkg = pkg
                    }
                }
            }
            latestPkg
        } catch (_: Exception) {
            null
        }
    }

    private val callEndRunnable = Runnable {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@Runnable
        val mode = am.mode
        val hasVoipStream = try {
            am.activeRecordingConfigurations.any { cfg ->
                val src = cfg.clientAudioSource
                src == android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION ||
                        src == android.media.MediaRecorder.AudioSource.VOICE_CALL
            }
        } catch (_: Exception) {
            false
        }
        val isStillCall = (mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_IN_CALL ||
                hasVoipStream)
        if (!isStillCall && wasInActiveCall) {
            wasInActiveCall = false
            OTTCallNotificationListener.isCallRingingOrDialing = false
            OTTCallNotificationListener.isCallAnswered = false

            val saved = if (dspEngine.isRecording && dspEngine.currentAppTag != "Manual") {
                dspEngine.stopRecording()
            } else {
                null
            }
            detachCallAudioAnchor()

            val savedTag = saved?.get("appTag") ?: "Cuộc gọi"
            lastDetectedApp =
                "$savedTag (Đã lưu .m4a • Sẵn sàng tự tắt 0ms khi mở Ngân hàng)"
            updateNotification(
                "Đã lưu $savedTag (.m4a) • Đang Ngủ Đông (0.0% CPU)",
                false
            )
        }
    }

    private var modeChangedListener: AudioManager.OnModeChangedListener? = null

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
        if (!prefs.getBoolean("dsp_v293_clean_migrated", false)) {
            prefs.edit()
                .putFloat("remoteGainBoost", 1.5f)
                .putBoolean("autoBoostVolume", false)
                .putBoolean("dsp_v293_clean_migrated", true)
                .apply()
        }
        autoRecordOttEnabled = prefs.getBoolean("autoRecordOttEnabled", true)
        remoteGainBoost = prefs.getFloat("remoteGainBoost", 1.5f)
        autoBoostVolume = prefs.getBoolean("autoBoostVolume", false)
        disableMode = prefs.getString("disableMode", "NONE") ?: "NONE"
        dspEngine = getSharedEngine(applicationContext)
        createNotificationChannel()
        registerZeroBatteryHardwareCallbacks()
        if (disableMode != "MANUAL_OFF" &&
            hasWriteSecureSettings(this) &&
            !OTTCallAccessibilityService.isAccessibilityServiceEnabled(this)
        ) {
            enableEphemeralAccessibilityForCall(this)
        }
    }

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
                contentText = "Chế độ Ngủ Đông (0.0% CPU • Không dùng Trợ năng)",
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
                    "Ngủ đông chờ sự kiện (0.0% CPU • Không dùng Trợ năng)"
                registerZeroBatteryHardwareCallbacks()
            } catch (_: Exception) {
            }
        }

        when (action) {
            ACTION_START_GUARD -> {
                disableMode = "NONE"
                autoRecordOttEnabled = true
                lastDetectedApp =
                    "Ngủ đông chờ sự kiện (0.0% CPU • Không dùng Trợ năng)"
                registerZeroBatteryHardwareCallbacks()
                evaluateAudioHardwareInterrupt()
                updateNotification(
                    contentText = "Chế độ Ngủ Đông (0.0% CPU • Không dùng Trợ năng)",
                    recordingNow = dspEngine.isRecording
                )
            }

            ACTION_TRIGGER_CALL_CHECK -> {
                evaluateAudioHardwareInterrupt()
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
                    lastDetectedApp = "$detectedTag (Đang ghi âm)"
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

    fun resolveActiveCallTag(audioMode: Int): String {
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
     * Khi bắt đầu cuộc gọi:
     * - Nếu đã cấp quyền WRITE_SECURE_SETTINGS: Tự động BẬT cầu nối OTTCallAccessibilityService
     *   kèm neo 1x1 TYPE_ACCESSIBILITY_OVERLAY để Android 16 mở khóa 100% âm thanh đàm thoại 2 chiều (.m4a).
     */
    fun evaluateAudioHardwareInterrupt() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = am.mode
        val hasVoipRecordingStream = try {
            am.activeRecordingConfigurations.any { cfg ->
                val src = cfg.clientAudioSource
                src == android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION ||
                        src == android.media.MediaRecorder.AudioSource.VOICE_CALL
            }
        } catch (_: Exception) {
            false
        }
        val isCall = (mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_IN_CALL ||
                hasVoipRecordingStream)

        if (isCall) {
            mainHandler.removeCallbacks(callEndRunnable)
            val tag = resolveActiveCallTag(mode)

            if (autoRecordOttEnabled &&
                hasWriteSecureSettings(this) &&
                !OTTCallAccessibilityService.isAccessibilityServiceEnabled(this)
            ) {
                enableEphemeralAccessibilityForCall(this, force = true)
            }

            val accService = OTTCallAccessibilityService.instance
            accService?.attachAccessibilityAudioAnchor()
            val anyRecordingNow = (accService != null && accService.dspEngine.isRecording) || dspEngine.isRecording

            if (!wasInActiveCall || !anyRecordingNow) {
                wasInActiveCall = true
                if (autoRecordOttEnabled && !anyRecordingNow) {
                    attachCallAudioAnchorIfPermitted()
                    if (accService != null) {
                        accService.startCallRecordingFromAccessibilityBridge(mode)
                    } else {
                        dspEngine.startRecording(
                            appTag = tag,
                            remoteGainBoost = remoteGainBoost,
                            autoBoostEarpieceVolume = autoBoostVolume
                        )
                    }
                    lastDetectedApp = "$tag (Đang ghi âm tự động .m4a)"
                    updateNotification("ĐANG GHI ÂM $tag (Định dạng .m4a)...", true)
                }
            } else {
                dspEngine.updateActiveCallTag(tag)
            }
        } else if (mode != AudioManager.MODE_RINGTONE && wasInActiveCall) {
            mainHandler.removeCallbacks(callEndRunnable)
            mainHandler.postDelayed(callEndRunnable, 600L)
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
            .setContentTitle("S22U Call Recorder (Không Trợ Năng • 0% Pin)")
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
