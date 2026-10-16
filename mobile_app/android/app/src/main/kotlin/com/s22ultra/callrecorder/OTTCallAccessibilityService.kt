package com.s22ultra.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Dịch vụ Trợ năng Thông minh (Smart Banking Stealth v1.6.0 - Pre-Launch Click Interception):
 * - Giải quyết triệt để vấn đề App Ngân hàng kiểm tra Accessibility ngay trong Application.onCreate()
 *   (trước khi cửa sổ Activity của App Ngân hàng kịp hiển thị):
 *   1. Bắt sự kiện chạm ngón tay `TYPE_VIEW_CLICKED` ngay trên màn hình chính Samsung One UI Launcher:
 *      Khi ngón tay chạm vào icon mang tên Ngân hàng (VCB, BIDV, MB, Techcombank, VietinBank, ACB, VPBank, VNeID...),
 *      dịch vụ gọi `disableSelf()` ở t = 1ms (trước khi tiến trình App Ngân hàng kịp khởi tạo 150ms!).
 *   2. Bắt cả `TYPE_WINDOW_STATE_CHANGED` & `TYPE_WINDOWS_CHANGED` với quyền `QUERY_ALL_PACKAGES` đầy đủ.
 *   3. Hỗ trợ nút tắt tức thì 1-chạm `[Tắt Trợ Năng (Ngân Hàng)]` ngay trên thanh thông báo.
 */
class OTTCallAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: OTTCallAccessibilityService? = null
            private set

        @Volatile
        var autoRecordOttEnabled: Boolean = true

        @Volatile
        var bankingStealthEnabled: Boolean = true

        @Volatile
        var remoteGainBoost: Float = 2.2f

        @Volatile
        var autoBoostVolume: Boolean = true

        @Volatile
        var lastDetectedApp: String = "Sẵn sàng (Pre-Launch Banking Stealth)"

        val SUPPORTED_OTT_PACKAGES = mapOf(
            "com.zing.zalo" to "Zalo",
            "com.viber.voip" to "Viber",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.samsung.android.incallui" to "SIM_Call",
            "com.samsung.android.dialer" to "SIM_Call"
        )

        val VIETNAMESE_BANKING_PACKAGES = setOf(
            "com.VCB",
            "com.vnpay.bidv",
            "com.vietinbank.ipay",
            "com.mbmobile",
            "vn.com.techcombank.bb.app",
            "com.techcombank.bb.app",
            "com.acb.ACBMobile",
            "mobile.acb.com.vn",
            "com.vnpay.vpbankonline",
            "com.agribank.agribankplus",
            "com.vnpay.agribank",
            "com.tpb.mb.gprsandroid",
            "com.sacombank.ewallet",
            "src.com.sacombank",
            "com.vnpay.hdbank",
            "com.hdbank.ipuma",
            "vn.shb.mbanking",
            "com.shb.mbanking",
            "com.vnpay.vib",
            "com.vib.myvib2",
            "com.ocb.omniextra",
            "vn.com.ocb.awe",
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
            "com.vnid",
            "com.vnpay.wallet"
        )

        val BANKING_KEYWORDS = listOf(
            "bank", "vcb", "vietcombank", "bidv", "ipay", "vietinbank",
            "mbmobile", "mbbank", "mb bank", "techcombank", "vpbank",
            "agribank", "sacombank", "tpbank", "hdbank", "myvib", "vib ",
            "ocb", "seabank", "lpbank", "lienviet", "shb", "eximbank",
            "msb", "nam a", "abbank", "pvcombank", "bvbank", "timo",
            "cake", "tnex", "vnid", "vneid", "smartbanking", "digibank",
            "mbanking", "ebanking", "ngân hàng"
        )

        fun isBankingPackage(pkgName: String): Boolean {
            if (pkgName.startsWith("com.android.") ||
                pkgName.startsWith("com.samsung.") ||
                pkgName.startsWith("com.sec.") ||
                pkgName.startsWith("com.google.") ||
                pkgName == "com.s22ultra.callrecorder" ||
                SUPPORTED_OTT_PACKAGES.containsKey(pkgName)
            ) {
                return false
            }
            if (VIETNAMESE_BANKING_PACKAGES.contains(pkgName)) return true
            val lower = pkgName.lowercase()
            return BANKING_KEYWORDS.any { lower.contains(it) }
        }

        fun isBankingLabelClicked(labelText: String): Boolean {
            if (labelText.isBlank()) return false
            val lower = labelText.lowercase().trim()
            if (lower.contains("s22u") || lower.contains("call recorder") || lower.contains("smart banking stealth")) {
                return false
            }
            return BANKING_KEYWORDS.any { lower.contains(it) } ||
                    lower == "mb" || lower == "acb" || lower == "vib" || lower == "shb" || lower == "ocb" || lower == "msb"
        }
    }

    lateinit var dspEngine: AudioDspRecorderEngine
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var currentForegroundOttApp: String? = null
    private var wasInActiveCall: Boolean = false

    private val audioStatePoller = object : Runnable {
        override fun run() {
            checkAudioCommunicationState()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        dspEngine = AudioDspRecorderEngine(applicationContext)
        lastDetectedApp = "Trợ năng ĐANG BẬT (Tự ẩn khi chạm icon Ngân hàng)"
        notifyForegroundGuardStatusChanged(enabled = true, reason = "Trợ năng ĐANG BẬT")
        handler.post(audioStatePoller)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkgName = event.packageName?.toString() ?: ""

        if (bankingStealthEnabled && !wasInActiveCall) {
            // CƠ CHẾ 1 (QUAN TRỌNG NHẤT): Bắt ngay khoảnh khắc ngón tay chạm vào Icon App Ngân hàng trên Màn hình chính
            // (Xảy ra trước khi tiến trình App Ngân hàng kịp khởi tạo Application.onCreate() 150ms!)
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                val clickedText = buildString {
                    event.text?.forEach { append(it).append(" ") }
                    if (event.contentDescription != null) append(event.contentDescription)
                }
                if (isBankingLabelClicked(clickedText)) {
                    triggerInstantSelfDisable("Icon: ${clickedText.trim()}")
                    return
                }
            }

            // CƠ CHẾ 2: Bắt sự kiện chuyển cửa sổ của bất kỳ gói App Ngân hàng nào
            if (pkgName.isNotEmpty() && isBankingPackage(pkgName)) {
                triggerInstantSelfDisable(pkgName)
                return
            }
        }

        // 3. Nếu là ứng dụng OTT hoặc cuộc gọi SIM
        if (SUPPORTED_OTT_PACKAGES.containsKey(pkgName)) {
            currentForegroundOttApp = SUPPORTED_OTT_PACKAGES[pkgName]
            lastDetectedApp = currentForegroundOttApp ?: pkgName
            checkAudioCommunicationState()
        }
    }

    fun triggerInstantSelfDisable(reason: String) {
        lastDetectedApp = "Đã tắt Trợ năng để dùng Ngân hàng ($reason)"
        notifyForegroundGuardBankingStealth(reason)
        disableSelf()
    }

    private fun notifyForegroundGuardBankingStealth(bankingPkg: String) {
        try {
            val svcIntent = Intent(this, CallMonitorForegroundService::class.java).apply {
                action = CallMonitorForegroundService.ACTION_BANKING_STEALTH_TRIGGERED
                putExtra("bankingPkg", bankingPkg)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        } catch (_: Exception) {
        }
    }

    private fun notifyForegroundGuardStatusChanged(enabled: Boolean, reason: String) {
        try {
            val svcIntent = Intent(this, CallMonitorForegroundService::class.java).apply {
                action = CallMonitorForegroundService.ACTION_START_GUARD
                putExtra("accEnabled", enabled)
                putExtra("reason", reason)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        } catch (_: Exception) {
        }
    }

    private fun checkAudioCommunicationState() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = am.mode

        val isCallActive = (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL)

        if (isCallActive && !wasInActiveCall) {
            wasInActiveCall = true
            val defaultTag = if (mode == AudioManager.MODE_IN_CALL) "SIM_Call" else "OTT_VoIP"
            val tag = currentForegroundOttApp ?: defaultTag
            lastDetectedApp = "$tag (Đang ghi âm)"
            if (autoRecordOttEnabled && !dspEngine.isRecording) {
                dspEngine.startRecording(
                    appTag = tag,
                    remoteGainBoost = remoteGainBoost,
                    autoBoostEarpieceVolume = autoBoostVolume
                )
            }
        } else if (!isCallActive && wasInActiveCall) {
            wasInActiveCall = false
            if (dspEngine.isRecording && dspEngine.currentAppTag != "Manual") {
                val saved = dspEngine.stopRecording()
                if (saved != null) {
                    lastDetectedApp = "${saved["appTag"]} (Đã lưu)"
                }
            }
        }
    }

    override fun onInterrupt() {
        if (::dspEngine.isInitialized && dspEngine.isRecording) {
            dspEngine.stopRecording()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(audioStatePoller)
        if (::dspEngine.isInitialized && dspEngine.isRecording) {
            dspEngine.stopRecording()
        }
        instance = null
        super.onDestroy()
    }
}
