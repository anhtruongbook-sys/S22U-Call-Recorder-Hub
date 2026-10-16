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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class CallMonitorForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "s22u_call_recorder_guard"
        private const val NOTIF_ID = 2201

        const val ACTION_START_GUARD = "com.s22ultra.callrecorder.ACTION_START_GUARD"
        const val ACTION_BANKING_STEALTH_TRIGGERED = "com.s22ultra.callrecorder.ACTION_BANKING_STEALTH_TRIGGERED"
        const val ACTION_DISABLE_ACCESSIBILITY_NOW = "com.s22ultra.callrecorder.ACTION_DISABLE_ACCESSIBILITY_NOW"
        const val ACTION_MANUAL_DISABLE_ACCESSIBILITY = "com.s22ultra.callrecorder.ACTION_MANUAL_DISABLE_ACCESSIBILITY"
        const val ACTION_RESTORE_ACCESSIBILITY_NOW = "com.s22ultra.callrecorder.ACTION_RESTORE_ACCESSIBILITY_NOW"
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

        /**
         * Trạng thái tắt Trợ năng:
         * - "NONE": Đang bật bình thường
         * - "AUTO_STEALTH": Tạm ẩn tự động khi vào Ngân hàng / VNeID / App cấm Trợ năng -> SẼ TỰ ĐỘNG BẬT LẠI khi thoát App
         * - "MANUAL_OFF": Người dùng chủ động tắt trong App -> TUYỆT ĐỐI KHÔNG tự bật lại
         */
        @Volatile
        var disableMode: String = "NONE"

        @Volatile
        var activeStealthAppName: String = ""

        fun hasWriteSecureSettingsPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED
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

    private var stealthTriggeredAtMs: Long = 0L
    private var stealthExitedAtMs: Long = 0L
    private var hasEnteredStealthTargetApp: Boolean = false
    private var lastKnownForegroundPkg: String = ""
    private var hasPromptedManualRestoreThisSession: Boolean = false

    private val guardPoller = object : Runnable {
        override fun run() {
            checkAudioState()
            checkAutoRestoreCondition()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        OTTCallAccessibilityService.loadLearnedApps(applicationContext)
        dspEngine = AudioDspRecorderEngine(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val isAccNow = OTTCallAccessibilityService.instance != null

        if (!isGuardRunning) {
            val notif = buildNotification(
                contentText = if (isAccNow) {
                    "Trợ năng ĐANG BẬT • Tự ẩn khi vào Ngân hàng/VNeID & Tự bật lại khi thoát"
                } else {
                    "Trợ năng ĐANG TẮT • Vẫn tự động ghi âm cuộc gọi qua Foreground Guard"
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
                handler.removeCallbacks(guardPoller)
                handler.post(guardPoller)
            } catch (_: Exception) {
            }
        }

        when (action) {
            ACTION_START_GUARD -> {
                val accOn = OTTCallAccessibilityService.instance != null
                if (accOn) {
                    disableMode = "NONE"
                    activeStealthAppName = ""
                    hasPromptedManualRestoreThisSession = false
                }
                updateNotification(
                    contentText = if (accOn) {
                        "Trợ năng ĐANG BẬT • Tự ẩn khi vào Ngân hàng/VNeID & Tự bật lại khi thoát"
                    } else if (disableMode == "MANUAL_OFF") {
                        "Đã TẮT CHỦ ĐỘNG Trợ năng (Không tự bật lại)"
                    } else {
                        "Trợ năng ĐANG TẠM ẨN • Sẽ tự bật lại sau khi thoát ứng dụng"
                    },
                    recordingNow = false,
                    accessibilityCurrentlyOn = accOn
                )
            }

            // Nút [Tắt Trợ Năng (Dùng NH)] trên thanh thông báo -> Tính là AUTO_STEALTH (Tự bật lại sau khi dùng xong NH)
            ACTION_DISABLE_ACCESSIBILITY_NOW -> {
                disableMode = "AUTO_STEALTH"
                activeStealthAppName = "Ngân hàng / VNeID"
                stealthTriggeredAtMs = System.currentTimeMillis()
                stealthExitedAtMs = 0L
                hasEnteredStealthTargetApp = false
                hasPromptedManualRestoreThisSession = false
                OTTCallAccessibilityService.instance?.triggerAutoStealthDisable("Nút Tạm ẩn dùng NH")
                updateNotification(
                    contentText = "ĐÃ TẠM ẨN TRỢ NĂNG! Tự động bật lại sau khi bạn thoát Ngân hàng/VNeID.",
                    recordingNow = false,
                    accessibilityCurrentlyOn = false
                )
            }

            // Khi người dùng CHỦ ĐỘNG bấm tắt trong App -> Tính là MANUAL_OFF (TUYỆT ĐỐI KHÔNG tự bật lại)
            ACTION_MANUAL_DISABLE_ACCESSIBILITY -> {
                disableMode = "MANUAL_OFF"
                activeStealthAppName = ""
                stealthTriggeredAtMs = 0L
                stealthExitedAtMs = 0L
                OTTCallAccessibilityService.lastDetectedApp = "Đã tắt chủ động (Không tự bật lại)"
                updateNotification(
                    contentText = "ĐÃ TẮT CHỦ ĐỘNG TRỢ NĂNG • Giữ nguyên trạng thái tắt theo ý bạn.",
                    recordingNow = false,
                    accessibilityCurrentlyOn = false
                )
            }

            // Khi hệ thống tự phát hiện chạm icon Ngân hàng / VNeID / Hộp thoại yêu cầu tắt Trợ năng
            ACTION_BANKING_STEALTH_TRIGGERED -> {
                val pkgLabel = intent.getStringExtra("bankingPkg") ?: "Ngân hàng / VNeID"
                disableMode = "AUTO_STEALTH"
                activeStealthAppName = pkgLabel
                stealthTriggeredAtMs = System.currentTimeMillis()
                stealthExitedAtMs = 0L
                hasEnteredStealthTargetApp = false
                hasPromptedManualRestoreThisSession = false
                OTTCallAccessibilityService.lastDetectedApp = "Tạm ẩn cho $pkgLabel (Tự bật lại khi thoát)"
                updateNotification(
                    contentText = "ĐÃ TẠM ẨN ($pkgLabel) • Sẽ tự động bật lại sau khi bạn thoát ứng dụng.",
                    recordingNow = false,
                    accessibilityCurrentlyOn = false
                )
            }

            ACTION_RESTORE_ACCESSIBILITY_NOW -> {
                val restoredSilently = trySilentEnableAccessibility()
                if (!restoredSilently) {
                    openAccessibilitySettingsActivity()
                }
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

    /**
     * Kiểm tra điều kiện tự động BẬT LẠI Trợ năng sau khi người dùng thoát App Ngân hàng / VNeID.
     * - Nếu `disableMode == "MANUAL_OFF"` (Người dùng chủ động tắt trong App) -> BỎ QUA 100%, không bao giờ tự bật lại.
     * - Chỉ chạy khi `disableMode == "AUTO_STEALTH"` và `OTTCallAccessibilityService.autoRestoreEnabled == true`.
     */
    private fun checkAutoRestoreCondition() {
        if (OTTCallAccessibilityService.instance != null) {
            if (disableMode == "AUTO_STEALTH") {
                disableMode = "NONE"
            }
            return
        }

        // Tôn trọng tuyệt đối trường hợp người dùng tắt chủ động trong App
        if (disableMode != "AUTO_STEALTH" || !OTTCallAccessibilityService.autoRestoreEnabled) {
            return
        }

        val now = System.currentTimeMillis()
        val elapsedSinceStealth = now - stealthTriggeredAtMs
        // Đợi tối thiểu 4 giây từ lúc bắt đầu ẩn để App Ngân hàng mở xong ổn định
        if (elapsedSinceStealth < 4000L) return

        // TÍN HIỆU 1: Người dùng đã dùng xong và tắt màn hình khóa máy
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) {
            performAutoRestore("Màn hình khóa sau khi dùng $activeStealthAppName")
            return
        }

        // TÍN HIỆU 2: Theo dõi gói ứng dụng đang hiển thị bằng UsageStatsManager
        if (hasUsageStatsPermission(this)) {
            val fgPkg = queryCurrentForegroundPackage()
            if (fgPkg.isNotEmpty()) {
                lastKnownForegroundPkg = fgPkg
            }
            val currentFg = lastKnownForegroundPkg
            if (currentFg.isNotEmpty()) {
                val isCurrentlyInStealthApp = OTTCallAccessibilityService.isBankingOrLearnedPackage(currentFg)
                if (isCurrentlyInStealthApp) {
                    hasEnteredStealthTargetApp = true
                    stealthExitedAtMs = 0L
                } else {
                    val isLauncher = currentFg.contains("launcher") || currentFg.contains("home")
                    // Nếu đã vào App Ngân hàng rồi thoát ra màn hình chính / App khác,
                    // hoặc nếu quá 15 giây ở ngoài màn hình chính
                    if (hasEnteredStealthTargetApp || (isLauncher && elapsedSinceStealth > 15000L)) {
                        if (stealthExitedAtMs == 0L) {
                            stealthExitedAtMs = now
                        } else {
                            // Độ trễ an toàn 7 giây (để người dùng kịp chuyển sang tin nhắn xem OTP nếu cần)
                            val requiredExitDelay = if (isLauncher) 6000L else 8000L
                            if (now - stealthExitedAtMs >= requiredExitDelay) {
                                performAutoRestore("Đã thoát $activeStealthAppName")
                            }
                        }
                    }
                }
            }
        }
    }

    private fun queryCurrentForegroundPackage(): String {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return ""
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 10000L, now)
            val event = UsageEvents.Event()
            var latestPkg = ""
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                    event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                ) {
                    if (event.timeStamp >= latestTime && !event.packageName.isNullOrBlank()) {
                        latestTime = event.timeStamp
                        latestPkg = event.packageName
                    }
                }
            }
            latestPkg
        } catch (_: Exception) {
            ""
        }
    }

    private fun performAutoRestore(reason: String) {
        if (disableMode != "AUTO_STEALTH" || !OTTCallAccessibilityService.autoRestoreEnabled) return

        val restoredSilently = trySilentEnableAccessibility()
        if (restoredSilently) {
            disableMode = "NONE"
            OTTCallAccessibilityService.lastDetectedApp = "Đã tự bật lại Trợ năng ($reason)"
            updateNotification(
                contentText = "Đã tự động BẬT LẠI Trợ năng ($reason)",
                recordingNow = dspEngine.isRecording,
                accessibilityCurrentlyOn = true
            )
        } else if (!hasPromptedManualRestoreThisSession) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isInteractive == true) {
                hasPromptedManualRestoreThisSession = true
                OTTCallAccessibilityService.lastDetectedApp = "Đã thoát $activeStealthAppName • Chờ bật lại 1-chạm"
                updateNotification(
                    contentText = "Đã dùng xong $activeStealthAppName • Bấm [Bật Lại Trợ Năng]",
                    recordingNow = dspEngine.isRecording,
                    accessibilityCurrentlyOn = false
                )
                openAccessibilitySettingsActivity()
            }
        }
    }

    /**
     * Bật lại Trợ năng ngầm 100% nếu đã được cấp quyền `WRITE_SECURE_SETTINGS` (qua ADB hoặc Shizuku).
     */
    fun trySilentEnableAccessibility(): Boolean {
        return try {
            val comp = ComponentName(this, OTTCallAccessibilityService::class.java).flattenToString()
            val current = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            if (!current.contains(comp)) {
                val updated = if (current.isBlank()) comp else "$current:$comp"
                val ok = Settings.Secure.putString(
                    contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    updated
                )
                Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                ok
            } else {
                Settings.Secure.putInt(contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun openAccessibilitySettingsActivity() {
        try {
            val accIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(accIntent)
        } catch (_: Exception) {
        }
    }

    private fun checkAudioState() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = am.mode
        val isCall = (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL)

        if (isCall && !wasInActiveCall) {
            wasInActiveCall = true
            // Nếu đang ở trạng thái AUTO_STEALTH (không phải MANUAL_OFF), thử tự khôi phục Trợ năng
            if (OTTCallAccessibilityService.instance == null &&
                disableMode == "AUTO_STEALTH" &&
                OTTCallAccessibilityService.autoRestoreEnabled
            ) {
                trySilentEnableAccessibility()
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
                description = "Tự động ẩn Trợ năng khi mở App Ngân hàng/VNeID và tự động bật lại sau khi thoát"
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
                "Tạm Ẩn (Dùng NH)",
                pendingDisable
            )
        } else {
            val restoreIntent = Intent(this, CallMonitorForegroundService::class.java).apply {
                action = ACTION_RESTORE_ACCESSIBILITY_NOW
            }
            val pendingRestore = PendingIntent.getService(
                this,
                3,
                restoreIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                android.R.drawable.ic_lock_power_off,
                "Bật Lại Trợ Năng",
                pendingRestore
            )
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
        handler.removeCallbacks(guardPoller)
        if (::dspEngine.isInitialized && dspEngine.isRecording) {
            dspEngine.stopRecording()
        }
        isGuardRunning = false
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
