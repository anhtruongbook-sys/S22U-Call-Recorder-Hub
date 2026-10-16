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

    fun getAllUnifiedRecordings(dspEngine: AudioDspRecorderEngine): List<Map<String, Any>> {
        val results = mutableListOf<Map<String, Any>>()
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())

        // 1. Quét thư mục OTT_Recordings của ứng dụng (Zalo, Viber, Telegram, WhatsApp, Manual)
        val ottDir = dspEngine.getRecordingsDir()
        if (ottDir.exists()) {
            ottDir.listFiles()?.forEach { file ->
                if (file.isFile && (file.name.endsWith(".wav", true) || file.name.endsWith(".m4a", true))) {
                    val prefix = file.name.substringBefore("_", "OTT")
                    val appSource = when {
                        prefix.contains("Zalo", true) -> "Zalo"
                        prefix.contains("Viber", true) -> "Viber"
                        prefix.contains("Telegram", true) -> "Telegram"
                        prefix.contains("WhatsApp", true) -> "WhatsApp"
                        else -> prefix
                    }
                    results.add(
                        mapOf(
                            "name" to file.name,
                            "path" to file.absolutePath,
                            "source" to appSource,
                            "category" to "OTT_VOIP",
                            "sizeKB" to (file.length() / 1024L).coerceAtLeast(1L),
                            "lastModifiedMs" to file.lastModified(),
                            "dateFormatted" to dateFormat.format(Date(file.lastModified())),
                            "sha256Short" to dspEngine.computeSha256(file).take(16)
                        )
                    )
                }
            }
        }

        // 2. Quét thư mục ghi âm cuộc gọi SIM gốc của Samsung S22 Ultra (/Recordings/Call/)
        for (dir in getSamsungNativeDirectories()) {
            if (dir.exists() && dir.canRead()) {
                dir.listFiles()?.forEach { file ->
                    if (file.isFile && (file.name.endsWith(".m4a", true) ||
                                file.name.endsWith(".amr", true) ||
                                file.name.endsWith(".wav", true) ||
                                file.name.endsWith(".mp3", true))
                    ) {
                        results.add(
                            mapOf(
                                "name" to file.name,
                                "path" to file.absolutePath,
                                "source" to "SIM (Samsung Gốc)",
                                "category" to "CELLULAR_SIM",
                                "sizeKB" to (file.length() / 1024L).coerceAtLeast(1L),
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
            "installedOttApps" to installedOtt
        )
    }
}
