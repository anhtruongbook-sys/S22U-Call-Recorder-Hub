package com.s22ultra.callrecorder

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.s22ultra.callrecorder/native"
    private lateinit var fallbackDspEngine: AudioDspRecorderEngine
    private lateinit var syncManager: SamsungCallSyncManager
    private var mediaPlayer: MediaPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentlyPlayingPath: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        fallbackDspEngine = AudioDspRecorderEngine(applicationContext)
        syncManager = SamsungCallSyncManager(applicationContext)

        if (hasMicPermission() &&
            !CallMonitorForegroundService.isGuardRunning &&
            CallMonitorForegroundService.disableMode != "MANUAL_OFF"
        ) {
            startGuardService()
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getSystemStatus" -> {
                        if (hasMicPermission() &&
                            !CallMonitorForegroundService.isGuardRunning &&
                            CallMonitorForegroundService.disableMode != "MANUAL_OFF"
                        ) {
                            startGuardService()
                        }
                        val activeEngine = CallMonitorForegroundService.instance?.dspEngine
                            ?: fallbackDspEngine
                        val diag = syncManager.getDeviceDiagnostics().toMutableMap()
                        diag["accessibilityEnabled"] = CallMonitorForegroundService.isGuardRunning
                        diag["notificationListenerGranted"] = OTTCallNotificationListener.isNotificationListenerEnabled(this)
                        diag["micPermissionGranted"] = hasMicPermission()
                        diag["storagePermissionGranted"] = hasStoragePermission()
                        diag["usageStatsGranted"] = CallMonitorForegroundService.hasUsageStatsPermission(this)
                        diag["overlayPermissionGranted"] = CallMonitorForegroundService.hasOverlayPermission(this)
                        diag["writeSecureSettingsGranted"] = true
                        diag["isRecording"] = activeEngine.isRecording
                        diag["currentRecordingPath"] = activeEngine.currentFilePath ?: ""
                        diag["currentAppTag"] = activeEngine.currentAppTag
                        diag["peakAmplitude"] = activeEngine.currentPeakAmplitude
                        diag["autoRecordOttEnabled"] = CallMonitorForegroundService.autoRecordOttEnabled
                        diag["bankingStealthEnabled"] = true
                        diag["autoRestoreEnabled"] = true
                        diag["disableMode"] = CallMonitorForegroundService.disableMode
                        diag["learnedStealthApps"] = emptyList<String>()
                        diag["remoteGainBoost"] = CallMonitorForegroundService.remoteGainBoost.toDouble()
                        diag["autoBoostVolume"] = CallMonitorForegroundService.autoBoostVolume
                        diag["lastDetectedApp"] = CallMonitorForegroundService.lastDetectedApp
                        diag["currentlyPlayingPath"] = currentlyPlayingPath ?: ""
                        result.success(diag)
                    }

                    "requestPermissions" -> {
                        requestRuntimePermissions()
                        result.success(true)
                    }

                    "openAccessibilitySettings" -> {
                        try {
                            CallMonitorForegroundService.disableMode = "NONE"
                            startGuardService()
                            if (!OTTCallNotificationListener.isNotificationListenerEnabled(this)) {
                                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("ERR_GUARD", e.message, null)
                        }
                    }

                    "disableAccessibilityNow" -> {
                        // Với kiến trúc v1.9.0 Không dùng Trợ năng, dịch vụ luôn chạy ngầm 24/7 mà KHÔNG BỊ NGÂN HÀNG CHẶN!
                        CallMonitorForegroundService.disableMode = "NONE"
                        startGuardService()
                        result.success(true)
                    }

                    "disableAccessibilityManual" -> {
                        // Người dùng chủ động tắt trong App -> Giữ nguyên TẮT, KHÔNG tự bật lại
                        CallMonitorForegroundService.disableMode = "MANUAL_OFF"
                        try {
                            val stopIntent = Intent(this, CallMonitorForegroundService::class.java).apply {
                                action = CallMonitorForegroundService.ACTION_STOP_GUARD_MANUAL
                            }
                            startService(stopIntent)
                        } catch (_: Exception) {
                        }
                        result.success(true)
                    }

                    "openUsageAccessSettings" -> {
                        try {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("ERR_USAGE_STATS", e.message, null)
                        }
                    }

                    "openOverlaySettings" -> {
                        try {
                            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")
                                ).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            } else {
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            }
                            startActivity(intent)
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("ERR_OVERLAY", e.message, null)
                        }
                    }

                    "clearLearnedStealthApps" -> {
                        result.success(true)
                    }

                    "openSamsungCallSettings" -> {
                        try {
                            val intent = Intent().apply {
                                component = ComponentName(
                                    "com.samsung.android.app.telephonyui",
                                    "com.samsung.android.app.telephonyui.netsettings.ui.CallSettingsActivity"
                                )
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                            result.success(true)
                        } catch (_: Exception) {
                            try {
                                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                                startActivity(fallback)
                                result.success(true)
                            } catch (e2: Exception) {
                                result.error("ERR_CALL_SETTINGS", e2.message, null)
                            }
                        }
                    }

                    "openAllFilesAccess" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                startActivity(intent)
                                result.success(true)
                            } catch (_: Exception) {
                                val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                startActivity(intent)
                                result.success(true)
                            }
                        } else {
                            requestRuntimePermissions()
                            result.success(true)
                        }
                    }

                    "updateSettings" -> {
                        val autoOtt = call.argument<Boolean>("autoRecordOttEnabled") ?: true
                        val gain = call.argument<Double>("remoteGainBoost")?.toFloat() ?: 2.2f
                        val boostVol = call.argument<Boolean>("autoBoostVolume") ?: true
                        CallMonitorForegroundService.autoRecordOttEnabled = autoOtt
                        CallMonitorForegroundService.remoteGainBoost = gain.coerceIn(1.0f, 4.0f)
                        CallMonitorForegroundService.autoBoostVolume = boostVol
                        result.success(true)
                    }

                    "startManualRecording" -> {
                        if (!hasMicPermission()) {
                            requestRuntimePermissions()
                            result.error("NO_MIC_PERM", "Chưa cấp quyền Microphone", null)
                            return@setMethodCallHandler
                        }
                        if (!CallMonitorForegroundService.isGuardRunning) {
                            startGuardService()
                        }
                        val appTag = call.argument<String>("appTag") ?: "Zalo_Test"
                        val activeEngine = CallMonitorForegroundService.instance?.dspEngine
                            ?: fallbackDspEngine
                        val path = activeEngine.startRecording(
                            appTag = appTag,
                            remoteGainBoost = CallMonitorForegroundService.remoteGainBoost,
                            autoBoostEarpieceVolume = CallMonitorForegroundService.autoBoostVolume
                        )
                        result.success(path ?: "")
                    }

                    "stopRecording" -> {
                        val activeEngine = CallMonitorForegroundService.instance?.dspEngine
                            ?: fallbackDspEngine
                        val info = activeEngine.stopRecording()
                        result.success(info)
                    }

                    "listRecordings" -> {
                        val list = syncManager.getAllUnifiedRecordings(fallbackDspEngine)
                        result.success(list)
                    }

                    "playRecording" -> {
                        val path = call.argument<String>("path") ?: ""
                        val played = playAudioFile(path)
                        result.success(played)
                    }

                    "stopPlayback" -> {
                        stopAudioPlayback()
                        result.success(true)
                    }

                    "shareRecording" -> {
                        val path = call.argument<String>("path") ?: ""
                        val shared = shareAudioFile(path)
                        result.success(shared)
                    }

                    "exportToDownloads" -> {
                        val path = call.argument<String>("path") ?: ""
                        val savedPath = exportFileToDownloads(path)
                        result.success(savedPath)
                    }

                    "deleteRecording" -> {
                        val path = call.argument<String>("path") ?: ""
                        val f = File(path)
                        if (currentlyPlayingPath == path) {
                            stopAudioPlayback()
                        }
                        val deleted = if (f.exists()) f.delete() else false
                        result.success(deleted)
                    }

                    else -> result.notImplemented()
                }
            }
    }

    private fun startGuardService() {
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

    private fun shareAudioFile(path: String): Boolean {
        return try {
            val file = File(path)
            if (!file.exists()) return false
            val uri: Uri = FileProvider.getUriForFile(
                this,
                "${applicationContext.packageName}.fileprovider",
                file
            )
            val mimeType = when {
                file.name.endsWith(".wav", true) -> "audio/wav"
                file.name.endsWith(".m4a", true) -> "audio/mp4"
                file.name.endsWith(".mp3", true) -> "audio/mpeg"
                else -> "audio/*"
            }
            val sha256 = fallbackDspEngine.computeSha256(file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Bản ghi âm cuộc gọi: ${file.name}\nSHA-256: $sha256"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(shareIntent, "Chia sẻ bản ghi âm (${file.name})").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(chooser)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun exportFileToDownloads(path: String): String {
        return try {
            val src = File(path)
            if (!src.exists()) return ""
            val dlDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "S22U_Call_Recordings"
            )
            if (!dlDir.exists()) dlDir.mkdirs()
            val dst = File(dlDir, src.name)
            src.copyTo(dst, overwrite = true)
            dst.absolutePath
        } catch (_: Exception) {
            ""
        }
    }

    private fun playAudioFile(path: String): Boolean {
        return try {
            stopAudioPlayback()
            val file = File(path)
            if (!file.exists()) return false

            // Nếu là file .wav cũ bị thu nhỏ, tự động chuẩn hóa Peak lên 28.500 (-1.2 dBFS) ngay trước khi phát!
            if (file.name.lowercase().endsWith(".wav")) {
                fallbackDspEngine.normalizeWavFileToBroadcastStandard(file)
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                setDataSource(file.absolutePath)
                setVolume(1.0f, 1.0f)
                setOnCompletionListener {
                    currentlyPlayingPath = null
                }
                prepare()
                try {
                    loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                        setTargetGain(900) // Tăng cường +9.0 dB phần cứng khi nghe lại trong App
                        enabled = true
                    }
                } catch (_: Exception) {
                }
                start()
            }
            currentlyPlayingPath = path
            true
        } catch (_: Exception) {
            currentlyPlayingPath = null
            false
        }
    }

    private fun stopAudioPlayback() {
        try {
            loudnessEnhancer?.enabled = false
            loudnessEnhancer?.release()
        } catch (_: Exception) {
        }
        loudnessEnhancer = null
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        currentlyPlayingPath = null
    }

    private fun hasMicPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager() ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                this,
                                Manifest.permission.READ_MEDIA_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED)
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRuntimePermissions() {
        val perms = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1001)
    }

    override fun onDestroy() {
        stopAudioPlayback()
        super.onDestroy()
    }
}
