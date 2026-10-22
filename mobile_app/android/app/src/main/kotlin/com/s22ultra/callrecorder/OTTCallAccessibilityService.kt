package com.s22ultra.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * Cầu nối Thu âm 2 Chiều & Lá Chắn Ngân Hàng Tức Thời (`0ms Pre-Launch Banking Stealth + Auto-Restore`):
 * 1. Luôn sẵn sàng ở `0ms` trước khi cuộc gọi Zalo/OTT bắt đầu để `AudioRecord.Builder().setContext(this)`
 *    được hệ điều hành Android 16 cấp quyền thu âm `VOICE_RECOGNITION` 2 chiều rõ nét (.m4a).
 * 2. Gắn neo `1x1` vô hình `TYPE_ACCESSIBILITY_OVERLAY` trong suốt thời gian đàm thoại để `AudioPolicyService::UidPolicy::isA11yOnTop()`
 *    trên Android 16 / One UI 8 luôn đạt `true` 100%, không bao giờ bị tắt tiếng khi 2 người bắt đầu nói chuyện.
 * 3. Ngay khoảnh khắc người dùng chạm vào biểu tượng App Ngân hàng / VNeID trên Màn hình chính (`TYPE_VIEW_CLICKED` tại Launcher)
 *    hoặc mở cửa sổ App Ngân hàng (`TYPE_WINDOW_STATE_CHANGED`): lập tức gọi `disableSelf()` trong `0ms`.
 */
class OTTCallAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: OTTCallAccessibilityService? = null
            private set

        val BANKING_AND_GOV_PACKAGES = setOf(
            "com.VCB",
            "com.vnpay.bidv",
            "com.vietinbank.ipay",
            "vn.com.techcombank.bb.app",
            "com.techcombank.one",
            "com.mbmobile",
            "mobile.acb.com.vn",
            "com.vnpay.vpbankonline",
            "com.tpb.mb.gprsandroid",
            "com.vnpay.Agribank3g",
            "com.sacombank.ewallet",
            "src.com.sacombank",
            "com.shb.mbanking",
            "vn.shb.mbanking",
            "com.vnpay.hdbank",
            "com.hdbank.ipuma",
            "com.vib.myvib2",
            "com.vnpay.vib",
            "com.ocb.omniextra",
            "vn.com.ocb.awe",
            "vn.shinhan.global.vn.bank",
            "com.tpbank.quickpay",
            "com.seabank.seateacher",
            "vn.com.seabank.mb1",
            "com.msb.mbanking",
            "com.lpbank.lienviet24h",
            "vn.com.lpb.lienviet24h",
            "com.vnpay.eximbank",
            "com.namabank.openbanking",
            "com.bvbank.digimi",
            "com.abbank.youbank",
            "com.pvcombank.PSG",
            "com.vnpay.wallet",
            "com.vnid",
            "vn.gov.eid",
            "com.bhtd.etax",
            "vn.gov.gdt.etaxmobile"
        )

        val BANKING_PACKAGE_KEYWORDS = listOf(
            "vietcombank", "vnpay.bidv", "vietinbank", "techcombank",
            "mbmobile", "vpbankonline", "agribank", "sacombank",
            "shb.mbanking", "hdbank", "myvib", "ocb.omni", "seabank",
            "msb.mbanking", "lienviet24h", "eximbank", "namabank",
            "abbank", "pvcombank", "bvbank", "vn.gov.eid", "etaxmobile"
        )

        val EXACT_BANKING_ICON_LABELS = setOf(
            "vcb digibank", "vietcombank", "bidv smartbanking", "bidv",
            "vietinbank ipay", "techcombank", "techcombank mobile",
            "mbbank", "mb bank", "acb one", "vpbank neo", "tpbank mobile",
            "agribank plus", "agribank", "sacombank pay", "sacombank mbanking",
            "hdbank", "myvib", "ocb omni", "seabank", "lpbank", "shb mobile",
            "msb mbanhking", "msb", "vneid", "etax mobile"
        )

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            return instance != null
        }

        fun isAccessibilitySettingListed(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = ComponentName(context, OTTCallAccessibilityService::class.java).flattenToString()
            val shortName = "${context.packageName}/.OTTCallAccessibilityService"
            return enabledServices.contains(expected) || enabledServices.contains(shortName)
        }

        fun isLauncherPackage(pkg: String): Boolean {
            if (pkg.isBlank()) return false
            val lower = pkg.lowercase()
            return lower == "com.sec.android.app.launcher" ||
                    lower.endsWith(".launcher")
        }

        fun isSystemOrSafePackage(pkg: String): Boolean {
            if (pkg.isBlank()) return true
            return pkg.startsWith("com.android.") ||
                    pkg.startsWith("com.samsung.") ||
                    pkg.startsWith("com.sec.") ||
                    pkg.startsWith("com.google.") ||
                    pkg == "android" ||
                    pkg == "com.s22ultra.callrecorder" ||
                    CallMonitorForegroundService.SUPPORTED_OTT_PACKAGES.containsKey(pkg)
        }

        fun isBankingOrProtectedPackage(pkg: String): Boolean {
            if (isSystemOrSafePackage(pkg)) return false
            if (BANKING_AND_GOV_PACKAGES.contains(pkg)) return true
            val lower = pkg.lowercase()
            return BANKING_PACKAGE_KEYWORDS.any { lower.contains(it) }
        }

        fun isBankingIconLabelClicked(labelText: String): Boolean {
            val clean = labelText.trim().lowercase()
            if (clean.length !in 3..28) return false
            return EXACT_BANKING_ICON_LABELS.contains(clean)
        }
    }

    val dspEngine: AudioDspRecorderEngine
        get() = CallMonitorForegroundService.getSharedEngine(this)

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var a11yAudioAnchorView: View? = null

    fun attachAccessibilityAudioAnchor() {
        val action = Runnable {
            if (a11yAudioAnchorView != null) return@Runnable
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@Runnable
                val view = View(this)
                val params = WindowManager.LayoutParams(
                    1,
                    1,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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
                a11yAudioAnchorView = view
            } catch (_: Exception) {
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    fun detachAccessibilityAudioAnchor() {
        val action = Runnable {
            val view = a11yAudioAnchorView ?: return@Runnable
            a11yAudioAnchorView = null
            try {
                val wm = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                wm?.removeView(view)
            } catch (_: Exception) {
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            mainHandler.post(action)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    private var a11yModeListener: AudioManager.OnModeChangedListener? = null
    private var a11yCallbacksRegistered = false

    private val a11yRecordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            super.onRecordingConfigChanged(configs)
            mainHandler.post {
                evaluateCallStateInAccessibilityBridge()
            }
        }
    }

    private val a11yCallEndRunnable = Runnable {
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
        val inCallNow = (mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_IN_CALL ||
                hasVoipStream)
        if (!inCallNow) {
            detachAccessibilityAudioAnchor()
            if (dspEngine.isRecording && dspEngine.currentAppTag != "Manual") {
                val saved = dspEngine.stopRecording()
                val savedTag = saved?.get("appTag") ?: "Cuộc gọi"
                CallMonitorForegroundService.lastDetectedApp =
                    "$savedTag (Đã lưu .m4a • Cầu nối 2 chiều sẵn sàng)"
            }
        }
    }

    private fun registerDirectAudioCallbacks() {
        if (a11yCallbacksRegistered || !CallMonitorForegroundService.isMasterAppEnabled(this)) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val l = AudioManager.OnModeChangedListener { _ ->
                    mainHandler.post {
                        evaluateCallStateInAccessibilityBridge()
                    }
                }
                am.addOnModeChangedListener(mainExecutor, l)
                a11yModeListener = l
            }
            am.registerAudioRecordingCallback(a11yRecordingCallback, mainHandler)
            a11yCallbacksRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterDirectAudioCallbacks() {
        if (!a11yCallbacksRegistered) return
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                a11yModeListener?.let { am?.removeOnModeChangedListener(it) }
            }
            am?.unregisterAudioRecordingCallback(a11yRecordingCallback)
        } catch (_: Exception) {
        }
        a11yModeListener = null
        a11yCallbacksRegistered = false
    }

    fun onMasterPowerChanged(enabled: Boolean) {
        if (enabled) {
            registerDirectAudioCallbacks()
            evaluateCallStateInAccessibilityBridge()
        } else {
            mainHandler.removeCallbacks(a11yCallEndRunnable)
            unregisterDirectAudioCallbacks()
            detachAccessibilityAudioAnchor()
            if (dspEngine.isRecording) {
                dspEngine.stopRecording()
            }
        }
    }

    fun evaluateCallStateInAccessibilityBridge() {
        if (!CallMonitorForegroundService.isMasterAppEnabled(this)) {
            detachAccessibilityAudioAnchor()
            return
        }
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
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
        val hasActiveOttCallNotif = !OTTCallNotificationListener.currentActiveOttTag.isNullOrBlank() &&
                OTTCallNotificationListener.isCallAnswered
        val inCallNow = (mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_IN_CALL ||
                hasVoipStream ||
                hasActiveOttCallNotif)

        if (inCallNow) {
            mainHandler.removeCallbacks(a11yCallEndRunnable)
            attachAccessibilityAudioAnchor()
            if (!dspEngine.isRecording) {
                startCallRecordingFromAccessibilityBridge(mode)
            } else {
                dspEngine.requestAccessibilityStreamUpgrade()
            }
            CallMonitorForegroundService.instance?.evaluateAudioHardwareInterrupt()
        } else if (mode != AudioManager.MODE_RINGTONE && dspEngine.isRecording && dspEngine.currentAppTag != "Manual") {
            mainHandler.removeCallbacks(a11yCallEndRunnable)
            mainHandler.postDelayed(a11yCallEndRunnable, 650L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        CallMonitorForegroundService.dismissBridgeAlertNotification(this)
        if (!CallMonitorForegroundService.isMasterAppEnabled(this)) {
            // KHÔNG gọi disableSelf() khi người dùng tạm TẮT ứng dụng để giữ nguyên quyền Trợ năng cho lần BẬT sau!
            detachAccessibilityAudioAnchor()
            unregisterDirectAudioCallbacks()
            return
        }
        CallMonitorForegroundService.lastDetectedApp = "Cầu nối 2 chiều S22U Call Bridge ĐANG BẬT (Sẵn sàng thu âm)"
        registerDirectAudioCallbacks()
        evaluateCallStateInAccessibilityBridge()
    }

    fun startCallRecordingFromAccessibilityBridge(audioMode: Int) {
        if (!CallMonitorForegroundService.isMasterAppEnabled(this)) return
        attachAccessibilityAudioAnchor()
        if (dspEngine.isRecording) {
            dspEngine.requestAccessibilityStreamUpgrade()
            return
        }

        val tag = CallMonitorForegroundService.instance?.resolveActiveCallTag(audioMode)
            ?: OTTCallNotificationListener.currentActiveOttTag
            ?: if (audioMode == AudioManager.MODE_IN_CALL) "SIM_Call" else "Zalo_OTT"

        dspEngine.startRecording(
            appTag = tag,
            remoteGainBoost = CallMonitorForegroundService.remoteGainBoost,
            autoBoostEarpieceVolume = CallMonitorForegroundService.autoBoostVolume
        )
        CallMonitorForegroundService.lastDetectedApp = "$tag (Đang thu âm 2 chiều .m4a)"
    }

    fun stopCallRecordingAndKeepReady(): Map<String, Any?>? {
        detachAccessibilityAudioAnchor()
        return if (dspEngine.isRecording) {
            dspEngine.stopRecording()
        } else {
            null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!CallMonitorForegroundService.isMasterAppEnabled(this)) {
            detachAccessibilityAudioAnchor()
            return
        }
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""

        // TẦNG 1: Bắt ngay khoảnh khắc chạm vào Icon App Ngân hàng / VNeID trên Màn hình chính (Launcher)
        // Lập tức gọi disableSelf() trong 0ms (kể cả khi chưa cấp WRITE_SECURE_SETTINGS) để App Ngân hàng KHÔNG BAO GIỜ báo lỗi!
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && !dspEngine.isRecording && isLauncherPackage(pkg)) {
            val clickedText = buildString {
                event.text?.forEach { append(it).append(" ") }
                if (event.contentDescription != null) append(event.contentDescription)
            }
            if (isBankingIconLabelClicked(clickedText)) {
                engageInstantBankingStealth("Icon ${clickedText.trim()}")
                return
            }
        }

        // TẦNG 2: Bắt sự kiện chuyển cửa sổ của bất kỳ App Ngân hàng / VNeID nào -> Tắt Trợ năng tức thì trong 0ms!
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg.isNotEmpty()) {
            if (isBankingOrProtectedPackage(pkg)) {
                engageInstantBankingStealth(pkg)
                return
            }
            if (CallMonitorForegroundService.SUPPORTED_OTT_PACKAGES.containsKey(pkg)) {
                evaluateCallStateInAccessibilityBridge()
            }
        } else if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            CallMonitorForegroundService.SUPPORTED_OTT_PACKAGES.containsKey(pkg) &&
            !dspEngine.isRecording
        ) {
            evaluateCallStateInAccessibilityBridge()
        }
    }

    private fun engageInstantBankingStealth(bankingTarget: String) {
        detachAccessibilityAudioAnchor()
        if (dspEngine.isRecording) {
            dspEngine.stopRecording()
        }
        CallMonitorForegroundService.lastDetectedApp =
            "Đã tự tắt Trợ năng 0ms khi mở $bankingTarget (An toàn 100% Ngân hàng)"
        CallMonitorForegroundService.onBankingStealthEngaged(this)
        safelyDisableSelfNow()
    }

    fun safelyDisableSelfNow() {
        unregisterDirectAudioCallbacks()
        detachAccessibilityAudioAnchor()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                disableSelf()
            }
        } catch (_: Exception) {
        }
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        unregisterDirectAudioCallbacks()
        detachAccessibilityAudioAnchor()
        instance = null
        super.onDestroy()
    }
}
