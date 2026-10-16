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
 * Dịch vụ Trợ năng Thông minh (Smart Banking Stealth v1.5.0):
 * 1. Bình thường giữ đặc quyền thu âm 2 chiều cho Zalo, Viber, Telegram, WhatsApp & SIM (48kHz Smooth DSP).
 * 2. Ngay khoảnh khắc người dùng chạm mở bất kỳ App Ngân hàng / Ví điện tử / VNeID nào:
 *    - Trong vòng < 5ms đầu tiên của sự kiện TYPE_WINDOW_STATE_CHANGED (trước khi App Ngân hàng kịp quét bảo mật),
 *      dịch vụ gọi ngay lập tức `disableSelf()` để tự tắt Trợ năng hoàn toàn.
 *    - Đồng thời báo cho CallMonitorForegroundService hiển thị nút "Bật lại Trợ năng" (hoặc tự động bật lại ngầm
 *      nếu máy có quyền WRITE_SECURE_SETTINGS) sau khi người dùng giao dịch ngân hàng xong.
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
        var lastDetectedApp: String = "Sẵn sàng (Smart Banking Stealth)"

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

        // Danh sách các ứng dụng Ngân hàng Việt Nam, Ví điện tử & VNeID kiểm tra Accessibility
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
            "bank", "vcb", "bidv", "ipay", "mbmobile", "techcombank",
            "vpbank", "agribank", "sacombank", "tpbank", "hdbank",
            "vib.", "ocb.", "seabank", "lpbank", "shb.", "eximbank",
            "vnid", "smartbanking", "digibank", "mbanking", "ebanking"
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
        lastDetectedApp = "Trợ năng ĐANG BẬT (Sẵn sàng tự ẩn khi mở Ngân hàng)"
        handler.post(audioStatePoller)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkgName = event.packageName?.toString() ?: return

        // 1. KIỂM TRA SMART BANKING STEALTH TRƯỚC TIÊN (< 5ms):
        // Nếu người dùng vừa chạm mở App Ngân hàng / VNeID và đang không trong cuộc gọi -> Tự động tắt Trợ năng ngay lập tức!
        if (bankingStealthEnabled && !wasInActiveCall && isBankingPackage(pkgName)) {
            lastDetectedApp = "Đã tự động TẮT Trợ năng cho App Ngân hàng ($pkgName)"
            notifyForegroundGuardBankingStealth(pkgName)
            disableSelf()
            return
        }

        // 2. Nếu là ứng dụng OTT hoặc cuộc gọi SIM
        if (SUPPORTED_OTT_PACKAGES.containsKey(pkgName)) {
            currentForegroundOttApp = SUPPORTED_OTT_PACKAGES[pkgName]
            lastDetectedApp = currentForegroundOttApp ?: pkgName
            checkAudioCommunicationState()
        }
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
