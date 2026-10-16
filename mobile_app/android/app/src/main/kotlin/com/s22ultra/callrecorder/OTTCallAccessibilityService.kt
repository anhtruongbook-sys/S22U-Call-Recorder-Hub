package com.s22ultra.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Dịch vụ Trợ năng Thông minh (Smart Banking Stealth v1.7.0):
 * 1. Pre-Launch Click Interception (`TYPE_VIEW_CLICKED` @ 1ms):
 *    Bắt ngay khoảnh khắc ngón tay chạm vào Icon App Ngân hàng / VNeID / App đã học trên màn hình chính
 *    trước khi tiến trình App kịp khởi tạo `Application.onCreate()`.
 * 2. Universal Anti-Accessibility Prompt Scanner + Auto-Learn:
 *    Tự động quét nội dung hộp thoại của mọi ứng dụng lạ. Nếu phát hiện yêu cầu tắt Trợ năng /
 *    Hỗ trợ tiếp cận / Accessibility -> Tự động tắt ngay lập tức VÀ tự lưu tên gói vào bộ nhớ `Auto-Learn`.
 * 3. Phân biệt rõ:
 *    - `triggerAutoStealthDisable(...)` -> Báo cho `CallMonitorForegroundService` tự động BẬT LẠI sau khi thoát App.
 *    - `triggerManualDisable(...)` -> Khi người dùng chủ động tắt trong App -> Giữ nguyên TẮT, không tự bật lại.
 */
class OTTCallAccessibilityService : AccessibilityService() {

