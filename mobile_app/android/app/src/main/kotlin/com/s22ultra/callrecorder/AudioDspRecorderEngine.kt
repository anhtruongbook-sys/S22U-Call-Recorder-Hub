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
 * Động cơ thu âm & xử lý tín hiệu số v2.5.0 cho Samsung Galaxy S22 Ultra (SM-S908U):
 * 1. CHUẨN HÓA TÊN FILE TIẾNG VIỆT UNICODE NFC (`sanitizeVietnameseFileName`):
 *    Giữ nguyên 100% chữ Tiếng Việt có dấu (ví dụ: `Zalo_Trương_Thành_Long_20261016_235000.wav`),
 *    tuyệt đối không biến chữ có dấu thành dấu gạch dưới `_`.
 * 2. CỔNG CHỜ NHẤC MÁY & BỘ ĐỆM TRƯỚC GIỌNG NÓI 1.0 GIÂY (`Pre-Roll Speech Gate`):
 *    - Khi cuộc gọi đang đổ chuông / chưa nhấc máy (`isCallRingingOrDialing == true`): Tuyệt đối chưa ghi vào file.
 *    - Chỉ bắt đầu ghi xuống file `.wav` ngay khoảnh khắc đầu dây nhấc máy và phát tiếng nói đầu tiên ("Alo"),
 *      kèm bộ đệm 1.0 giây trước đó để không mất chữ đầu tiên và cắt bỏ 100% đoạn chờ nhấc máy vô nghĩa!
 * 3. LỌC GIỌNG NÓI SẠCH 120Hz–4200Hz & CỔNG CHỐNG NHIỄU (`Clean Voice-Band DSP`):
 *    - Tuyệt đối KHÔNG chuyển sang nguồn `UNPROCESSED` hay khuếch đại nhiễu nền 85x gây tiếng sôi/ồn.
 *    - Khi không có tiếng nói (`envelope < 95`): Hạ gain xuống `0.5x` để nền im lặng hoàn toàn.
 *    - Khi có giọng nói thật (`envelope >= 95`): Khuếch đại mượt `3.8x – 6.2x` trong trẻo, rõ nét.
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

    private fun openCleanVoiceAudioRecord(sampleRate: Int): AudioRecord? {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf <= 0) return null
        val bufferSize = minBuf * 6

        // Chỉ sử dụng 2 nguồn giọng nói chuẩn của Android (VOICE_RECOGNITION = 6 và MIC = 1),
        // TUYỆT ĐỐI KHÔNG dùng UNPROCESSED (9) để không bao giờ thu phải nhiễu tĩnh điện phần cứng!
        val cleanSources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        for (src in cleanSources) {
            try {
                val candidate = AudioRecord(src, sampleRate, channelConfig, audioFormat, bufferSize)
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    try {
                        val sessionId = candidate.audioSessionId
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
                    return candidate
                } else {
                    candidate.release()
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    @Synchronized
    fun startRecording(
        appTag: String,
        remoteGainBoost: Float = 2.2f,
        autoBoostEarpieceVolume: Boolean = false
    ): String? {
        if (isRecording) return currentFilePath

        val preferredRates = intArrayOf(48000, 16000, 44100)
        var recorder: AudioRecord? = null
        var selectedSampleRate = 48000

        for (rate in preferredRates) {
            val candidate = openCleanVoiceAudioRecord(rate)
            if (candidate != null) {
                recorder = candidate
                selectedSampleRate = rate
                break
            }
        }

        if (recorder == null) return null

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

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTag = sanitizeVietnameseFileName(appTag)
        val outFile = File(getRecordingsDir(), "${safeTag}_${timestamp}.wav")

        currentFilePath = outFile.absolutePath
        currentAppTag = appTag
        startTimeMs = System.currentTimeMillis()
        isRecording = true

        val initialRecorder = recorder
        val finalRate = selectedSampleRate
        val isManualTest = (appTag == "Manual" || appTag == "Ghi_Am_Nhanh" || appTag.endsWith("_Test"))

        recordingThread = Thread {
            writeWavWithPreRollAndCleanVoiceDsp(
                initialRecorder = initialRecorder,
                outFile = outFile,
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

    private fun writeWavWithPreRollAndCleanVoiceDsp(
        initialRecorder: AudioRecord,
        outFile: File,
        sampleRate: Int,
        userGainSetting: Float,
        isManualTest: Boolean
    ) {
        var activeRecorder: AudioRecord = initialRecorder
        val pcmBuffer = ShortArray(2048)
        var totalAudioBytes = 0L
        var overallPeakRecorded = 0

        // Bộ đệm Pre-Roll 1.0 giây (~24 khối 2048 mẫu ở 48kHz):
        // Giữ 1.0 giây âm thanh gần nhất trong RAM, chỉ bắt đầu ghi xuống file khi ĐÃ NHẤC MÁY và CÓ GIỌNG NÓI THẬT!
        val maxPreRollChunks = 24
        val preRollQueue = ArrayDeque<ByteArray>(maxPreRollChunks + 2)
        var hasCommittedSpeechStart = isManualTest
        var consecutiveVoiceBuffers = 0
        var consecutiveZeroBuffers = 0
        var rebindCount = 0

        val normalizedSlider = userGainSetting.coerceIn(1.0f, 4.0f)
        // Khuếch đại sạch (Mặc định 2.2 -> 5.1x cho tiếng loa thoại, không thổi phồng tạp âm)
        val maxVoiceBoost = 2.4f + normalizedSlider * 1.25f
        val targetSpeechLevel = 18500f

        // Bộ lọc Thông dải Giọng nói (Band-Pass 120Hz – 4200Hz):
        // - High-Pass (~120Hz) cắt tiếng ù trầm và DC Offset
        // - Low-Pass (~4200Hz) cắt sạch tiếng xì/sôi tần số cao
        var prevRaw = 0f
        var prevHp = 0f
        val hpAlpha = 0.984f

        var prevLp = 0f
        val lpAlpha = 0.46f

        var envelope = 0f
        var smoothedGain = 1.0f

        val attackCoeff = 0.016f
        val releaseCoeff = 0.0005f

        try {
            FileOutputStream(outFile).use { fos ->
                fos.write(ByteArray(44))
                activeRecorder.startRecording()

                while (isRecording) {
                    // Nếu thông báo Zalo/OTT vẫn đang báo "Đang gọi..." / "Đang đổ chuông..." (chưa nhấc máy),
                    // chỉ đọc xả buffer và tuyệt đối chưa ghi vào file!
                    if (!isManualTest && OTTCallNotificationListener.isCallRingingOrDialing) {
                        activeRecorder.read(pcmBuffer, 0, pcmBuffer.size)
                        currentPeakAmplitude = 0
                        continue
                    }

                    val readCount = activeRecorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readCount > 0) {
                        var maxRawInBuffer = 0
                        var maxCleanVoiceInBuffer = 0
                        var maxOutInBuffer = 0
                        val chunkBytes = ByteArray(readCount * 2)

                        for (i in 0 until readCount) {
                            val rawShort = pcmBuffer[i].toInt()
                            val absRaw = abs(rawShort)
                            if (absRaw > maxRawInBuffer) maxRawInBuffer = absRaw

                            val x = rawShort.toFloat()

                            // Bước 1: High-Pass 120Hz khử DC Offset & tiếng ù
                            val hp = hpAlpha * (prevHp + x - prevRaw)
                            prevRaw = x
                            prevHp = hp

                            // Bước 2: Low-Pass 4200Hz khử tiếng sôi/nhiễu tần số cao
                            val cleanVoice = prevLp + lpAlpha * (hp - prevLp)
                            prevLp = cleanVoice

                            val absIn = abs(cleanVoice)
                            if (absIn.toInt() > maxCleanVoiceInBuffer) {
                                maxCleanVoiceInBuffer = absIn.toInt()
                            }

                            // Bước 3: Đường bao năng lượng giọng nói (Envelope Follower)
                            envelope = if (absIn > envelope) {
                                envelope + attackCoeff * (absIn - envelope)
                            } else {
                                envelope + releaseCoeff * (absIn - envelope)
                            }

                            // Bước 4: Cổng Chống Nhiễu (Noise Gate) + Khuếch Đại Giọng Nói Mượt:
                            // - Khi im lặng / nhiễu nền (< 95): Hạ gain xuống 0.5x để triệt tiêu tiếng sôi/ồn!
                            // - Khi có giọng nói (95 .. 2200): Nâng mượt lên maxVoiceBoost (3.6x – 7.4x)
                            // - Khi giọng nói lớn (> 2200): Cân bằng mượt về targetSpeechLevel (18500)
                            val desiredGain: Float = when {
                                envelope < 95f -> 0.5f
                                envelope < 320f -> {
                                    val t = ((envelope - 95f) / 225f).coerceIn(0f, 1f)
                                    0.5f + t * (maxVoiceBoost - 0.5f)
                                }
                                else -> {
                                    val needed = targetSpeechLevel / envelope
                                    needed.coerceIn(1.2f, maxVoiceBoost)
                                }
                            }

                            val gainStep = if (desiredGain < smoothedGain) 0.018f else 0.0014f
                            smoothedGain += gainStep * (desiredGain - smoothedGain)

                            var outVal = cleanVoice * smoothedGain

                            // Bước 5: Soft-Knee Limiter ở ngưỡng 23500 chống vỡ tiếng
                            val absOut = abs(outVal)
                            if (absOut > 23500f) {
                                val signVal = if (outVal >= 0f) 1f else -1f
                                val excess = absOut - 23500f
                                val softCompressed = 23500f + 7500f * (excess / (excess + 7500f))
                                outVal = signVal * softCompressed
                            }

                            val finalInt = outVal.toInt().coerceIn(-32767, 32767)
                            val absFinal = abs(finalInt)
                            if (absFinal > maxOutInBuffer) maxOutInBuffer = absFinal

                            val finalShort = finalInt.toShort()
                            chunkBytes[i * 2] = (finalShort.toInt() and 0x00FF).toByte()
                            chunkBytes[i * 2 + 1] = ((finalShort.toInt() shr 8) and 0x00FF).toByte()
                        }

                        currentPeakAmplitude = maxOutInBuffer

                        // Nếu luồng thu bị trả về 0 hoàn toàn (do Zalo vừa bấm trả lời chiếm quyền mic),
                        // khởi tạo lại đúng nguồn VOICE_RECOGNITION tối đa 2 lần (tuyệt đối không chuyển sang UNPROCESSED!)
                        if (maxRawInBuffer <= 1) {
                            consecutiveZeroBuffers++
                            if (consecutiveZeroBuffers >= 25 && rebindCount < 2) {
                                consecutiveZeroBuffers = 0
                                rebindCount++
                                val reopened = openCleanVoiceAudioRecord(sampleRate)
                                if (reopened != null) {
                                    try {
                                        activeRecorder.stop()
                                    } catch (_: Exception) {
                                    }
                                    try {
                                        activeRecorder.release()
                                    } catch (_: Exception) {
                                    }
                                    activeRecorder = reopened
                                    activeRecorder.startRecording()
                                    prevRaw = 0f
                                    prevHp = 0f
                                    prevLp = 0f
                                }
                            }
                        } else {
                            consecutiveZeroBuffers = 0
                        }

                        // Kiểm tra kích hoạt ghi xuống file khi có giọng nói thật ("Alo" khi đã nhấc máy):
                        if (!hasCommittedSpeechStart) {
                            if (preRollQueue.size >= maxPreRollChunks) {
                                preRollQueue.removeFirst()
                            }
                            preRollQueue.addLast(chunkBytes)

                            if (maxCleanVoiceInBuffer >= 160) {
                                consecutiveVoiceBuffers++
                                if (consecutiveVoiceBuffers >= 2) {
                                    hasCommittedSpeechStart = true
                                    // Xả toàn bộ 1.0 giây Pre-Roll vào file để bắt trọn chữ "Alo" đầu tiên!
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
                activeRecorder.stop()
            } catch (_: Exception) {
            }
            try {
                activeRecorder.release()
            } catch (_: Exception) {
            }
            // Chỉ giữ lại file nếu thực sự có dữ liệu giọng nói đã được ghi!
            if (outFile.exists() && totalAudioBytes > 0L) {
                updateWavHeader(outFile, totalAudioBytes, sampleRate, 1, 16)
                if (overallPeakRecorded in 450..21000) {
                    normalizeWavFileToBroadcastStandard(outFile, overallPeakRecorded)
                }
                mirrorToPermanentPublicStorage(outFile)
            } else if (outFile.exists()) {
                try {
                    outFile.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    /**
     * Chuẩn hóa âm lượng an toàn (Tối đa 3.2x cho các bản ghi có giọng nói thực sự `peak >= 450`),
     * TUYỆT ĐỐI KHÔNG nhân 85x lên nhiễu nền!
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

                if (peak < 450 || peak >= 22000) return false

                val scale = (22500f / peak.toFloat()).coerceIn(1.1f, 3.2f)
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
                        if (abs(boosted) > 24000f) {
                            val sign = if (boosted >= 0f) 1f else -1f
                            val ex = abs(boosted) - 24000f
                            boosted = sign * (24000f + 6000f * (ex / (ex + 6000f)))
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
