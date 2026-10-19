package com.s22ultra.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Cầu nối Thu âm Tức thời Chỉ Trong Cuộc Gọi (`Ephemeral Call-Only Accessibility Bridge`):
 * - Nguyên lý hoạt động:
 *   1. Bình thường (khi KHÔNG có cuộc gọi): Tự động TẮT HOÀN TOÀN (`disableSelf()`), để mọi App Ngân hàng
 *      (VCB, BIDV, VietinBank, Techcombank, MB Bank, VNeID...) hoạt động 100% bình thường mọi lúc.
 *   2. Khi có cuộc gọi Zalo / OTT bắt đầu: `CallMonitorForegroundService` tự động BẬT dịch vụ này lên
 *      (nếu đã cấp `WRITE_SECURE_SETTINGS` 1 lần trên điện thoại) để hệ điều hành Android 16 mở khóa
 *      luồng thu âm `VOICE_RECOGNITION` 2 chiều rõ nét.
 *   3. Ngay khi cúp máy kết thúc cuộc gọi (hoặc ngay khi người dùng mở bất kỳ App Ngân hàng nào):
 *      Lập tức gọi `disableSelf()` để TẮT HOÀN TOÀN về trạng thái `OFF` trong 0ms!
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
            "com.shb.mbanking",
            "com.vnpay.hdbank",
            "com.vib.myvib2",
            "com.ocb.omniextra",
            "vn.shinhan.global.vn.bank",
            "com.tpbank.quickpay",
            "com.mservice.momotransfer",
            "vn.zalopay.sdk",
            "com.bplus.vtpay",
            "com.vnptit.innovation.vnptpay",
            "com.vnid",
            "vn.gov.eid"
        )

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val expected = ComponentName(context, OTTCallAccessibilityService::class.java).flattenToString()
            val shortName = "${context.packageName}/.OTTCallAccessibilityService"
            return enabledServices.contains(expected) || enabledServices.contains(shortName)
        }
    }

    lateinit var dspEngine: AudioDspRecorderEngine
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        instance = this
        dspEngine = AudioDspRecorderEngine(applicationContext)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (!::dspEngine.isInitialized) {
            dspEngine = AudioDspRecorderEngine(applicationContext)
        }

        // Kiểm tra ngay khi vừa kết nối: Nếu đang có cuộc gọi thì bắt đầu thu âm ngay lập tức!
        // Ngược lại, nếu đã cấp WRITE_SECURE_SETTINGS (Chế độ Tự Động Tức Thời) và hiện KHÔNG có cuộc gọi,
        // tự tắt ngay lập tức để trạng thái mặc định luôn là OFF 100%!
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

        if (inCallNow) {
            startCallRecordingFromAccessibilityBridge(mode)
        } else if (CallMonitorForegroundService.hasWriteSecureSettings(this)) {
            // Nếu đã có quyền tự bật/tắt tự động và hiện không có cuộc gọi -> Tắt ngay để luôn sạch sẽ cho App Ngân hàng!
            mainHandler.postDelayed({
                val curMode = (getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.mode ?: AudioManager.MODE_NORMAL
                if (curMode != AudioManager.MODE_IN_COMMUNICATION &&
                    curMode != AudioManager.MODE_IN_CALL &&
                    !dspEngine.isRecording
                ) {
                    safelyDisableSelfNow()
                }
            }, 1500L)
        }
    }

    fun startCallRecordingFromAccessibilityBridge(audioMode: Int) {
        if (!::dspEngine.isInitialized) {
            dspEngine = AudioDspRecorderEngine(applicationContext)
        }
        if (dspEngine.isRecording) return

        // Nếu CallMonitorForegroundService đang giữ phiên thu nháp không có quyền Accessibility,
        // chuyển giao ngay sang dspEngine của AccessibilityService để Android 16 mở khóa 100% âm thanh!
        val fgEngine = CallMonitorForegroundService.instance?.dspEngine
        if (fgEngine != null && fgEngine.isRecording) {
            fgEngine.stopRecording()
        }

        val tag = CallMonitorForegroundService.instance?.resolveActiveCallTag(audioMode)
            ?: OTTCallNotificationListener.currentActiveOttTag
            ?: if (audioMode == AudioManager.MODE_IN_CALL) "SIM_Call" else "Zalo_OTT"

        dspEngine.startRecording(
            appTag = tag,
            remoteGainBoost = CallMonitorForegroundService.remoteGainBoost,
            autoBoostEarpieceVolume = CallMonitorForegroundService.autoBoostVolume
        )
        CallMonitorForegroundService.lastDetectedApp = "$tag (Đang thu âm 2 chiều qua Cầu nối Tức thời .m4a)"
    }

    fun stopCallRecordingAndAutoDisableIfPermitted(): Map<String, Any?>? {
        val saved = if (::dspEngine.isInitialized && dspEngine.isRecording) {
            dspEngine.stopRecording()
        } else {
            null
        }
        // Nếu máy đã cấp quyền tự bật/tắt (WRITE_SECURE_SETTINGS), lập tức TẮT Trợ năng ngay khi cúp máy!
        if (CallMonitorForegroundService.hasWriteSecureSettings(this)) {
            safelyDisableSelfNow()
        }
        return saved
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            // Bất biến Bảo vệ App Ngân hàng: Dù vì bất kỳ lý do gì người dùng mở App Ngân hàng / VNeID
            // khi Trợ năng đang bật, lập tức gọi disableSelf() trong 0ms để App Ngân hàng vào thẳng 100%!
            if (isBankingOrProtectedPackage(pkg)) {
                if (::dspEngine.isInitialized && dspEngine.isRecording) {
                    dspEngine.stopRecording()
                }
                CallMonitorForegroundService.lastDetectedApp =
                    "Đã tự tắt Trợ năng tức thì khi mở $pkg (An toàn 100% Ngân hàng)"
                safelyDisableSelfNow()
            }
        }
    }

    private fun isBankingOrProtectedPackage(pkg: String): Boolean {
        if (BANKING_AND_GOV_PACKAGES.contains(pkg)) return true
        val lower = pkg.lowercase()
        return lower.contains("bank") ||
                lower.contains("vcb") ||
                lower.contains("bidv") ||
                lower.contains("ipay") ||
                lower.contains("vnpay") ||
                lower.contains("smartbanking") ||
                lower.contains("vneid") ||
                lower.contains("eid")
    }

    fun safelyDisableSelfNow() {
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
        if (::dspEngine.isInitialized && dspEngine.isRecording) {
            dspEngine.stopRecording()
        }
        instance = null
        super.onDestroy()
    }
}
