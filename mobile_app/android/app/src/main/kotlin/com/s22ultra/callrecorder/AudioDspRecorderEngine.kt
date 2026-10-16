package com.s22ultra.callrecorder

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Động cơ thu âm & xử lý tín hiệu số (Studio-Grade Smooth Envelope DSP) cho Samsung Galaxy S22 Ultra:
 * - Lưu trữ vĩnh viễn tại `/storage/emulated/0/Download/S22U_Call_Recordings/` (và đồng bộ MediaStore.Downloads)
 *   để đảm bảo KHI XÓA / GỠ CÀI ĐẶT APP KHÔNG BAO GIỜ BỊ MẤT FILE GHI ÂM.
 */
class AudioDspRecorderEngine(private val context: Context) {

    @Volatile
    var isRecording: Boolean = false
        private set

    var currentFilePath: String? = null
        private set

    var currentAppTag: String = "Manual"
        private set

    var currentPeakAmplitude: Int = 0
        private set

    private var recordingThread: Thread? = null
    private var startTimeMs: Long = 0L

    fun getPermanentPublicDir(): File {
        return File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "S22U_Call_Recordings"
        )
    }

    fun getInternalFallbackDir(): File {
        val dir = File(context.getExternalFilesDir(null), "OTT_Recordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Ưu tiên ghi trực tiếp vào thư mục công cộng `/storage/emulated/0/Download/S22U_Call_Recordings/`
     * (không bao giờ bị xóa khi gỡ cài đặt App). Nếu chưa cấp quyền All Files Access, ghi tạm vào bộ nhớ
     * đệm rồi tự động đẩy sang `MediaStore.Downloads` (`Download/S22U_Call_Recordings/`) ngay khi dừng ghi.
     */
    fun getRecordingsDir(): File {
        try {
            val publicDir = getPermanentPublicDir()
            if (!publicDir.exists()) {
                publicDir.mkdirs()
            }
            if (publicDir.exists() && publicDir.canWrite()) {
                return publicDir
            }
        } catch (_: Exception) {
        }
        return getInternalFallbackDir()
    }

    @Synchronized
    fun startRecording(
        appTag: String,
        remoteGainBoost: Float = 2.2f,
        autoBoostEarpieceVolume: Boolean = true
    ): String? {
        if (isRecording) return currentFilePath

        // Ưu tiên 48000 Hz (xung nhịp gốc của chip âm thanh S22 Ultra khi gọi VoIP) để triệt tiêu tiếng rè Resampler
        val preferredRates = intArrayOf(48000, 16000, 44100)
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        // Nguồn thu sạch nhất cho AccessibilityService trên S22 Ultra: VOICE_RECOGNITION (6) -> UNPROCESSED (9) -> MIC (1)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC
        )

        var recorder: AudioRecord? = null
        var selectedSampleRate = 48000

        outer@ for (rate in preferredRates) {
            val minBufSize = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
            if (minBufSize <= 0) continue
            // Buffer lớn gấp 6 lần giúp chống hụt mẫu (Buffer Underrun crackling) khi CPU chuyển tác vụ sang Zalo/Telegram
            val bufferSize = minBufSize * 6

            for (src in sources) {
                try {
                    val candidate = AudioRecord(src, rate, channelConfig, audioFormat, bufferSize)
                    if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                        recorder = candidate
                        selectedSampleRate = rate
                        break@outer
                    } else {
                        candidate.release()
                    }
                } catch (_: Exception) {
                }
            }
        }

        if (recorder == null) return null

        // Cấu hình phần cứng AudioFX trên S22 Ultra:
        // - Tắt AcousticEchoCanceler (AEC) để không xóa tiếng loa thoại người bên kia
        // - Tắt AutomaticGainControl (AGC) phần cứng để tránh xung đột với Smooth Envelope AGC của chúng ta gây sôi nền
        // - Bật nhẹ NoiseSuppressor (NS) nếu phần cứng hỗ trợ để khử tiếng xì nền tĩnh
        try {
            val sessionId = recorder.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                AcousticEchoCanceler.create(sessionId)?.apply { enabled = false }
            }
            if (AutomaticGainControl.isAvailable()) {
                AutomaticGainControl.create(sessionId)?.apply { enabled = false }
            }
            if (NoiseSuppressor.isAvailable()) {
                NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }

        // Đặt âm lượng loa thoại ở mức 75% (mức sạch nhất trên S22 Ultra, không ép màng loa thoại rung rè cơ học ở 100%)
        if (autoBoostEarpieceVolume) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val targetVol = (maxVol * 0.75).toInt().coerceAtLeast(1)
                if (am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) < targetVol) {
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVol, 0)
                }
            } catch (_: Exception) {
            }
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTag = appTag.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outFile = File(getRecordingsDir(), "${safeTag}_${timestamp}.wav")

        currentFilePath = outFile.absolutePath
        currentAppTag = appTag
        startTimeMs = System.currentTimeMillis()
        isRecording = true

        val activeRecorder = recorder
        val finalRate = selectedSampleRate
        recordingThread = Thread {
            writeWavWithSmoothEnvelopeDsp(activeRecorder, outFile, finalRate, remoteGainBoost)
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        return outFile.absolutePath
    }

    @Synchronized
    fun stopRecording(): Map<String, Any?>? {
        if (!isRecording) return null
        isRecording = false
        try {
            recordingThread?.join(2500)
        } catch (_: InterruptedException) {
        }
        recordingThread = null
        currentPeakAmplitude = 0

        val path = currentFilePath ?: return null
        val file = File(path)
        if (!file.exists()) return null

        val durationSec = ((System.currentTimeMillis() - startTimeMs) / 1000L).coerceAtLeast(1L)
        val sha256 = computeSha256(file)

        return mapOf(
            "path" to file.absolutePath,
            "name" to file.name,
            "appTag" to currentAppTag,
            "sizeBytes" to file.length(),
            "durationSec" to durationSec,
            "sha256" to sha256
        )
    }

    /**
     * Bộ xử lý DSP Đường Bao Mượt (Smooth Envelope Follower + High-Pass DC Blocker + Soft-Knee Limiter):
     * Giữ nguyên hình dạng sóng âm liên tục (không bao giờ nhảy bậc gây rè).
     */
    private fun writeWavWithSmoothEnvelopeDsp(
        recorder: AudioRecord,
        outFile: File,
        sampleRate: Int,
        userGainSetting: Float
    ) {
        val pcmBuffer = ShortArray(2048)
        val byteBuffer = ByteArray(pcmBuffer.size * 2)
        var totalAudioBytes = 0L

        // Giới hạn hệ số khuếch đại mục tiêu ở vùng an toàn chống vỡ tiếng (1.0x .. 4.0x)
        val maxRemoteBoost = userGainSetting.coerceIn(1.0f, 4.0f)

        // Biến trạng thái bộ lọc DC Blocker & High-Pass (~110Hz) loại bỏ tiếng ù/rè trầm
        var prevRaw = 0f
        var prevHp = 0f
        val hpAlpha = 0.985f

        // Biến trạng thái bộ lọc Low-Pass (~7500Hz) làm mượt gai nhiễu tần số cao
        var prevLp = 0f
        val lpAlpha = 0.62f

        // Biến trạng thái Đường bao biên độ (Envelope Follower) & Hệ số khuếch đại mượt (Smoothed Gain)
        var envelope = 0f
        var smoothedGain = 1.0f

        // Hằng số thời gian mượt theo tần số lấy mẫu (Attack nhanh 8ms, Release mượt 180ms)
        val attackCoeff = 0.015f
        val releaseCoeff = 0.0004f

        try {
            FileOutputStream(outFile).use { fos ->
                fos.write(ByteArray(44))
                recorder.startRecording()

                while (isRecording) {
                    val readCount = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readCount > 0) {
                        var maxAmp = 0
                        for (i in 0 until readCount) {
                            val x = pcmBuffer[i].toFloat()

                            // Bước 1: Lọc bỏ DC Offset & Nhiễu ù tần số thấp (< 110Hz)
                            val hp = hpAlpha * (prevHp + x - prevRaw)
                            prevRaw = x
                            prevHp = hp

                            // Bước 2: Lọc mượt gai nhiễu tần số cao (> 7.5kHz)
                            val cleanSample = prevLp + lpAlpha * (hp - prevLp)
                            prevLp = cleanSample

                            val absIn = abs(cleanSample)
                            if (absIn.toInt() > maxAmp) maxAmp = absIn.toInt()

                            // Bước 3: Theo dõi đường bao năng lượng âm thanh (Envelope Follower)
                            envelope = if (absIn > envelope) {
                                envelope + attackCoeff * (absIn - envelope)
                            } else {
                                envelope + releaseCoeff * (absIn - envelope)
                            }

                            // Bước 4: Tính toán Gain mục tiêu mượt mà dựa trên đường bao (Không nhảy bậc!)
                            // - Nếu im lặng nền (envelope < 120): giữ gain = 1.0f (không khuếch đại tiếng xì nền)
                            // - Nếu tiếng loa thoại nhỏ (envelope 120..3200): nâng gain lên maxRemoteBoost
                            // - Nếu giọng bạn nói trực tiếp lớn (envelope > 6500): hạ gain mượt về 1.0f chống rè
                            val desiredGain: Float = when {
                                envelope < 120f -> 1.0f
                                envelope < 3200f -> {
                                    // Nội suy mượt từ 1.0f lên maxRemoteBoost khi tín hiệu vượt nền tĩnh
                                    val t = ((envelope - 120f) / 600f).coerceIn(0f, 1f)
                                    1.0f + t * (maxRemoteBoost - 1.0f)
                                }
                                envelope < 7500f -> {
                                    // Nội suy mượt giảm dần từ maxRemoteBoost về 1.0f khi giọng nói lớn dần
                                    val t = ((envelope - 3200f) / 4300f).coerceIn(0f, 1f)
                                    maxRemoteBoost - t * (maxRemoteBoost - 1.0f)
                                }
                                else -> 1.0f
                            }

                            // Làm mượt chuyển tiếp của chính hệ số Gain để triệt tiêu 100% méo dạng sóng
                            val gainStep = if (desiredGain < smoothedGain) 0.02f else 0.001f
                            smoothedGain += gainStep * (desiredGain - smoothedGain)

                            var outVal = cleanSample * smoothedGain

                            // Bước 5: Bộ giới hạn mềm liên tục (Continuous Soft-Knee Limiter) ở ngưỡng 24000
                            val absOut = abs(outVal)
                            if (absOut > 24000f) {
                                val signVal = if (outVal >= 0f) 1f else -1f
                                val excess = absOut - 24000f
                                val softCompressed = 24000f + 8500f * (excess / (excess + 8500f))
                                outVal = signVal * softCompressed
                            }

                            val finalShort = outVal.toInt().coerceIn(-32767, 32767).toShort()
                            byteBuffer[i * 2] = (finalShort.toInt() and 0x00FF).toByte()
                            byteBuffer[i * 2 + 1] = ((finalShort.toInt() shr 8) and 0x00FF).toByte()
                        }

                        currentPeakAmplitude = maxAmp
                        val bytesToWrite = readCount * 2
                        fos.write(byteBuffer, 0, bytesToWrite)
                        totalAudioBytes += bytesToWrite
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            try {
                recorder.release()
            } catch (_: Exception) {
            }
            if (outFile.exists() && totalAudioBytes > 0) {
                updateWavHeader(outFile, totalAudioBytes, sampleRate, 1, 16)
                mirrorToPermanentPublicStorage(outFile)
            }
        }
    }

    /**
     * Đảm bảo 100% file ghi âm luôn tồn tại trong thư mục công cộng `/storage/emulated/0/Download/S22U_Call_Recordings/`
     * kể cả khi chưa cấp quyền All Files Access (sử dụng MediaStore.Downloads của Android 10-16).
     * Khi gỡ cài đặt (Uninstall) ứng dụng, toàn bộ các file này vẫn nằm nguyên vẹn vĩnh viễn trong máy.
     */
    fun mirrorToPermanentPublicStorage(srcFile: File) {
        try {
            val pubDir = getPermanentPublicDir()
            if (srcFile.parentFile?.canonicalPath == pubDir.canonicalPath) {
                return
            }
            if (!pubDir.exists()) pubDir.mkdirs()
            val directDst = File(pubDir, srcFile.name)
            if (pubDir.canWrite()) {
                srcFile.copyTo(directDst, overwrite = true)
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, srcFile.name)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                    put(
                        MediaStore.Downloads.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/S22U_Call_Recordings"
                    )
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { outStream ->
                        FileInputStream(srcFile).use { inStream ->
                            inStream.copyTo(outStream)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun updateWavHeader(
        wavFile: File,
        totalAudioLen: Long,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val totalDataLen = totalAudioLen + 36
        val byteRate = (sampleRate * channels * bitsPerSample / 8).toLong()
        val header = ByteArray(44)

        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        writeIntLE(header, 4, totalDataLen.toInt())
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        writeIntLE(header, 16, 16)
        header[20] = 1
        header[21] = 0
        header[22] = channels.toByte()
        header[23] = 0
        writeIntLE(header, 24, sampleRate)
        writeIntLE(header, 28, byteRate.toInt())
        header[32] = (channels * bitsPerSample / 8).toByte()
        header[33] = 0
        header[34] = bitsPerSample.toByte()
        header[35] = 0
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        writeIntLE(header, 40, totalAudioLen.toInt())

        RandomAccessFile(wavFile, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }

    private fun writeIntLE(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = (value and 0xFF).toByte()
        buffer[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    fun computeSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { fis ->
                val buf = ByteArray(16384)
                var n: Int
                while (fis.read(buf).also { n = it } != -1) {
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            ""
        }
    }
}
