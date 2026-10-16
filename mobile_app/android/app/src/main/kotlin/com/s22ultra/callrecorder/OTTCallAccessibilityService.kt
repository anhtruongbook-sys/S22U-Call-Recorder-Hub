package com.s22ultra.callrecorder

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class OTTCallAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: OTTCallAccessibilityService? = null
            private set

        @Volatile
        var autoRecordOttEnabled: Boolean = true

        @Volatile
        var remoteGainBoost: Float = 2.2f

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
            "com.whatsapp.w4b" to "WhatsApp Business",
            "com.samsung.android.incallui" to "SIM_Call",
            "com.samsung.android.dialer" to "SIM_Call"
        )
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

        // MODE_IN_COMMUNICATION (3): Zalo, Viber, Telegram, WhatsApp
        // MODE_IN_CALL (2): Cuộc gọi SIM thông thường (Hỗ trợ cả máy Mỹ SM-S908U bị ẩn ghi âm gốc)
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
