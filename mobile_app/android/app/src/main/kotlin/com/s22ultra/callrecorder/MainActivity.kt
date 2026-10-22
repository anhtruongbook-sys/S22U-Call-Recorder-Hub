package com.s22ultra.callrecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.security.MessageDigest

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.s22ultra.callrecorder/native"
    private val REC_PREFS = "s22u_recorder_prefs"
    private val SEC_PREFS = "s22u_security_prefs"
    private val HASH_SALT = "S22Ultra_Knox_Vault_2026_"

    private lateinit var fallbackDspEngine: AudioDspRecorderEngine
    private lateinit var syncManager: SamsungCallSyncManager
    private var mediaPlayer: MediaPlayer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var currentlyPlayingPath: String? = null
    private var biometricCancellationSignal: CancellationSignal? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        fallbackDspEngine = CallMonitorForegroundService.getSharedEngine(applicationContext)
        syncManager = SamsungCallSyncManager(applicationContext)

        val recPrefs = getSharedPreferences(REC_PREFS, Context.MODE_PRIVATE)
        val masterEnabledOnInit = CallMonitorForegroundService.isMasterAppEnabled(this)
        CallMonitorForegroundService.autoRecordOttEnabled =
            if (masterEnabledOnInit) recPrefs.getBoolean("autoRecordOttEnabled", true) else false
        CallMonitorForegroundService.remoteGainBoost = recPrefs.getFloat("remoteGainBoost", 2.2f)
        CallMonitorForegroundService.autoBoostVolume = recPrefs.getBoolean("autoBoostVolume", false)
        CallMonitorForegroundService.disableMode =
            if (masterEnabledOnInit) (recPrefs.getString("disableMode", "NONE") ?: "NONE") else "MANUAL_OFF"

        if (masterEnabledOnInit &&
            hasMicPermission() &&
            !CallMonitorForegroundService.isGuardRunning
        ) {
            startGuardService(userExplicitEnable = false)
        }

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getSystemStatus" -> {
                        val masterEnabled = CallMonitorForegroundService.isMasterAppEnabled(this)
                        val hasWriteSec = CallMonitorForegroundService.hasWriteSecureSettings(this)
                        if (masterEnabled) {
                            if (hasMicPermission() && !CallMonitorForegroundService.isGuardRunning) {
                                startGuardService(userExplicitEnable = false)
                            }
                            if (hasWriteSec && !OTTCallAccessibilityService.isAccessibilityServiceEnabled(this)) {
                                CallMonitorForegroundService.enableEphemeralAccessibilityForCall(this)
                            }
                            CallMonitorForegroundService.instance?.evaluateAudioHardwareInterrupt()
                        }
                        val activeEngine = CallMonitorForegroundService.getSharedEngine(applicationContext)
                        val diag = syncManager.getDeviceDiagnostics().toMutableMap()
                        diag["masterAppEnabled"] = masterEnabled
                        diag["accessibilityEnabled"] = OTTCallAccessibilityService.isAccessibilityServiceEnabled(this)
                        diag["guardServiceRunning"] = CallMonitorForegroundService.isGuardRunning
                        diag["notificationListenerGranted"] = OTTCallNotificationListener.isNotificationListenerEnabled(this)
                        diag["micPermissionGranted"] = hasMicPermission()
                        diag["storagePermissionGranted"] = hasStoragePermission()
                        diag["usageStatsGranted"] = CallMonitorForegroundService.hasUsageStatsPermission(this)
                        diag["overlayPermissionGranted"] = CallMonitorForegroundService.hasOverlayPermission(this)
                        diag["writeSecureSettingsGranted"] = hasWriteSec
                        diag["isRecording"] = activeEngine.isRecording
                        diag["currentRecordingPath"] = activeEngine.currentFilePath ?: ""
                        diag["currentAppTag"] = activeEngine.currentAppTag
                        diag["peakAmplitude"] = activeEngine.currentPeakAmplitude
                        diag["autoRecordOttEnabled"] = masterEnabled && CallMonitorForegroundService.autoRecordOttEnabled
                        diag["disableMode"] = if (masterEnabled) CallMonitorForegroundService.disableMode else "MANUAL_OFF"
                        diag["remoteGainBoost"] = CallMonitorForegroundService.remoteGainBoost.toDouble()
                        diag["autoBoostVolume"] = CallMonitorForegroundService.autoBoostVolume
                        diag["lastDetectedApp"] = CallMonitorForegroundService.lastDetectedApp
                        diag["currentlyPlayingPath"] = currentlyPlayingPath ?: ""
                        diag["security"] = getSecurityConfigMap()
                        result.success(diag)
                    }

                    // ==================== QUẢN LÝ BẢO MẬT & KHÓA ỨNG DỤNG (PASSWORD / PIN 6 SỐ / VÂN TAY) ====================
                    "getSecurityConfig" -> {
                        result.success(getSecurityConfigMap())
                    }

                    "setPassword" -> {
                        val pwd = call.argument<String>("password")?.trim() ?: ""
                        if (pwd.isEmpty()) {
                            result.error("INVALID_PWD", "Mật khẩu không được để trống", null)
                            return@setMethodCallHandler
                        }
                        val prefs = getSharedPreferences(SEC_PREFS, Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("passwordHash", sha256Hash(pwd))
                            .putBoolean("lockEnabled", true)
                            .putBoolean("autoLockOnSwitch", true)
                            .apply()
                        result.success(getSecurityConfigMap())
                    }

                    "setPin6" -> {
                        val pin = call.argument<String>("pin")?.trim() ?: ""
                        if (!pin.matches(Regex("^\\d{6}$"))) {
                            result.error("INVALID_PIN", "Mã PIN phải gồm đúng 6 chữ số (0-9)", null)
                            return@setMethodCallHandler
                        }
                        val prefs = getSharedPreferences(SEC_PREFS, Context.MODE_PRIVATE)
                        prefs.edit()
                            .putString("pin6Hash", sha256Hash(pin))
                            .putBoolean("lockEnabled", true)
                            .putBoolean("autoLockOnSwitch", true)
                            .apply()
                        result.success(getSecurityConfigMap())
                    }

                    "setBiometricEnabled" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        val prefs = getSharedPreferences(SEC_PREFS, Context.MODE_PRIVATE)
                        prefs.edit()
                            .putBoolean("biometricEnabled", enabled)
                            .apply()
                        result.success(getSecurityConfigMap())
                    }

                    "setLockEnabled" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: false
                        val prefs = getSharedPreferences(SEC_PREFS, Context.MODE_PRIVATE)
                        prefs.edit()
                            .putBoolean("lockEnabled", enabled)
                            .apply()
                        result.success(getSecurityConfigMap())
                    }

                    "setAutoLockOnSwitch" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        val prefs = getSharedPreferences(SEC_PREFS, Context.MODE_PRIVATE)
                        prefs.edit()
                            .putBoolean("autoLockOnSwitch", enabled)
                            .apply()
                        result.success(getSecurityConfigMap())
                    }

                    "verifyPassword" -> {
                        val input = call.argument<String>("password")?.trim() ?: ""
                        val prefs = getSharedPreferences(SEC_PREFS, Context.MODE_PRIVATE)
                        val savedHash = prefs.getString("passwordHash", "") ?: ""
                        val ok = savedHash.isNotEmpty() && sha256Hash(input) == savedHash
                        result.success(ok)
                    }

                    "verifyPin6" -> {
                        val input = call.argument<String>("pin")?.trim() ?: ""
                        val prefs = getSharedPreferences(SEC_PREFS, Context.MODE_PRIVATE)
                        val savedHash = prefs.getString("pin6Hash", "") ?: ""
                        val ok = savedHash.isNotEmpty() && sha256Hash(input) == savedHash
                        result.success(ok)
                    }

                    "authenticateBiometric" -> {
                        triggerSamsungBiometricPrompt(result)
                    }

                    // ==================== QUẢN LÝ DỊCH VỤ GHI ÂM & DSP ====================
                    "requestPermissions" -> {
                        requestRuntimePermissions()
                        result.success(true)
                    }

                    "openAccessibilitySettings" -> {
                        try {
                            if (CallMonitorForegroundService.isMasterAppEnabled(this)) {
                                startGuardService(userExplicitEnable = false)
                            }
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("ERR_GUARD", e.message, null)
                        }
                    }

                    "openDeveloperSettings" -> {
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                            result.success(true)
                        } catch (_: Exception) {
                            try {
                                val fallback = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(fallback)
                                result.success(true)
                            } catch (e2: Exception) {
                                result.error("ERR_DEV", e2.message, null)
                            }
                        }
                    }

                    "openBreventApp" -> {
                        val pkg = call.argument<String>("package") ?: "me.piebridge.brevent"
                        try {
                            val launch = packageManager.getLaunchIntentForPackage(pkg)
                            if (launch != null) {
                                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(launch)
                            } else {
                                val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(market)
                            }
                            result.success(true)
                        } catch (_: Exception) {
                            val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(web)
                            result.success(true)
                        }
                    }

                    "openNotificationListenerSettings" -> {
                        try {
                            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            startActivity(intent)
                            result.success(true)
                        } catch (e: Exception) {
                            result.error("ERR_NOTIF", e.message, null)
                        }
                    }

                    "disableAccessibilityNow" -> {
                        CallMonitorForegroundService.disableEphemeralAccessibilityAfterCall(this)
                        result.success(true)
                    }

                    "setMasterRecordingEnabled" -> {
                        val enabled = call.argument<Boolean>("enabled") ?: true
                        val openSettingsIfNeeded = call.argument<Boolean>("openSettingsIfNeeded") ?: false
                        val closeApp = call.argument<Boolean>("closeApp") ?: false
                        if (enabled) {
                            if (!hasMicPermission()) {
                                requestRuntimePermissions()
                            }
                            CallMonitorForegroundService.setMasterAppPowerState(this, true)
                            startGuardService(userExplicitEnable = true)
                            val hasWriteSec = CallMonitorForegroundService.hasWriteSecureSettings(this)
                            if (hasWriteSec && !OTTCallAccessibilityService.isAccessibilityServiceEnabled(this)) {
                                CallMonitorForegroundService.enableEphemeralAccessibilityForCall(this, force = true)
                            } else if (openSettingsIfNeeded && !OTTCallAccessibilityService.isAccessibilityServiceEnabled(this)) {
                                try {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    startActivity(intent)
                                } catch (_: Exception) {
                                }
                            }
                            result.success(
                                mapOf(
                                    "enabled" to true,
                                    "masterAppEnabled" to true,
                                    "accessibilityEnabled" to OTTCallAccessibilityService.isAccessibilityServiceEnabled(this),
                                    "writeSecureSettingsGranted" to hasWriteSec
                                )
                            )
                        } else {
                            stopAudioPlayback()
                            CallMonitorForegroundService.setMasterAppPowerState(this, false)
                            result.success(
                                mapOf(
                                    "enabled" to false,
                                    "masterAppEnabled" to false,
                                    "accessibilityEnabled" to false
                                )
                            )
                            if (closeApp) {
                                window.decorView.postDelayed({
                                    try {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                            finishAndRemoveTask()
                                        } else {
                                            finishAffinity()
                                        }
                                    } catch (_: Exception) {
                                        finish()
                                    }
                                }, 180L)
                            }
                        }
                    }

                    "disableAccessibilityManual" -> {
                        CallMonitorForegroundService.setMasterAppPowerState(this, false)
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
                        val gain = call.argument<Double>("remoteGainBoost")?.toFloat() ?: CallMonitorForegroundService.remoteGainBoost
                        val boostVol = call.argument<Boolean>("autoBoostVolume") ?: CallMonitorForegroundService.autoBoostVolume
                        val clampedGain = gain.coerceIn(1.0f, 4.0f)
                        CallMonitorForegroundService.remoteGainBoost = clampedGain
                        CallMonitorForegroundService.autoBoostVolume = boostVol
                        getSharedPreferences(REC_PREFS, Context.MODE_PRIVATE).edit()
                            .putFloat("remoteGainBoost", clampedGain)
                            .putBoolean("autoBoostVolume", boostVol)
                            .commit()

                        val explicitAutoOtt = call.argument<Boolean>("autoRecordOttEnabled")
                        if (explicitAutoOtt != null) {
                            if (explicitAutoOtt) {
                                CallMonitorForegroundService.setMasterAppPowerState(this, true)
                                startGuardService(userExplicitEnable = true)
                                if (CallMonitorForegroundService.hasWriteSecureSettings(this) &&
                                    !OTTCallAccessibilityService.isAccessibilityServiceEnabled(this)
                                ) {
                                    CallMonitorForegroundService.enableEphemeralAccessibilityForCall(this, force = true)
                                }
                            } else {
                                CallMonitorForegroundService.setMasterAppPowerState(this, false)
                            }
                        }
                        result.success(true)
                    }

                    "startManualRecording" -> {
                        if (!CallMonitorForegroundService.isMasterAppEnabled(this)) {
                            result.error("APP_DISABLED", "Ứng dụng đang ở trạng thái TẮT. Vui lòng bấm BẬT ứng dụng trước.", null)
                            return@setMethodCallHandler
                        }
                        if (!hasMicPermission()) {
                            requestRuntimePermissions()
                            result.error("NO_MIC_PERM", "Chưa cấp quyền Microphone", null)
                            return@setMethodCallHandler
                        }
                        if (!CallMonitorForegroundService.isGuardRunning) {
                            startGuardService(userExplicitEnable = false)
                        }
                        CallMonitorForegroundService.enableEphemeralAccessibilityForCall(this)
                        val appTag = call.argument<String>("appTag") ?: "Zalo_Test"
                        val activeEngine = CallMonitorForegroundService.getSharedEngine(applicationContext)
                        val path = activeEngine.startRecording(
                            appTag = appTag,
                            remoteGainBoost = CallMonitorForegroundService.remoteGainBoost,
                            autoBoostEarpieceVolume = CallMonitorForegroundService.autoBoostVolume
                        )
                        result.success(path ?: "")
                    }

                    "stopRecording" -> {
                        val activeEngine = CallMonitorForegroundService.getSharedEngine(applicationContext)
                        val info = activeEngine.stopRecording()
                        result.success(info)
                    }

                    "listRecordings" -> {
                        val activeEngine = CallMonitorForegroundService.getSharedEngine(applicationContext)
                        Thread {
                            val list = syncManager.getAllUnifiedRecordings(activeEngine)
                            runOnUiThread {
                                result.success(list)
                            }
                        }.start()
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

    private fun getSecurityConfigMap(): Map<String, Any> {
        val prefs = getSharedPreferences(SEC_PREFS, Context.MODE_PRIVATE)
        val hasPassword = !(prefs.getString("passwordHash", "").isNullOrEmpty())
        val hasPin6 = !(prefs.getString("pin6Hash", "").isNullOrEmpty())
        val lockEnabled = prefs.getBoolean("lockEnabled", false) && (hasPassword || hasPin6)
        val bioAvailable = isBiometricAvailable()
        val bioEnabled = prefs.getBoolean("biometricEnabled", false) && bioAvailable
        val autoLockOnSwitch = prefs.getBoolean("autoLockOnSwitch", true)

        // Khi bật Sinh trắc học vân tay -> Vân tay tự động làm mặc định!
        val defaultMethod = when {
            bioEnabled -> "BIOMETRIC"
            hasPin6 -> "PIN6"
            hasPassword -> "PASSWORD"
            else -> "NONE"
        }

        return mapOf(
            "lockEnabled" to lockEnabled,
            "hasPassword" to hasPassword,
            "hasPin6" to hasPin6,
            "biometricAvailable" to bioAvailable,
            "biometricEnabled" to bioEnabled,
            "autoLockOnSwitch" to autoLockOnSwitch,
            "defaultMethod" to defaultMethod
        )
    }

    private fun isBiometricAvailable(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val bm = getSystemService(BiometricManager::class.java)
                bm?.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
            } else {
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun triggerSamsungBiometricPrompt(result: MethodChannel.Result) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            result.success(false)
            return
        }
        try {
            biometricCancellationSignal?.cancel()
            val signal = CancellationSignal()
            biometricCancellationSignal = signal

            var responded = false
            val prompt = BiometricPrompt.Builder(this)
                .setTitle("Mở khóa S22U Call Recorder")
                .setSubtitle("Xác thực cảm biến vân tay siêu âm S22 Ultra")
                .setDescription("Chạm vân tay hoặc chọn nhập Mã PIN 6 số / Mật khẩu")
                .setNegativeButton("Nhập Mã PIN / Mật khẩu", mainExecutor) { _, _ ->
                    if (!responded) {
                        responded = true
                        result.success(false)
                    }
                }
                .build()

            prompt.authenticate(signal, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(authResult: BiometricPrompt.AuthenticationResult?) {
                    super.onAuthenticationSucceeded(authResult)
                    if (!responded) {
                        responded = true
                        result.success(true)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    super.onAuthenticationError(errorCode, errString)
                    if (!responded) {
                        responded = true
                        result.success(false)
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // Giữ nguyên hộp thoại vân tay để người dùng chạm lại
                }
            })
        } catch (_: Exception) {
            result.success(false)
        }
    }

    private fun sha256Hash(raw: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest((HASH_SALT + raw).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun startGuardService(userExplicitEnable: Boolean = false) {
        if (!userExplicitEnable && !CallMonitorForegroundService.isMasterAppEnabled(this)) {
            return
        }
        try {
            val svcIntent = Intent(this, CallMonitorForegroundService::class.java).apply {
                action = if (userExplicitEnable) {
                    CallMonitorForegroundService.ACTION_USER_ENABLE_APP
                } else {
                    CallMonitorForegroundService.ACTION_START_GUARD
                }
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
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val mime = if (file.name.lowercase().endsWith(".m4a")) "audio/mp4" else "audio/wav"
            val sha256 = fallbackDspEngine.computeSha256(file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
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
                        setTargetGain(400) // +4.0 dB tăng độ rõ giọng nói trên loa ngoài S22 Ultra
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
        biometricCancellationSignal?.cancel()
        stopAudioPlayback()
        super.onDestroy()
    }
}
