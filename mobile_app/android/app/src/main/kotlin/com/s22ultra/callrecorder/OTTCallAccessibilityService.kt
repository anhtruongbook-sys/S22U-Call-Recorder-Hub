package com.s22ultra.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Cầu nối Thu âm 2 Chiều & Lá Chắn Ngân Hàng Tức Thời (`0ms Pre-Launch Banking Stealth + Auto-Disable`):
 * 1. Luôn sẵn sàng ở `0ms` trước khi cuộc gọi Zalo/OTT bắt đầu để `AudioRecord.Builder().setContext(this)`
 *    được hệ điều hành Android 16 cấp quyền thu âm `VOICE_RECOGNITION` 2 chiều rõ nét (.m4a).
 * 2. Gắn neo `1x1` vô hình `TYPE_ACCESSIBILITY_OVERLAY` trong suốt thời gian đàm thoại để `AudioPolicyService::UidPolicy::isA11yOnTop()`
 *    trên Android 16 / One UI 8 luôn đạt `true` 100%, không bao giờ bị tắt tiếng khi 2 người bắt đầu nói chuyện.
 * 3. Khi người dùng bấm `TẮT ỨNG DỤNG` HOẶC ngay khoảnh khắc chạm vào biểu tượng App Ngân hàng / VNeID trên Màn hình chính
 *    (`TYPE_VIEW_CLICKED` / `TYPE_VIEW_FOCUSED`) hoặc mở bất kỳ cửa sổ App Ngân hàng nào: lập tức gọi `disableSelf()` trong `0ms`!
 */
class OTTCallAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: OTTCallAccessibilityService? = null
            private set

        private val dynamicPackageCache = ConcurrentHashMap<String, Boolean>()
        private val dynamicInstalledBankingLabels = ConcurrentHashMap.newKeySet<String>()

        val BANKING_AND_GOV_PACKAGES = setOf(
            "com.VCB",
            "com.vnpay.bidv",
            "com.vietinbank.ipay",
            "vn.com.techcombank.bb.app",
            "com.techcombank.one",
            "com.mbmobile",
            "mobile.acb.com.vn",
            "com.vnpay.vpbankonline",
            "com.vpbank.neo",
            "com.tpb.mb.gprsandroid",
            "com.tpbank.quickpay",
            "com.vnpay.Agribank3g",
            "com.agribank.agribankplus",
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
            "com.seabank.seateacher",
            "vn.com.seabank.mb1",
            "com.msb.mbanking",
            "com.lpbank.lienviet24h",
            "vn.com.lpb.lienviet24h",
            "com.vnpay.eximbank",
            "com.eximbank.edigi",
            "com.namabank.openbanking",
            "com.bvbank.digimi",
            "com.abbank.youbank",
            "com.pvcombank.PSG",
            "com.vnpay.pvcombank",
            "com.vnpay.wallet",
            "com.vnpay.NAMABANK",
            "com.vnpay.bacabank",
            "com.vnpay.kienlongbank",
            "com.kienlongbank.klb",
            "com.vnpay.vietbank",
            "com.vnpay.vietabank",
            "com.vnpay.pgbank",
            "com.vnpay.saigonbank",
            "com.vnpay.baovietbank",
            "com.ncb.izmobile",
            "vn.com.woori.smart",
            "com.wooribank.smart.npib",
            "com.hsbc.hsbcvietnam",
            "com.uob.mightyvn",
            "com.scb.breeze.android.vn",
            "com.citibank.mobile.vn",
            "vn.cake.bank",
            "io.lifestyle.timo",
            "vn.tnex.consumer",
            "com.ocb.liobank",
            "vn.vikki.digitalbank",
            "com.mservice.momotransfer",
            "vn.com.vng.zalopay",
            "com.bplus.vtpay",
            "com.viettel.ViettelPay",
            "com.airpay.vn",
            "com.vnid",
            "vn.gov.eid",
            "com.bhtd.etax",
            "vn.gov.gdt.etaxmobile",
            "com.bhxh.vssid"
        )

        val BANKING_PACKAGE_KEYWORDS = listOf(
            "vietcombank", "com.vcb", "vnpay", "bidv", "vietinbank", "ipay",
            "techcombank", "mbmobile", "mbbank", "acb.com", "vpbank", "tpbank",
            "tpb.mb", "agribank", "sacombank", "shb.mbanking", "hdbank",
            "myvib", "ocb.omni", "ocb.awe", "seabank", "msb.mbanking",
            "lienviet", "lpbank", "eximbank", "namabank", "abbank", "pvcombank",
            "bvbank", "digimi", "kienlongbank", "vietbank", "vietabank",
            "bacabank", "pgbank", "saigonbank", "baovietbank", "izmobile",
            "shinhan", "woori", "hsbc", "uob.mighty", "citibank", "cake.bank",
            "timo", "tnex", "liobank", "momotransfer", "zalopay", "viettelpay",
            "vtpay", "vn.gov.eid", "com.vnid", "etaxmobile", "bhtd.etax", "vssid"
        )

        val EXACT_BANKING_ICON_LABELS = setOf(
            "vcb digibank", "vietcombank", "bidv smartbanking", "bidv",
            "vietinbank ipay", "vietinbank", "ipay", "techcombank", "techcombank mobile",
            "mbbank", "mb bank", "acb one", "acb", "vpbank neo", "vpbank",
            "tpbank mobile", "tpbank", "agribank plus", "agribank",
            "sacombank pay", "sacombank mbanking", "sacombank",
            "hdbank", "myvib", "vib", "ocb omni", "ocb", "seabank", "seamobile",
            "lpbank", "lienviet24h", "shb mobile", "shb", "saigonbank",
            "msb mbanking", "msb mbanhking", "msb", "eximbank", "eximbank edigi",
            "nam a bank", "open banking", "bvbank", "digimi", "abbank", "ab ditizen",
            "pvcombank", "kienlongbank", "vietbank", "vietabank", "bac a bank",
            "pg bank", "ncb izimobile", "shinhan sol", "woori won", "hsbc vietnam",
            "uob TMRW", "timo", "tnex", "cake", "cake by vpbank", "liobank", "vikki",
            "momo", "zalopay", "vnpay", "viettel money", "shopeepay",
            "vneid", "etax mobile", "vssid"
        )

        val BANKING_ICON_KEYWORDS = listOf(
            "digibank", "vietcombank", "smartbanking", "bidv", "vietinbank",
            "techcombank", "mbbank", "acb one", "vpbank", "tpbank", "agribank",
            "sacombank", "hdbank", "myvib", "ocb omni", "seabank", "seamobile",
            "lpbank", "lienviet", "shb mobile", "msb mbank", "eximbank",
            "nam a bank", "bvbank", "digimi", "abbank", "pvcombank", "kienlongbank",
            "vietbank", "vietabank", "saigonbank", "baoviet", "shinhan", "woori",
            "hsbc", "viettel money", "vnpay", "zalopay", "vneid", "etax"
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
                    lower.endsWith(".launcher") ||
                    lower.contains("cocktailbarservice") ||
                    lower.contains("appsedge") ||
                    lower.contains("appsearch") ||
                    lower == "com.android.systemui"
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

        fun isBankingOrProtectedPackage(pkg: String, context: Context? = null): Boolean {
            if (isSystemOrSafePackage(pkg)) return false
            if (BANKING_AND_GOV_PACKAGES.contains(pkg)) return true
            val cached = dynamicPackageCache[pkg]
            if (cached != null) return cached

            val lower = pkg.lowercase()
            if (BANKING_PACKAGE_KEYWORDS.any { lower.contains(it) }) {
                dynamicPackageCache[pkg] = true
                return true
            }

            if (context != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    val appInfo = context.packageManager.getApplicationInfo(pkg, 0)
                    if (appInfo.category == ApplicationInfo.CATEGORY_FINANCE) {
                        dynamicPackageCache[pkg] = true
                        return true
                    }
                } catch (_: Exception) {
                }
            }
            dynamicPackageCache[pkg] = false
            return false
        }

        fun refreshInstalledBankingAppsCache(context: Context) {
            Thread {
                try {
                    val pm = context.packageManager
                    val apps = pm.getInstalledApplications(0)
                    for (app in apps) {
                        val pkg = app.packageName ?: continue
                        if (isBankingOrProtectedPackage(pkg, context)) {
                            val label = pm.getApplicationLabel(app)?.toString()?.trim()?.lowercase()
                            if (!label.isNullOrBlank() && label.length >= 2) {
                                dynamicInstalledBankingLabels.add(label)
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }.start()
        }

        private fun normalizeLauncherIconLabel(raw: String): String {
            var s = raw.trim().lowercase()
            // Cắt bỏ hậu tố đếm thông báo của One UI Home (ví dụ: "VCB Digibank, 2 thông báo" hoặc "MBBank\n1 mục chưa đọc")
            val nlIdx = s.indexOf('\n')
            if (nlIdx > 0) s = s.substring(0, nlIdx).trim()
            val commaIdx = s.indexOf(',')
            if (commaIdx > 0) s = s.substring(0, commaIdx).trim()
            return s
        }

        fun isBankingIconLabelClicked(labelText: String): Boolean {
            val clean = normalizeLauncherIconLabel(labelText)
            if (clean.length !in 2..42) return false
            if (EXACT_BANKING_ICON_LABELS.contains(clean)) return true
            if (dynamicInstalledBankingLabels.contains(clean)) return true
            return BANKING_ICON_KEYWORDS.any { kw -> clean.contains(kw) }
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
            refreshInstalledBankingAppsCache(this)
            registerDirectAudioCallbacks()
            evaluateCallStateInAccessibilityBridge()
        } else {
            mainHandler.removeCallbacks(a11yCallEndRunnable)
            unregisterDirectAudioCallbacks()
            detachAccessibilityAudioAnchor()
            if (dspEngine.isRecording) {
                dspEngine.stopRecording()
            }
            // BẤT BIẾN: Khi người dùng bấm TẮT ỨNG DỤNG, tự động tắt Trợ năng ngay lập tức!
            safelyDisableSelfNow()
        }
    }

    fun evaluateCallStateInAccessibilityBridge() {
        if (!CallMonitorForegroundService.isMasterAppEnabled(this)) {
            safelyDisableSelfNow()
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
        refreshInstalledBankingAppsCache(this)
        CallMonitorForegroundService.dismissBridgeAlertNotification(this)
        if (!CallMonitorForegroundService.isMasterAppEnabled(this)) {
            safelyDisableSelfNow()
            return
        }
        CallMonitorForegroundService.lastDetectedApp = "Cầu nối 2 chiều S22U Call Bridge ĐANG BẬT (Sẵn sàng thu âm)"
        registerDirectAudioCallbacks()
        evaluateCallStateInAccessibilityBridge()
    }

    fun startCallRecordingFromAccessibilityBridge(audioMode: Int) {
        if (!CallMonitorForegroundService.isMasterAppEnabled(this)) {
            safelyDisableSelfNow()
            return
        }
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

    private fun findMatchedBankingLabelFromEvent(event: AccessibilityEvent): String? {
        event.text?.forEach { t ->
            val s = t?.toString()?.trim() ?: ""
            if (s.isNotEmpty() && isBankingIconLabelClicked(s)) {
                return s
            }
        }
        val cd = event.contentDescription?.toString()?.trim() ?: ""
        if (cd.isNotEmpty() && isBankingIconLabelClicked(cd)) {
            return cd
        }
        try {
            val src = event.source
            if (src != null) {
                val srcText = src.text?.toString()?.trim() ?: ""
                if (srcText.isNotEmpty() && isBankingIconLabelClicked(srcText)) {
                    return srcText
                }
                val srcCd = src.contentDescription?.toString()?.trim() ?: ""
                if (srcCd.isNotEmpty() && isBankingIconLabelClicked(srcCd)) {
                    return srcCd
                }
            }
        } catch (_: Exception) {
        }
        return null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TẦNG 0: Nếu ứng dụng đã được người dùng bấm TẮT -> Tự động tắt Trợ năng (disableSelf()) ngay tức khắc!
        if (!CallMonitorForegroundService.isMasterAppEnabled(this)) {
            safelyDisableSelfNow()
            return
        }
        if (event == null) return
        val pkg = event.packageName?.toString() ?: ""

        // TẦNG 1: Kiểm tra gói ứng dụng ở MỌI loại sự kiện AccessibilityEvent (không phân biệt eventType)
        // Ngay vi-giây đầu tiên bất kỳ App Ngân hàng / Tài chính / VNeID nào xuất hiện -> Tắt Trợ năng 0ms!
        if (pkg.isNotEmpty() && isBankingOrProtectedPackage(pkg, this)) {
            engageInstantBankingStealth(pkg)
            return
        }

        // Kiểm tra thêm gói của cửa sổ đang hoạt động trên cùng (rootInActiveWindow)
        try {
            val activePkg = rootInActiveWindow?.packageName?.toString() ?: ""
            if (activePkg.isNotEmpty() && isBankingOrProtectedPackage(activePkg, this)) {
                engageInstantBankingStealth(activePkg)
                return
            }
        } catch (_: Exception) {
        }

        // TẦNG 2: Bắt ngay khoảnh khắc chạm / chọn Icon App Ngân hàng trên Màn hình chính, Edge Panel hoặc Đa nhiệm
        // Lập tức gọi disableSelf() trong 0ms TRƯỚC KHI tiến trình App Ngân hàng kịp chạy Application.onCreate()!
        if ((event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
                    event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED) &&
            !dspEngine.isRecording &&
            isLauncherPackage(pkg)
        ) {
            val matchedLabel = findMatchedBankingLabelFromEvent(event)
            if (matchedLabel != null) {
                engageInstantBankingStealth("Icon $matchedLabel")
                return
            }
        }

        // TẦNG 3: Nhận diện sự kiện cửa sổ của Zalo / Viber / Telegram / WhatsApp / Messenger / SIM
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg.isNotEmpty()) {
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