    companion object {
        private const val PREFS_NAME = "s22u_stealth_prefs"
        private const val KEY_LEARNED_PKGS = "learned_stealth_pkgs"
        private const val KEY_LEARNED_LABELS = "learned_stealth_labels"

        @Volatile
        var instance: OTTCallAccessibilityService? = null
            private set

        @Volatile
        var autoRecordOttEnabled: Boolean = true

        @Volatile
        var bankingStealthEnabled: Boolean = true

        @Volatile
        var autoRestoreEnabled: Boolean = true

        @Volatile
        var remoteGainBoost: Float = 2.2f

        @Volatile
        var autoBoostVolume: Boolean = true

        @Volatile
        var lastDetectedApp: String = "Sẵn sàng (Auto-Stealth & Auto-Restore)"

        @Volatile
        var learnedStealthPackages = mutableSetOf<String>()

        @Volatile
        var learnedStealthLabels = mutableSetOf<String>()

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
            "com.vnpay.wallet",
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

        // Các cụm từ cảnh báo yêu cầu tắt Trợ năng trên hộp thoại của mọi ứng dụng (Việt + Anh)
        val ACCESSIBILITY_PROMPT_PHRASES = listOf(
            "tắt trợ năng",
            "quyền trợ năng",
            "dịch vụ trợ năng",
            "ứng dụng trợ năng",
            "đang bật trợ năng",
            "sử dụng trợ năng",
            "tắt quyền trợ năng",
            "vô hiệu hóa trợ năng",
            "hỗ trợ tiếp cận",
            "khả năng truy cập",
            "chiếm quyền điều khiển",
            "accessibility service",
            "disable accessibility",
            "turn off accessibility",
            "accessibility is enabled",
            "accessibility permission"
        )

        fun loadLearnedApps(context: Context) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                learnedStealthPackages = (prefs.getStringSet(KEY_LEARNED_PKGS, emptySet()) ?: emptySet()).toMutableSet()
                learnedStealthLabels = (prefs.getStringSet(KEY_LEARNED_LABELS, emptySet()) ?: emptySet()).toMutableSet()
            } catch (_: Exception) {
            }
        }

        fun learnNewStealthApp(context: Context, pkgName: String, appLabel: String) {
            if (isSystemOrSafePackage(pkgName)) return
            try {
                learnedStealthPackages.add(pkgName)
                if (appLabel.isNotBlank() && appLabel.length >= 2) {
                    learnedStealthLabels.add(appLabel.lowercase().trim())
                }
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit()
                    .putStringSet(KEY_LEARNED_PKGS, learnedStealthPackages.toSet())
                    .putStringSet(KEY_LEARNED_LABELS, learnedStealthLabels.toSet())
                    .apply()
            } catch (_: Exception) {
            }
        }

        fun clearLearnedApps(context: Context) {
            try {
                learnedStealthPackages.clear()
                learnedStealthLabels.clear()
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().remove(KEY_LEARNED_PKGS).remove(KEY_LEARNED_LABELS).apply()
            } catch (_: Exception) {
            }
        }

        fun isSystemOrSafePackage(pkgName: String): Boolean {
            if (pkgName.isBlank()) return true
            return pkgName.startsWith("com.android.") ||
                    pkgName.startsWith("com.samsung.") ||
                    pkgName.startsWith("com.sec.") ||
                    pkgName.startsWith("com.google.") ||
                    pkgName == "android" ||
                    pkgName == "com.s22ultra.callrecorder" ||
                    SUPPORTED_OTT_PACKAGES.containsKey(pkgName)
        }

        fun isBankingOrLearnedPackage(pkgName: String): Boolean {
            if (isSystemOrSafePackage(pkgName)) return false
            if (VIETNAMESE_BANKING_PACKAGES.contains(pkgName)) return true
            if (learnedStealthPackages.contains(pkgName)) return true
            val lower = pkgName.lowercase()
            return BANKING_KEYWORDS.any { lower.contains(it) }
        }

        fun isBankingOrLearnedLabelClicked(labelText: String): Boolean {
            if (labelText.isBlank()) return false
            val lower = labelText.lowercase().trim()
            if (lower.contains("s22u") ||
                lower.contains("call recorder") ||
                lower.contains("smart banking") ||
                lower.contains("trợ năng")
            ) {
                return false
            }
            if (learnedStealthLabels.any { it.isNotBlank() && lower.contains(it) }) return true
            return BANKING_KEYWORDS.any { lower.contains(it) } ||
                    lower == "mb" || lower == "acb" || lower == "vib" || lower == "shb" || lower == "ocb" || lower == "msb"
        }

        fun containsAccessibilityBlockPrompt(rawText: String): Boolean {
            if (rawText.length < 8) return false
            val lower = rawText.lowercase()
            // Nếu hộp thoại của app khác nhắc đích danh các cụm từ yêu cầu tắt trợ năng
            if (ACCESSIBILITY_PROMPT_PHRASES.any { lower.contains(it) }) {
                return true
            }
            // Hoặc nếu hộp thoại cảnh báo bảo mật liệt kê đích danh "s22u call recorder"
            if (lower.contains("s22u call recorder") &&
                (lower.contains("tắt") || lower.contains("bảo mật") || lower.contains("disable") || lower.contains("trợ năng"))
            ) {
                return true
            }
            return false
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
        loadLearnedApps(applicationContext)
        dspEngine = AudioDspRecorderEngine(applicationContext)
        lastDetectedApp = "Trợ năng ĐANG BẬT (Tự ẩn & Tự bật lại)"
        notifyForegroundGuardStatusChanged(enabled = true, reason = "Trợ năng ĐANG BẬT")
        handler.post(audioStatePoller)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkgName = event.packageName?.toString() ?: ""

        if (bankingStealthEnabled && !wasInActiveCall) {
            // TẦNG 1: Bắt ngay khoảnh khắc ngón tay chạm vào Icon trên Màn hình chính (1ms trước khi App mở)
            if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
                val clickedText = buildString {
                    event.text?.forEach { append(it).append(" ") }
                    if (event.contentDescription != null) append(event.contentDescription)
                }
                if (isBankingOrLearnedLabelClicked(clickedText)) {
                    triggerAutoStealthDisable(
                        reason = "Icon: ${clickedText.trim()}",
                        targetPkg = pkgName
                    )
                    return
                }
            }

            // TẦNG 2: Bắt sự kiện chuyển cửa sổ của bất kỳ gói App Ngân hàng / VNeID / App đã học nào
            if (pkgName.isNotEmpty() && isBankingOrLearnedPackage(pkgName)) {
                val appLabel = resolveAppLabel(pkgName)
                triggerAutoStealthDisable(
                    reason = appLabel,
                    targetPkg = pkgName
                )
                return
            }

            // TẦNG 3 (UNIVERSAL AUTO-LEARN DETECTOR):
            // Quét nội dung hộp thoại của mọi ứng dụng lạ xem có yêu cầu tắt Trợ năng / Accessibility không
            if (pkgName.isNotEmpty() && !isSystemOrSafePackage(pkgName)) {
                val eventText = buildString {
                    event.text?.forEach { append(it).append(" ") }
                    if (event.contentDescription != null) append(event.contentDescription).append(" ")
                }
                var detectedPrompt = containsAccessibilityBlockPrompt(eventText)

                if (!detectedPrompt &&
                    (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                     event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                ) {
                    val windowText = extractVisibleWindowTextSafe(rootInActiveWindow)
                    detectedPrompt = containsAccessibilityBlockPrompt(windowText)
                }

                if (detectedPrompt) {
                    val appLabel = resolveAppLabel(pkgName)
                    learnNewStealthApp(applicationContext, pkgName, appLabel)
                    triggerAutoStealthDisable(
                        reason = "Tự phát hiện & học: $appLabel",
                        targetPkg = pkgName
                    )
                    return
                }
            }
        }

        // 4. Nếu là ứng dụng OTT hoặc cuộc gọi SIM
        if (SUPPORTED_OTT_PACKAGES.containsKey(pkgName)) {
            currentForegroundOttApp = SUPPORTED_OTT_PACKAGES[pkgName]
            lastDetectedApp = currentForegroundOttApp ?: pkgName
            checkAudioCommunicationState()
        }
    }

    private fun extractVisibleWindowTextSafe(root: AccessibilityNodeInfo?): String {
        if (root == null) return ""
        val sb = StringBuilder()
        var visited = 0
        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 5 || visited > 35) return
            visited++
            node.text?.let {
                if (it.isNotEmpty()) sb.append(it).append(" ")
            }
            node.contentDescription?.let {
                if (it.isNotEmpty()) sb.append(it).append(" ")
            }
            val count = node.childCount
            for (i in 0 until count) {
                if (visited > 35) break
                try {
                    traverse(node.getChild(i), depth + 1)
                } catch (_: Exception) {
                }
            }
        }
        try {
            traverse(root, 0)
        } catch (_: Exception) {
        }
        return sb.toString()
    }

    private fun resolveAppLabel(pkgName: String): String {
        return try {
            val ai = packageManager.getApplicationInfo(pkgName, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkgName
        }
    }

    /**
     * Tắt tự động tạm thời (AUTO_STEALTH) khi vào App Ngân hàng / VNeID / App cấm Trợ năng.
     * Sẽ được `CallMonitorForegroundService` tự động BẬT LẠI sau khi người dùng thoát App đó.
     */
    fun triggerAutoStealthDisable(reason: String, targetPkg: String = "") {
        lastDetectedApp = "Tạm ẩn cho $reason (Sẽ tự bật lại khi thoát)"
        try {
            val svcIntent = Intent(this, CallMonitorForegroundService::class.java).apply {
                action = CallMonitorForegroundService.ACTION_BANKING_STEALTH_TRIGGERED
                putExtra("bankingPkg", reason)
                putExtra("rawPkg", targetPkg)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        } catch (_: Exception) {
        }
        disableSelf()
    }

    /**
     * Tắt CHỦ ĐỘNG bởi người dùng trong App (`MANUAL_OFF`).
     * Tuyệt đối KHÔNG tự động bật lại cho đến khi người dùng chủ động bật lại.
     */
    fun triggerManualDisable(reason: String = "Tắt chủ động trong App") {
        lastDetectedApp = "Đã tắt chủ động ($reason) • Không tự bật lại"
        try {
            val svcIntent = Intent(this, CallMonitorForegroundService::class.java).apply {
                action = CallMonitorForegroundService.ACTION_MANUAL_DISABLE_ACCESSIBILITY
                putExtra("reason", reason)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent)
            } else {
                startService(svcIntent)
            }
        } catch (_: Exception) {
        }
        disableSelf()
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
