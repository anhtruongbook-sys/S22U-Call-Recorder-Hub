package com.s22ultra.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * Dịch vụ Trợ năng (AccessibilityService) chuyên biệt cho Samsung Galaxy S22 Ultra (Non-Root).
 * - Nhận diện tự động khi có cuộc gọi VoIP/OTT trên Zalo, Viber, Telegram, WhatsApp
 *   thông qua sự kiện cửa sổ kết hợp giám sát trạng thái AudioManager.MODE_IN_COMMUNICATION.
 * - Khi được bật trong Cài đặt Hỗ trợ tiếp cận, dịch vụ này giữ đặc quyền thu âm đồng thời
 *   cùng lúc với ứng dụng OTT trên Android 10-15.
 */
class OTTCallAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: OTTCallAccessibilityService? = null
            private set

        @Volatile
        var autoRecordOttEnabled: Boolean = true

        @Volatile
        var remoteGainBoost: Float = 4.5f

        @Volatile
        var autoBoostVolume: Boolean = true

        @Volatile
        var lastDetectedApp: String = "Chưa có cuộc gọi"

        val SUPPORTED_OTT_PACKAGES = mapOf(
            "com.zing.zalo" to "Zalo",
            "com.viber.voip" to "Viber",
            "org.telegram.messenger" to "Telegram",
            "org.telegram.messenger.web" to "Telegram",
            "com.whatsapp" to "WhatsApp",
            "com.whatsapp.w4b" to "WhatsApp Business"
        )
    }

    lateinit var dspEngine: AudioDspRecorderEngine
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var currentForegroundOttApp: String? = null
    private var wasInVoipCall: Boolean = false

    private val audioStatePoller = object : Runnable {
        override fun run() {
            checkAudioCommunicationState()
            handler.postDelayed(this, 1200L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        dspEngine = AudioDspRecorderEngine(applicationContext)
        handler.post(audioStatePoller)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkgName = event.packageName?.toString() ?: return

        if (SUPPORTED_OTT_PACKAGES.containsKey(pkgName)) {
            currentForegroundOttApp = SUPPORTED_OTT_PACKAGES[pkgName]
            lastDetectedApp = currentForegroundOttApp ?: pkgName
            checkAudioCommunicationState()
        }
    }

    private fun checkAudioCommunicationState() {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val mode = am.mode

        // MODE_IN_COMMUNICATION (3) là trạng thái chuẩn khi Zalo, Viber, Telegram, WhatsApp đang đàm thoại VoIP
        val isVoipActive = (mode == AudioManager.MODE_IN_COMMUNICATION)

        if (isVoipActive && !wasInVoipCall) {
            wasInVoipCall = true
            val tag = currentForegroundOttApp ?: "OTT_VoIP"
            lastDetectedApp = "$tag (Đang gọi)"
            if (autoRecordOttEnabled && !dspEngine.isRecording) {
                dspEngine.startRecording(
                    appTag = tag,
                    remoteGainBoost = remoteGainBoost,
                    autoBoostEarpieceVolume = autoBoostVolume
                )
            }
        } else if (!isVoipActive && wasInVoipCall) {
            wasInVoipCall = false
            if (dspEngine.isRecording && dspEngine.currentAppTag != "Manual") {
                dspEngine.stopRecording()
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
