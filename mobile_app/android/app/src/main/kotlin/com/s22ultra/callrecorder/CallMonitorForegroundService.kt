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
        private const val ALERT_CHANNEL_ID = "s22u_call_recorder_restore_alert"
        private const val NOTIF_ID = 2201
        private const val ALERT_NOTIF_ID = 2202

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

        val SUPPORTED_OTT_PACKAGES = mapOf(
            "com.zing.zalo" to "Zalo",
            "com.viber.voip" to "Viber",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business"
        )

        fun hasWriteSecureSettingsPermission(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED
        }

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
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val isAccNow = OTTCallAccessibilityService.instance != null

        if (!isGuardRunning) {
            val notif = buildNotification(
                contentText = if (isAccNow) {
                    "Trợ năng ĐANG BẬT • Tự ẩn khi vào Ngân hàng/VNeID & Tự bật lại khi thoát"
                } else {
                    "Trực chiến ngầm ĐANG CHẠY • Tự động ghi âm Zalo/OTT & SIM"
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
                    cancelRestoreAlertNotification()
                }
                updateNotification(
                    contentText = if (accOn) {
                        "Trợ năng ĐANG BẬT • Tự ẩn khi vào Ngân hàng/VNeID & Tự bật lại khi thoát"
                    } else if (disableMode == "MANUAL_OFF") {
                        "Đã TẮT CHỦ ĐỘNG Trợ năng (Không tự bật lại)"
                    } else {
                        "Trợ năng ĐANG TẠM ẨN • Sẽ tự bật lại sau khi thoát Ngân hàng/VNeID"
                    },
                    recordingNow = false,
                    accessibilityCurrentlyOn = accOn
                )
            }

            // Nút [Tạm ẩn dùng NH] -> Chỉ bắt đầu tính thoát SAU KHI người dùng thực sự mở App Ngân hàng/VNeID
            ACTION_DISABLE_ACCESSIBILITY_NOW -> {
                disableMode = "AUTO_STEALTH"
                activeStealthAppName = "Ngân hàng / VNeID"
                stealthTriggeredAtMs = System.currentTimeMillis()
                stealthExitedAtMs = 0L
                hasEnteredStealthTargetApp = false
                hasPromptedManualRestoreThisSession = false
                OTTCallAccessibilityService.instance?.triggerAutoStealthDisable("Tạm ẩn dùng NH")
                updateNotification(
                    contentText = "ĐÃ TẠM ẨN TRỢ NĂNG! Mở Ngân hàng/VNeID thoải mái, thoát ra sẽ tự bật lại.",
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
                hasEnteredStealthTargetApp = false
                OTTCallAccessibilityService.lastDetectedApp = "Đã tắt chủ động (Không tự bật lại)"
                updateNotification(
                    contentText = "ĐÃ TẮT CHỦ ĐỘNG TRỢ NĂNG • Giữ nguyên trạng thái tắt theo ý bạn.",
                    recordingNow = false,
                    accessibilityCurrentlyOn = false
                )
            }

            // Khi hệ thống tự phát hiện chạm icon hoặc mở App Ngân hàng / VNeID / Hộp thoại yêu cầu tắt Trợ năng
            ACTION_BANKING_STEALTH_TRIGGERED -> {
                val pkgLabel = intent.getStringExtra("bankingPkg") ?: "Ngân hàng / VNeID"
                val rawPkg = intent.getStringExtra("rawPkg") ?: ""
                disableMode = "AUTO_STEALTH"
                activeStealthAppName = pkgLabel
                stealthTriggeredAtMs = System.currentTimeMillis()
                stealthExitedAtMs = 0L
                // Nếu sự kiện đến từ chính gói Ngân hàng/VNeID thì đánh dấu đã vào App Ngân hàng ngay!
                hasEnteredStealthTargetApp = rawPkg.isNotEmpty() &&
                        OTTCallAccessibilityService.isBankingOrLearnedPackage(rawPkg)
                hasPromptedManualRestoreThisSession = false
                OTTCallAccessibilityService.lastDetectedApp = "Đang dùng $pkgLabel (Tự bật lại khi thoát)"
                updateNotification(
                    contentText = "ĐANG ẨN CHO $pkgLabel • Thoát ra hoặc chuyển App khác sẽ tự bật lại.",
                    recordingNow = false,
                    accessibilityCurrentlyOn = false
                )
            }

            ACTION_RESTORE_ACCESSIBILITY_NOW -> {
                cancelRestoreAlertNotification()
                val restoredSilently = trySilentEnableAccessibility()
                if (!restoredSilently) {
                    openAccessibilitySettingsActivity()
                }
            }

            ACTION_QUICK_RECORD -> {
                val activeEngine = OTTCallAccessibilityService.instance?.dspEngine ?: dspEngine
                if (!activeEngine.isRecording) {
                    val detectedTag = resolveCurrentOttTagFromUsageStats() ?: "OTT_Call"
                    activeEngine.startRecording(
                        appTag = detectedTag,
                        remoteGainBoost = OTTCallAccessibilityService.remoteGainBoost,
                        autoBoostEarpieceVolume = OTTCallAccessibilityService.autoBoostVolume
                    )
                    updateNotification(
                        contentText = "ĐANG GHI ÂM $detectedTag (48kHz Smooth DSP)...",
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
                        contentText = "Đã lưu cuộc gọi vào /Download/S22U_Call_Recordings",
                        recordingNow = false,
                        accessibilityCurrentlyOn = accOn
                    )
                }
            }
        }

        return START_STICKY
    }

    /**
     * Kiểm tra điều kiện tự động BẬT LẠI Trợ năng sau khi người dùng thoát App Ngân hàng / VNeID
     * hoặc chuyển từ App Ngân hàng sang bất kỳ App nào khác.
     * - Nếu `disableMode == "MANUAL_OFF"` -> BỎ QUA 100%, không bao giờ tự bật lại.
     */
    private fun checkAutoRestoreCondition() {
        if (OTTCallAccessibilityService.instance != null) {
            if (disableMode == "AUTO_STEALTH") {
                disableMode = "NONE"
                cancelRestoreAlertNotification()
            }
            return
        }

        // Tôn trọng tuyệt đối trường hợp người dùng tắt chủ động trong App
        if (disableMode != "AUTO_STEALTH" || !OTTCallAccessibilityService.autoRestoreEnabled) {
            return
        }

        val now = System.currentTimeMillis()
        val elapsedSinceStealth = now - stealthTriggeredAtMs
        // Đợi tối thiểu 2.5 giây từ lúc chạm icon để App Ngân hàng kịp mở lên màn hình
        if (elapsedSinceStealth < 2500L) return

        // TÍN HIỆU 1: Người dùng tắt màn hình khóa máy -> Chắc chắn đã dùng xong Ngân hàng
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive && elapsedSinceStealth > 4000L) {
            performAutoRestore("Khóa màn hình sau khi dùng $activeStealthAppName")
            return
        }

        // TÍN HIỆU 2: Theo dõi gói ứng dụng đang hiển thị trên màn hình bằng UsageStatsManager (cửa sổ 3 phút)
        if (hasUsageStatsPermission(this)) {
            val fgPkg = queryCurrentForegroundPackage(lookbackMs = 180_000L)
            if (fgPkg.isNotEmpty()) {
                lastKnownForegroundPkg = fgPkg
            }
            val currentFg = lastKnownForegroundPkg
            if (currentFg.isNotEmpty()) {
                val isCurrentlyInStealthApp = OTTCallAccessibilityService.isBankingOrLearnedPackage(currentFg)
                if (isCurrentlyInStealthApp) {
                    // Đang ở trong App Ngân hàng / VNeID -> Ghi nhận đã vào và reset bộ đếm thoát
                    hasEnteredStealthTargetApp = true
                    stealthExitedAtMs = 0L
                    hasPromptedManualRestoreThisSession = false
                    OTTCallAccessibilityService.lastDetectedApp = "Đang trong $activeStealthAppName"
                } else if (hasEnteredStealthTargetApp) {
                    // Người dùng ĐÃ VÀO App Ngân hàng và vừa THOÁT RA màn hình chính HOẶC CHUYỂN SANG APP KHÁC!
                    // (Bỏ qua trình cài đặt/permission controller của hệ thống)
                    val isSystemPermissionDialog = currentFg.contains("permissioncontroller") ||
                            currentFg == "com.android.settings"
                    if (!isSystemPermissionDialog) {
                        if (stealthExitedAtMs == 0L) {
                            stealthExitedAtMs = now
                        } else if (now - stealthExitedAtMs >= 2500L) {
                            // Sau 2.5 giây kể từ khi rời khỏi App Ngân hàng sang App khác / Màn hình chính -> Khôi phục ngay!
                            performAutoRestore("Đã thoát $activeStealthAppName")
                        }
                    }
                }
            }
        }
    }

    private fun queryCurrentForegroundPackage(lookbackMs: Long = 180_000L): String {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return ""
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - lookbackMs, now)
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

    private fun resolveCurrentOttTagFromUsageStats(): String? {
        if (!hasUsageStatsPermission(this)) return null
        val fg = queryCurrentForegroundPackage(lookbackMs = 120_000L)
        if (fg.isEmpty()) return null
        return SUPPORTED_OTT_PACKAGES[fg]
    }

    private fun performAutoRestore(reason: String) {
        if (disableMode != "AUTO_STEALTH" || !OTTCallAccessibilityService.autoRestoreEnabled) return

        // Cấp độ 1: Nếu đã có quyền WRITE_SECURE_SETTINGS -> Tự bật lại Trợ năng NGẦM 100% trong 0.001s
        val restoredSilently = trySilentEnableAccessibility()
        if (restoredSilently) {
            disableMode = "NONE"
            hasEnteredStealthTargetApp = false
            stealthExitedAtMs = 0L
            cancelRestoreAlertNotification()
            OTTCallAccessibilityService.lastDetectedApp = "Đã tự động BẬT LẠI Trợ năng ($reason)"
            updateNotification(
                contentText = "Đã tự động BẬT LẠI Trợ năng ($reason)",
                recordingNow = dspEngine.isRecording,
                accessibilityCurrentlyOn = true
            )
            return
        }

        // Cấp độ 2: Nếu chưa cấp WRITE_SECURE_SETTINGS qua ADB:
        // Ngay khi thoát App Ngân hàng / chuyển sang App khác -> Bật màn hình Trợ năng (nếu có quyền Overlay)
        // đồng thời đẩy thông báo Heads-Up ưu tiên cao lên đầu màn hình.
        if (!hasPromptedManualRestoreThisSession) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (pm?.isInteractive == true) {
                hasPromptedManualRestoreThisSession = true
                OTTCallAccessibilityService.lastDetectedApp = "$reason • Bấm bật lại hoặc vẫn tự ghi ngầm"
                updateNotification(
                    contentText = "$reason • Bấm [Bật Lại Trợ Năng] (Ghi âm ngầm vẫn chạy 100%)",
                    recordingNow = dspEngine.isRecording,
                    accessibilityCurrentlyOn = false
                )
                showHeadsUpRestoreAlert(reason)
                if (hasOverlayPermission(this)) {
                    openAccessibilitySettingsActivity()
                }
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
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(accIntent)
        } catch (_: Exception) {
        }
    }

    private fun showHeadsUpRestoreAlert(reason: String) {
        try {
            val accIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val pendingAcc = PendingIntent.getActivity(
                this,
                99,
                accIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alertNotif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_lock_power_off)
                .setContentTitle("Đã thoát $activeStealthAppName")
                .setContentText("Chạm vào đây để gạt BẬT LẠI Trợ năng S22U Call Recorder")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentIntent(pendingAcc)
                .setFullScreenIntent(pendingAcc, true)
                .addAction(
                    android.R.drawable.ic_lock_power_off,
                    "Bật Lại Ngay",
                    pendingAcc
                )
                .build()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(ALERT_NOTIF_ID, alertNotif)
        } catch (_: Exception) {
        }
    }

    private fun cancelRestoreAlertNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.cancel(ALERT_NOTIF_ID)
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
            // NGAY CẢ KHI TRỢ NĂNG ĐANG TẮT, vẫn tự động nhận diện Zalo/Viber/Telegram/WhatsApp qua UsageStatsManager và ghi âm 100%!
            if (OTTCallAccessibilityService.instance == null && autoRecordOttEnabled && !dspEngine.isRecording) {
                val detectedOttTag = resolveCurrentOttTagFromUsageStats()
                val tag = detectedOttTag ?: if (mode == AudioManager.MODE_IN_CALL) "SIM_Call" else "Zalo_OTT"
                OTTCallAccessibilityService.lastDetectedApp = "$tag (Đang ghi âm ngầm)"
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
                val saved = dspEngine.stopRecording()
                val savedTag = saved?.get("appTag") ?: "Cuộc gọi"
                OTTCallAccessibilityService.lastDetectedApp = "$savedTag (Đã lưu vào Download)"
                updateNotification(
                    "Đã lưu $savedTag vào /Download/S22U_Call_Recordings",
                    false,
                    OTTCallAccessibilityService.instance != null
                )
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val guardChannel = NotificationChannel(
                CHANNEL_ID,
                "S22U Smart Banking Stealth Guard",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Tự động ẩn Trợ năng khi mở App Ngân hàng/VNeID và tự động ghi âm ngầm"
                setShowBadge(false)
            }
            val alertChannel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "Khôi phục Trợ năng sau khi thoát Ngân hàng",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Thông báo bật lại Trợ năng ngay khi vừa thoát App Ngân hàng / VNeID"
                setShowBadge(true)
            }
            nm?.createNotificationChannel(guardChannel)
            nm?.createNotificationChannel(alertChannel)
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
