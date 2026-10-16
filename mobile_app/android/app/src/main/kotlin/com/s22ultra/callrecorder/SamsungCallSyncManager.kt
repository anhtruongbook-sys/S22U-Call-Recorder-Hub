package com.s22ultra.callrecorder

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SamsungCallSyncManager(private val context: Context) {

    fun getSamsungNativeDirectories(): List<File> {
        val extRoot = Environment.getExternalStorageDirectory()
        return listOf(
            File(extRoot, "Recordings/Call"),
            File(extRoot, "Call"),
            File(extRoot, "Music/Recordings/Call")
        )
    }

    fun getOttDirectories(dspEngine: AudioDspRecorderEngine): List<File> {
        val extRoot = Environment.getExternalStorageDirectory()
        return listOf(
            dspEngine.getPermanentPublicDir(),
            File(extRoot, "Recordings/S22U_Call_Recordings"),
            dspEngine.getInternalFallbackDir()
        )
    }

    fun getAllUnifiedRecordings(dspEngine: AudioDspRecorderEngine): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        val seenNames = mutableSetOf<String>()
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        // Tự động sao lưu các file hợp lệ (> 44 bytes) từ thư mục nội bộ sang thư mục công cộng /Download/S22U_Call_Recordings/
        try {
            val internalDir = dspEngine.getInternalFallbackDir()
            if (internalDir.exists()) {
                internalDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.length() > 44L && (f.name.endsWith(".wav", true) || f.name.endsWith(".m4a", true))) {
                        dspEngine.mirrorToPermanentPublicStorage(f)
                    } else if (f.isFile && f.length() <= 44L && !dspEngine.isRecording) {
                        // Tự động dọn file rỗng 0B do ngắt nhầm
                        f.delete()
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 1. Quét toàn bộ thư mục lưu trữ OTT vĩnh viễn (/Download/S22U_Call_Recordings/ & nội bộ)
        for (ottDir in getOttDirectories(dspEngine)) {
            if (ottDir.exists() && ottDir.canRead()) {
                ottDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.length() <= 44L && !dspEngine.isRecording) {
                        try {
                            file.delete()
                        } catch (_: Exception) {
                        }
                        return@forEach
                    }
                    if (file.isFile &&
                        file.length() > 44L &&
                        !seenNames.contains(file.name) &&
                        (file.name.endsWith(".wav", true) || file.name.endsWith(".m4a", true))
                    ) {
                        seenNames.add(file.name)
                        val prefix = file.name.substringBefore("_", "OTT")
                        val appSource = when {
                            prefix.contains("Zalo", true) -> "Zalo"
                            prefix.contains("Viber", true) -> "Viber"
                            prefix.contains("Telegram", true) -> "Telegram"
                            prefix.contains("WhatsApp", true) -> "WhatsApp"
                            prefix.contains("Messenger", true) -> "Messenger"
                            prefix.contains("SIM", true) -> "SIM_Call"
                            else -> prefix
                        }
                        val lenBytes = file.length()
                        results.add(
                            mapOf(
                                "name" to file.name,
                                "path" to file.absolutePath,
                                "appTag" to appSource,
                                "source" to appSource,
                                "sourceType" to "OTT_VOIP",
                                "category" to "OTT_VOIP",
                                "sizeBytes" to lenBytes,
                                "sizeKB" to (lenBytes / 1024L).coerceAtLeast(1L),
                                "lastModifiedMs" to file.lastModified(),
                                "dateFormatted" to dateFormat.format(Date(file.lastModified())),
                                "sha256Short" to dspEngine.computeSha256(file).take(16)
                            )
                        )
                    }
                }
            }
        }

        // 2. Quét thư mục ghi âm cuộc gọi SIM gốc của Samsung S22 Ultra (/Recordings/Call/)
        for (dir in getSamsungNativeDirectories()) {
            if (dir.exists() && dir.canRead()) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile &&
                        file.length() > 0L &&
                        !seenNames.contains(file.name) &&
                        (file.name.endsWith(".m4a", true) ||
                                file.name.endsWith(".amr", true) ||
                                file.name.endsWith(".wav", true) ||
                                file.name.endsWith(".mp3", true))
                    ) {
                        seenNames.add(file.name)
                        val lenBytes = file.length()
                        results.add(
                            mapOf(
                                "name" to file.name,
                                "path" to file.absolutePath,
                                "appTag" to "SIM Samsung",
                                "source" to "SIM (Samsung Gốc)",
                                "sourceType" to "CELLULAR_SIM",
                                "category" to "CELLULAR_SIM",
                                "sizeBytes" to lenBytes,
                                "sizeKB" to (lenBytes / 1024L).coerceAtLeast(1L),
                                "lastModifiedMs" to file.lastModified(),
                                "dateFormatted" to dateFormat.format(Date(file.lastModified())),
                                "sha256Short" to dspEngine.computeSha256(file).take(16)
                            )
                        )
                    }
                }
            }
        }

        return results.sortedByDescending { it["lastModifiedMs"] as Long }
    }

    fun getDeviceDiagnostics(): Map<String, Any> {
        val manufacturer = Build.MANUFACTURER ?: "Unknown"
        val model = Build.MODEL ?: "Unknown"
        val androidRelease = Build.VERSION.RELEASE ?: "Unknown"
        val sdkInt = Build.VERSION.SDK_INT

        val samsungDir = File(Environment.getExternalStorageDirectory(), "Recordings/Call")
        val permanentOttDir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "S22U_Call_Recordings"
        )
        val installedOtt = mutableListOf<String>()
        val pm = context.packageManager

        CallMonitorForegroundService.SUPPORTED_OTT_PACKAGES.forEach { (pkg, label) ->
            try {
                pm.getPackageInfo(pkg, 0)
                if (!installedOtt.contains(label)) {
                    installedOtt.add(label)
                }
            } catch (_: Exception) {
            }
        }

        return mapOf(
            "manufacturer" to manufacturer,
            "model" to model,
            "androidRelease" to androidRelease,
            "sdkInt" to sdkInt,
            "samsungCallFolderExists" to samsungDir.exists(),
            "samsungCallFolderReadable" to samsungDir.canRead(),
            "samsungCallFolderPath" to samsungDir.absolutePath,
            "permanentOttFolderPath" to permanentOttDir.absolutePath,
            "installedOttApps" to installedOtt
        )
    }
}
