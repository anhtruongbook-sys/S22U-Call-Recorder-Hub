package com.s22ultra.callrecorder

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.s22ultra.callrecorder/native"
    private lateinit var fallbackDspEngine: AudioDspRecorderEngine
    private lateinit var syncManager: SamsungCallSyncManager
    private var mediaPlayer: MediaPlayer? = null
    private var currentlyPlayingPath: String? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        fallbackDspEngine = AudioDspRecorderEngine(applicationContext)
        syncManager = SamsungCallSyncManager(applicationContext)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getSystemStatus" -> {
                        val activeEngine = OTTCallAccessibilityService.instance?.dspEngine ?: fallbackDspEngine
                        val diag = syncManager.getDeviceDiagnostics().toMutableMap()
                        diag["accessibilityEnabled"] = isAccessibilityServiceEnabled()
                        diag["micPermissionGranted"] = hasMicPermission()
                        diag["storagePermissionGranted"] = hasStoragePermission()
                        diag["isRecording"] = activeEngine.isRecording
                        diag["currentRecordingPath"] = activeEngine.currentFilePath ?: ""
                        diag["currentAppTag"] = activeEngine.currentAppTag
                        diag["peakAmplitude"] = activeEngine.currentPeakAmplitude
                        diag["autoRecordOttEnabled"] = OTTCallAccessibilityService.autoRecordOttEnabled
                        diag["remoteGainBoost"] = OTTCallAccessibilityService.remoteGainBoost.toDouble()
                        diag["autoBoostVolume"] = OTTCallAccessibilityService.autoBoostVolume
                        diag["lastDetectedApp"] = OTTCallAccessibilityService.lastDetectedApp
                        diag["currentlyPlayingPath"] = currentlyPlayingPath ?: ""
                        result.success(diag)
                    }

                    "requestPermissions" -> {
                        requestRuntimePermissions()
                        result.success(true)
                    }

                    "openAccessibilitySettings" -> {
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(intent)
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("ERR_ACCESSIBILITY", e.message, null)
                        }
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
                        val gain = call.argument<Double>("remoteGainBoost")?.toFloat() ?: 4.5f
                        val boostVol = call.argument<Boolean>("autoBoostVolume") ?: true
                        OTTCallAccessibilityService.autoRecordOttEnabled = autoOtt
                        OTTCallAccessibilityService.remoteGainBoost = gain.coerceIn(1.0f, 10.0f)
                        OTTCallAccessibilityService.autoBoostVolume = boostVol
                        result.success(true)
                    }

                    "startManualRecording" -> {
                        if (!hasMicPermission()) {
                            requestRuntimePermissions()
                            result.error("NO_MIC_PERM", "Chưa cấp quyền Microphone", null)
                            return@setMethodCallHandler
                        }
                        val appTag = call.argument<String>("appTag") ?: "Zalo_Test"
                        val activeEngine = OTTCallAccessibilityService.instance?.dspEngine ?: fallbackDspEngine
                        val path = activeEngine.startRecording(
                            appTag = appTag,
                            remoteGainBoost = OTTCallAccessibilityService.remoteGainBoost,
                            autoBoostEarpieceVolume = OTTCallAccessibilityService.autoBoostVolume
                        )
                        result.success(path ?: "")
                    }

                    "stopRecording" -> {
                        val activeEngine = OTTCallAccessibilityService.instance?.dspEngine ?: fallbackDspEngine
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

    private fun playAudioFile(path: String): Boolean {
        return try {
            stopAudioPlayback()
            val file = File(path)
            if (!file.exists()) return false
            mediaPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener {
                    currentlyPlayingPath = null
                }
                prepare()
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

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, OTTCallAccessibilityService::class.java)
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServicesSetting.contains(expectedComponentName.flattenToString()) ||
                enabledServicesSetting.contains("${packageName}/.OTTCallAccessibilityService")
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
