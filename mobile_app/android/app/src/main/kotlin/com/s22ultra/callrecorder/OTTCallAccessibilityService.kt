package com.s22ultra.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat

/**
 * Cầu nối Âm thanh Kernel Android 16 (`OTTCallAccessibilityService`):
 * - Trên Android 16 (Samsung S22 Ultra Non-Root), khi Zalo / Viber / Telegram / WhatsApp / Messenger
 *   đang gọi (`VOICE_COMMUNICATION`), `AudioPolicyService` của nhân Android sẽ làm câm (`0x0000` - không có tiếng)
 *   mọi luồng `AudioRecord` của ứng dụng thường, NGOẠI TRỪ tiến trình có `AccessibilityService` đang hoạt động (`isA11yUid = true`).
 * - Dịch vụ này chạy ở chế độ Zero-Polling (0% Pin khi chờ) và `canRetrieveWindowContent="false"` (không đọc nội dung màn hình).
 */
class OTTCallAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: OTTCallAccessibilityService? = null
            private set

        @Volatile
        var isServiceConnectedFlag: Boolean = false
            private set

        @Volatile
        var currentForegroundOttApp: String? = null

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

        val BANKING_PACKAGES = setOf(
            "com.VCB",
            "com.vietinbank.ipay",
            "com.vnpay.bidv",
            "com.vnpay.Agribank3g",
            "com.mbmobile",
            "vn.com.techcombank.bb.app",
            "mobile.acb.com.vn",
            "com.vnpay.vpbankonline",
            "com.tpb.mb.gprsandroid",
            "com.sacombank.ewallet",
            "com.shb.mbanking",
            "com.vnpay.hdbank",
            "com.ocb.omniextra",
            "com.msb.mbanking",
            "vn.com.seabank.mb1",
            "com.vib.myvib2",
            "com.lpbank.lienviet24h",
            "com.namabank.openbanking",
            "com.vnpay.eximbank",
            "vn.shinhan.global.vn.bank",
            "com.vn.mps.MoMo",
            "vn.com.vng.zalopay",
            "com.bplus.vtpay",
            "vn.gov.Bca.VNeID"
        )

        fun hasWriteSecureSettings(context: Context): Boolean {
            return ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.WRITE_SECURE_SETTINGS
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    lateinit var dspEngine: AudioDspRecorderEngine
        private set

    private val mainHandler = Handler(Looper.getMainLooper())
    private var modeListener: AudioManager.OnModeChangedListener? = null
    private val recCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
            super.onRecordingConfigChanged(configs)
            mainHandler.post {
                CallMonitorForegroundService.instance?.evaluateAudioHardwareInterrupt()
                    ?: evaluateDirectCallState()
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        isServiceConnectedFlag = true
        dspEngine = AudioDspRecorderEngine(applicationContext)
        CallMonitorForegroundService.disableMode = "NONE"
        CallMonitorForegroundService.lastDetectedApp =
            "Cầu nối Âm thanh Android 16 ĐANG BẬT (Thu rõ 100% tiếng Zalo/OTT)"

        registerAudioCallbacks()
        ensureForegroundService()
    }

    private fun registerAudioCallbacks() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && modeListener == null) {
                val l = AudioManager.OnModeChangedListener { _ ->
                    mainHandler.post {
                        CallMonitorForegroundService.instance?.evaluateAudioHardwareInterrupt()
                            ?: evaluateDirectCallState()
                    }
                }
                am.addOnModeChangedListener(mainExecutor, l)
                modeListener = l
            }
            am.registerAudioRecordingCallback(recCallback, mainHandler)
        } catch (_: Exception) {
        }
    }

    private fun unregisterAudioCallbacks() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                modeListener?.let { am?.removeOnModeChangedListener(it) }
            }
            am?.unregisterAudioRecordingCallback(recCallback)
        } catch (_: Exception) {
        }
        modeListener = null
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (SUPPORTED_OTT_PACKAGES.containsKey(pkg)) {
                currentForegroundOttApp = SUPPORTED_OTT_PACKAGES[pkg]
            }
            // Chỉ tự động tạm ẩn khi vào App Ngân hàng NẾU máy đã có quyền tự bật lại (WRITE_SECURE_SETTINGS)
            // để người dùng KHÔNG BAO GIỜ bị rơi vào cảnh tự tắt rồi không tự bật lại được!
            if (BANKING_PACKAGES.contains(pkg) && hasWriteSecureSettings(this)) {
                CallMonitorForegroundService.disableMode = "BANKING_TEMP"
                CallMonitorForegroundService.lastDetectedApp = "Tạm ẩn cho $pkg (Sẽ tự bật lại ngay khi thoát)"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    disableSelf()
                }
            }
        }
    }

    private fun evaluateDirectCallState() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = am.mode
        val isCall = (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL)
        if (isCall && CallMonitorForegroundService.autoRecordOttEnabled && !dspEngine.isRecording) {
            val tag = OTTCallNotificationListener.currentActiveOttTag
                ?: currentForegroundOttApp
                ?: if (mode == AudioManager.MODE_IN_CALL) "SIM_Call" else "Zalo_OTT"
            dspEngine.startRecording(
                appTag = tag,
                remoteGainBoost = CallMonitorForegroundService.remoteGainBoost,
                autoBoostEarpieceVolume = CallMonitorForegroundService.autoBoostVolume
            )
        } else if (!isCall && dspEngine.isRecording && dspEngine.currentAppTag != "Manual") {
            dspEngine.stopRecording()
        }
    }

    private fun ensureForegroundService() {
        if (!CallMonitorForegroundService.isGuardRunning) {
            try {
                val svcIntent = Intent(this, CallMonitorForegroundService::class.java).apply {
                    action = CallMonitorForegroundService.ACTION_START_GUARD
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svcIntent)
                } else {
                    startService(svcIntent)
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        unregisterAudioCallbacks()
        if (::dspEngine.isInitialized && dspEngine.isRecording) {
            dspEngine.stopRecording()
        }
        isServiceConnectedFlag = false
        instance = null
        super.onDestroy()
    }
}
