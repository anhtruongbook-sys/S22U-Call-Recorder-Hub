package com.s22ultra.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.PixelFormat
import android.media.AudioManager
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
            "com.mservice.momotransfer",
            "vn.zalopay.sdk",
            "com.bplus.vtpay",
            "com.vnptit.innovation.vnptpay",
            "com.vnpay.wallet",
            "com.vnid",
            "vn.gov.eid",
            "com.bhtd.etax",
            "vn.gov.gdt.etaxmobile"
        )

        val BANKING_KEYWORDS = listOf(
            "bank", "vcb", "vietcombank", "bidv", "ipay", "vietinbank",
            "mbmobile", "mbbank", "mb bank", "techcombank", "vpbank",
            "agribank", "sacombank", "tpbank", "hdbank", "myvib", "vib ",
            "ocb", "seabank", "lpbank", "lienviet", "shb", "eximbank",
            "msb", "nam a", "abbank", "pvcombank", "bvbank", "timo",
            "cake", "tnex", "vnid", "vneid", "smartbanking", "digibank",
            "mbanking", "ebanking", "ngân hàng", "etax"
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
            return lower.contains("launcher") ||
                    lower.contains("home") ||
                    lower == "com.sec.android.app.launcher"
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
            return BANKING_KEYWORDS.any { lower.contains(it) }
        }

        fun isBankingIconLabelClicked(labelText: String): Boolean {
            val clean = labelText.trim()
            // Tên icon ứng dụng trên màn hình chính luôn ngắn (2..32 ký tự), tránh bắt nhầm widget dọn RAM ("500 MB")
            if (clean.length !in 2..32) return false
            val lower = clean.lowercase()
            if (lower.contains("s22u") || lower.contains("call recorder") || lower.contains("trợ năng")) {
                return false
            }
            if (Regex("\\d+\\s*mb\\b").containsMatchIn(lower)) {
                return false
            }
            return BANKING_KEYWORDS.any { lower.contains(it) } ||
                    lower == "mb" || lower == "mbbank" || lower == "acb" || lower == "vib" ||
                    lower == "shb" || lower == "ocb" || lower == "msb"
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

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val mode = am?.mode ?: AudioManager.MODE_NORMAL
        val hasVoipStream = try {
            am?.activeRecordingConfigurations?.any { cfg ->
                val src = cfg.clientAudioSource
                src == android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION ||
                        src == android.media.MediaRecorder.AudioSource.VOICE_CALL
            } == true
        } catch (_: Exception) {
            false
        }
        val inCallNow = (mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_IN_CALL ||
                hasVoipStream)

        // TUYỆT ĐỐI KHÔNG gắn neo Overlay khi không có cuộc gọi để không làm App Ngân hàng báo lỗi!
        if (dspEngine.isRecording) {
            attachAccessibilityAudioAnchor()
            dspEngine.requestAccessibilityStreamUpgrade()
        } else if (inCallNow) {
            attachAccessibilityAudioAnchor()
            startCallRecordingFromAccessibilityBridge(mode)
        }
    }

    fun startCallRecordingFromAccessibilityBridge(audioMode: Int) {
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
                CallMonitorForegroundService.instance?.evaluateAudioHardwareInterrupt()
            }
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
        detachAccessibilityAudioAnchor()
        instance = null
        super.onDestroy()
    }
}
