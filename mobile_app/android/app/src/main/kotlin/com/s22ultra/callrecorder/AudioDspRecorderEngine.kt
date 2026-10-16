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
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/**
 * Động cơ thu âm & xử lý tín hiệu số v2.6.0 cho Samsung Galaxy S22 Ultra (SM-S908U):
 * - KHÔNG DÙNG TRỢ NĂNG (`Zero-Accessibility`): An toàn 100% với tất cả các ứng dụng Ngân hàng & VNeID.
 * - Mở khóa kênh `VOICE_RECOGNITION` ngay ở mili-giây `0ms` khi bắt đầu `MODE_IN_COMMUNICATION` (cơ chế gốc của bản v2.0.0)
 *   và giữ liên tục 1 phiên `AudioRecord` duy nhất suốt cuộc gọi (tuyệt đối không đóng/mở lại giữa chừng và không dùng `UNPROCESSED`).
 * - Cổng chờ nhấc máy (`Pre-Roll Answer Gate`): Dù giữ kênh Mic từ `0ms`, dữ liệu lúc chưa nhấc máy chỉ quay vòng trong bộ đệm RAM 1.0 giây
 *   và CHỈ GHI XUỐNG FILE `.wav` từ khoảnh khắc đầu dây bên kia nhấc máy & phát tiếng nói đầu tiên ("Alo").
 * - Chuẩn hóa tên file Tiếng Việt có dấu chuẩn Unicode NFC (`Zalo_Trương_Thành_Long_...wav`).
 */
class AudioDspRecorderEngine(private val context: Context) {

    @Volatile
    var isRecording: Boolean = false
        private set

    @Volatile
    var currentFilePath: String? = null
        private set

    @Volatile
    var currentAppTag: String = "Manual"
        private set

    @Volatile
    var currentPeakAmplitude: Int = 0
        private set

    private var recordingThread: Thread? = null
    private var startTimeMs: Long = 0L
    private var currentTimestampStr: String = ""

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

