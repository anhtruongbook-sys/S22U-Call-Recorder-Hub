package com.s22ultra.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Cầu nối Âm thanh Kernel Android 16 (`OTTCallAccessibilityService` v2.5.0):
 * - Trên Android 16 (Samsung S22 Ultra Non-Root), nhân `AudioPolicyService` chỉ cho phép thu âm cuộc gọi
 *   Zalo / Viber / Telegram / WhatsApp / Messenger khi tiến trình có `isA11yUid = true`.
 * - ĐÃ XÓA BỎ 100% LỆNH `disableSelf()` -> Bạn chỉ cần bật 1 lần duy nhất, dịch vụ sẽ giữ nguyên trạng thái BẬT
 *   vĩnh viễn 24/7 (0% Pin khi chờ, không đọc nội dung màn hình `canRetrieveWindowContent="false"`, không bao giờ tự tắt!).
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

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            if (isServiceConnectedFlag && instance != null) return true
            return try {
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                val cn1 = ComponentName(context, OTTCallAccessibilityService::class.java).flattenToString()
                val cn2 = ComponentName(context, OTTCallAccessibilityService::class.java).flattenToShortString()
                enabledServices.contains(cn1, ignoreCase = true) ||
                        enabledServices.contains(cn2, ignoreCase = true) ||
                        enabledServices.contains("OTTCallAccessibilityService", ignoreCase = true)
            } catch (_: Exception) {
                false
            }
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
            "Cầu nối Thu âm Android 16 ĐANG BẬT (Sẵn sàng thu rõ 2 chiều 100%)"

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
            // TUYỆT ĐỐI KHÔNG GỌI disableSelf() để dịch vụ không bao giờ tự tắt!
        }
    }

    private fun evaluateDirectCallState() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = am.mode
        val isCall = (mode == AudioManager.MODE_IN_COMMUNICATION || mode == AudioManager.MODE_IN_CALL)
        if (isCall &&
            !OTTCallNotificationListener.isCallRingingOrDialing &&
            CallMonitorForegroundService.autoRecordOttEnabled &&
            !dspEngine.isRecording
        ) {
            val tag = OTTCallNotificationListener.currentActiveOttTag
                ?: currentForegroundOttApp
                ?: if (mode == AudioManager.MODE_IN_CALL) "SIM_Call" else "Zalo"
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