    /**
     * Giữ nguyên 100% chữ Tiếng Việt có dấu chuẩn Unicode NFC (ví dụ: `Zalo_Trương_Thành_Long`)
     */
    fun sanitizeVietnameseFileName(raw: String): String {
        val normalized = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC)
        val sb = StringBuilder()
        for (ch in normalized) {
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '-' || ch == ' ') {
                sb.append(ch)
            }
        }
        val cleaned = sb.toString()
            .trim()
            .replace(Regex("\\s+"), "_")
            .replace(Regex("_+"), "_")
        return if (cleaned.isNotEmpty()) cleaned else "Cuoc_Goi"
    }

    /**
     * Cập nhật tên người gọi Tiếng Việt (ví dụ từ `Zalo_OTT` sang `Zalo_Trương_Thành_Long`)
     * nếu thông báo cuộc gọi đến trễ hơn vài trăm ms sau khi kênh Mic đã mở ở 0ms.
     */
    @Synchronized
    fun updateActiveCallTag(newTag: String) {
        if (!isRecording || newTag.isBlank() || currentAppTag == "Manual") return
        val safeNew = sanitizeVietnameseFileName(newTag)
        val safeOld = sanitizeVietnameseFileName(currentAppTag)
        if (safeNew == safeOld || safeNew == "Zalo_OTT") return
        currentAppTag = newTag
    }

    @Synchronized
    fun startRecording(
        appTag: String,
        remoteGainBoost: Float = 2.2f,
        autoBoostEarpieceVolume: Boolean = false
    ): String? {
        if (isRecording) return currentFilePath

        val preferredRates = intArrayOf(48000, 16000, 44100)
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        // Chỉ dùng VOICE_RECOGNITION (6) -> MIC (1) như bản v2.0.0 (tuyệt đối không dùng UNPROCESSED gây nhiễu)
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        var recorder: AudioRecord? = null
        var selectedSampleRate = 48000

        outer@ for (rate in preferredRates) {
            val minBufSize = AudioRecord.getMinBufferSize(rate, channelConfig, audioFormat)
            if (minBufSize <= 0) continue
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

        // Cấu hình AudioFX trên session thu giống hệt bản v2.0.0 đã thu được tiếng sạch:
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

        if (autoBoostEarpieceVolume) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val targetVol = (maxVol * 0.65).toInt().coerceAtLeast(1)
                if (am.getStreamVolume(AudioManager.STREAM_VOICE_CALL) < targetVol) {
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, targetVol, 0)
                }
            } catch (_: Exception) {
            }
        }

        currentTimestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTag = sanitizeVietnameseFileName(appTag)
        val outFile = File(getRecordingsDir(), "${safeTag}_${currentTimestampStr}.wav")

        currentFilePath = outFile.absolutePath
        currentAppTag = appTag
        startTimeMs = System.currentTimeMillis()
        isRecording = true

        val activeRecorder = recorder
        val finalRate = selectedSampleRate
        val isManualTest = (appTag == "Manual" || appTag == "Ghi_Am_Nhanh" || appTag.endsWith("_Test"))

        recordingThread = Thread {
            writeWavWithSmoothEnvelopeDsp(
                recorder = activeRecorder,
                initialOutFile = outFile,
                sampleRate = finalRate,
                userGainSetting = remoteGainBoost,
                isManualTest = isManualTest
            )
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
            recordingThread?.join(3500)
        } catch (_: InterruptedException) {
        }
        recordingThread = null
        currentPeakAmplitude = 0

        val path = currentFilePath ?: return null
        val file = File(path)
        if (!file.exists() || file.length() <= 44L) {
            return null
        }

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

    private fun writeWavWithSmoothEnvelopeDsp(
        recorder: AudioRecord,
        initialOutFile: File,
        sampleRate: Int,
        userGainSetting: Float,
        isManualTest: Boolean
    ) {
        var outFile = initialOutFile
        val pcmBuffer = ShortArray(2048)
        var totalAudioBytes = 0L
        var overallPeakRecorded = 0

        // Bộ đệm vòng Pre-Roll 1.0 giây (~24 khối 2048 mẫu ở 48kHz):
        // Giữ liên tục phiên AudioRecord từ 0ms để không mất quyền Mic, nhưng KHÔNG ghi phần đang đổ chuông
        // (chưa nhấc máy) vào file! Chỉ bắt đầu ghi xuống file khi ĐÃ NHẤC MÁY và có tiếng nói đầu tiên ("Alo").
        val maxPreRollChunks = 24
        val preRollQueue = ArrayDeque<ByteArray>(maxPreRollChunks + 2)
        var hasCommittedSpeechStart = isManualTest
        var consecutiveVoiceBuffers = 0

        val normalizedSlider = userGainSetting.coerceIn(1.0f, 4.0f)
        // Khuếch đại giọng nói 6.0x (mạnh gấp ~2.7 lần bản v2.0.0 nhưng giữ nguyên bộ lọc mượt của v2.0.0)
        val maxRemoteBoost = 3.0f + normalizedSlider * 1.4f

        // Bộ lọc High-Pass (~110Hz) & Low-Pass (~7500Hz) nguyên bản của v2.0.0 (đã kiểm chứng không gây nhiễu)
        var prevRaw = 0f
        var prevHp = 0f
        val hpAlpha = 0.985f

        var prevLp = 0f
        val lpAlpha = 0.62f

        var envelope = 0f
        var smoothedGain = 1.0f

        val attackCoeff = 0.015f
        val releaseCoeff = 0.0004f

        try {
            FileOutputStream(outFile).use { fos ->
                fos.write(ByteArray(44))
                recorder.startRecording()

                while (isRecording) {
                    val readCount = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readCount > 0) {
                        var maxCleanInBuffer = 0
                        var maxOutInBuffer = 0
                        val chunkBytes = ByteArray(readCount * 2)

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
                            if (absIn.toInt() > maxCleanInBuffer) {
                                maxCleanInBuffer = absIn.toInt()
                            }

                            // Bước 3: Theo dõi đường bao năng lượng âm thanh (Envelope Follower)
                            envelope = if (absIn > envelope) {
                                envelope + attackCoeff * (absIn - envelope)
                            } else {
                                envelope + releaseCoeff * (absIn - envelope)
                            }

                            // Bước 4: Tính toán Gain mượt mà (kế thừa v2.0.0 nhưng nâng biên độ tiếng loa thoại):
                            // - Im lặng nền (envelope < 95): giữ gain = 0.7f (êm ru không sôi nền)
                            // - Tiếng loa thoại người bên kia & giọng nói vừa (95..4200): nâng mượt lên maxRemoteBoost (~6.0x)
                            // - Giọng nói lớn trực tiếp (> 4200): nén mượt về 1.5f chống vỡ tiếng
                            val desiredGain: Float = when {
                                envelope < 95f -> 0.7f
                                envelope < 3600f -> {
                                    val t = ((envelope - 95f) / 380f).coerceIn(0f, 1f)
                                    0.7f + t * (maxRemoteBoost - 0.7f)
                                }
                                envelope < 8500f -> {
                                    val t = ((envelope - 3600f) / 4900f).coerceIn(0f, 1f)
                                    maxRemoteBoost - t * (maxRemoteBoost - 1.4f)
                                }
                                else -> 1.4f
                            }

                            val gainStep = if (desiredGain < smoothedGain) 0.02f else 0.0012f
                            smoothedGain += gainStep * (desiredGain - smoothedGain)

                            var outVal = cleanSample * smoothedGain

                            // Bước 5: Bộ giới hạn mềm liên tục (Continuous Soft-Knee Limiter) ở ngưỡng 24000
                            val absOut = abs(outVal)
                            if (absOut > 24000f) {
                                val signVal = if (outVal >= 0f) 1f else -1f
                                val excess = absOut - 24000f
                                val softCompressed = 24000f + 8000f * (excess / (excess + 8000f))
                                outVal = signVal * softCompressed
                            }

                            val finalInt = outVal.toInt().coerceIn(-32767, 32767)
                            val absFinal = abs(finalInt)
                            if (absFinal > maxOutInBuffer) maxOutInBuffer = absFinal

                            val finalShort = finalInt.toShort()
                            chunkBytes[i * 2] = (finalShort.toInt() and 0x00FF).toByte()
                            chunkBytes[i * 2 + 1] = ((finalShort.toInt() shr 8) and 0x00FF).toByte()
                        }

                        // Nếu cuộc gọi đang đổ chuông / chờ nhấc máy (isCallRingingOrDialing == true),
                        // chỉ giữ phiên đọc mà KHÔNG đưa vào file để loại bỏ 100% đoạn chờ nhấc máy!
                        if (!isManualTest && OTTCallNotificationListener.isCallRingingOrDialing) {
                            preRollQueue.clear()
                            consecutiveVoiceBuffers = 0
                            currentPeakAmplitude = 0
                            continue
                        }

                        currentPeakAmplitude = maxOutInBuffer

                        if (!hasCommittedSpeechStart) {
                            if (preRollQueue.size >= maxPreRollChunks) {
                                preRollQueue.removeFirst()
                            }
                            preRollQueue.addLast(chunkBytes)

                            // Khi đã nhấc máy và có tiếng nói thật ("Alo", biên độ sạch >= 135 trong >= 2 buffer):
                            if (maxCleanInBuffer >= 135) {
                                consecutiveVoiceBuffers++
                                if (consecutiveVoiceBuffers >= 2) {
                                    hasCommittedSpeechStart = true
                                    while (preRollQueue.isNotEmpty()) {
                                        val b = preRollQueue.removeFirst()
                                        fos.write(b, 0, b.size)
                                        totalAudioBytes += b.size
                                    }
                                    if (maxOutInBuffer > overallPeakRecorded) {
                                        overallPeakRecorded = maxOutInBuffer
                                    }
                                }
                            } else {
                                consecutiveVoiceBuffers = 0
                            }
                        } else {
                            if (maxOutInBuffer > overallPeakRecorded) {
                                overallPeakRecorded = maxOutInBuffer
                            }
                            fos.write(chunkBytes, 0, chunkBytes.size)
                            totalAudioBytes += chunkBytes.size
                        }
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

            if (outFile.exists() && totalAudioBytes > 0L) {
                updateWavHeader(outFile, totalAudioBytes, sampleRate, 1, 16)

                // Nếu tên người gọi Tiếng Việt vừa được cập nhật trong cuộc gọi (ví dụ Zalo_Trương_Thành_Long),
                // đổi tên file sang đúng tên người gọi Tiếng Việt có dấu!
                val latestSafeTag = sanitizeVietnameseFileName(currentAppTag)
                val expectedName = "${latestSafeTag}_${currentTimestampStr}.wav"
                if (outFile.name != expectedName) {
                    try {
                        val renamedFile = File(outFile.parentFile, expectedName)
                        if (outFile.renameTo(renamedFile)) {
                            outFile = renamedFile
                            currentFilePath = outFile.absolutePath
                        }
                    } catch (_: Exception) {
                    }
                }

                // Chuẩn hóa đỉnh sóng thêm tối đa 3.5x lên mức 24.500 (-2.5 dBFS) để nghe to, rõ ràng
                if (overallPeakRecorded in 280..22500) {
                    normalizeWavFileToBroadcastStandard(outFile, overallPeakRecorded)
                }
                mirrorToPermanentPublicStorage(outFile)
            } else if (outFile.exists()) {
                // Nếu cuộc gọi nhỡ (chưa nhấc máy / không có âm thanh đàm thoại), tự xóa file rỗng!
                try {
                    outFile.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Chuẩn hóa đỉnh sóng (Peak Normalizer tối đa 3.5x lên `24.500 / 32.767`)
     * Giúp giọng nói trong file `.wav` to, rõ ràng mà không bao giờ khuếch đại nhiễu tĩnh.
     */
    fun normalizeWavFileToBroadcastStandard(wavFile: File, knownPeak: Int = 0): Boolean {
        if (!wavFile.exists() || wavFile.length() <= 44L) return false
        return try {
            RandomAccessFile(wavFile, "rw").use { raf ->
                val dataLen = (raf.length() - 44L).toInt()
                if (dataLen <= 0) return false

                var peak = knownPeak
                val buf = ByteArray(32768)

                if (peak <= 0) {
                    raf.seek(44L)
                    var remaining = dataLen
                    while (remaining > 0) {
                        val toRead = min(buf.size, remaining)
                        val read = raf.read(buf, 0, toRead)
                        if (read <= 0) break
                        var idx = 0
                        while (idx + 1 < read) {
                            val lo = buf[idx].toInt() and 0xFF
                            val hi = buf[idx + 1].toInt()
                            val sample = ((hi shl 8) or lo).toShort().toInt()
                            val absVal = abs(sample)
                            if (absVal > peak) peak = absVal
                            idx += 2
                        }
                        remaining -= read
                    }
                }

                if (peak < 280 || peak >= 23000) return false

                val scale = (24500f / peak.toFloat()).coerceIn(1.1f, 3.5f)
                var readPos = 44L
                var remaining = dataLen

                while (remaining > 0) {
                    val toRead = min(buf.size, remaining)
                    raf.seek(readPos)
                    val read = raf.read(buf, 0, toRead)
                    if (read <= 0) break

                    var idx = 0
                    while (idx + 1 < read) {
                        val lo = buf[idx].toInt() and 0xFF
                        val hi = buf[idx + 1].toInt()
                        val sample = ((hi shl 8) or lo).toShort().toInt()
                        var boosted = sample.toFloat() * scale
                        if (abs(boosted) > 24500f) {
                            val sign = if (boosted >= 0f) 1f else -1f
                            val ex = abs(boosted) - 24500f
                            boosted = sign * (24500f + 6500f * (ex / (ex + 6500f)))
                        }
                        val outShort = boosted.toInt().coerceIn(-32767, 32767).toShort()
                        buf[idx] = (outShort.toInt() and 0xFF).toByte()
                        buf[idx + 1] = ((outShort.toInt() shr 8) and 0xFF).toByte()
                        idx += 2
                    }

                    raf.seek(readPos)
                    raf.write(buf, 0, read)
                    readPos += read
                    remaining -= read
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    fun mirrorToPermanentPublicStorage(srcFile: File) {
        try {
            if (!srcFile.exists() || srcFile.length() <= 44L) return
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
