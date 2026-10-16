package com.s22ultra.callrecorder

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlin.math.max
import kotlin.math.min

/**
 * Động cơ thu âm & xử lý tín hiệu số (Pure Voice-Band Adaptive Gate + Natural Loudness DSP v2.3.0)
 * cho Samsung Galaxy S22 Ultra:
 * - TUYỆT ĐỐI KHÔNG can thiệp vào `AcousticEchoCanceler` / `NoiseSuppressor` phần cứng của luồng đàm thoại trực tiếp,
 *   và không ép âm lượng loa thoại lên 88% -> Giữ âm thanh loa nghe cuộc gọi đến/đi TRONG TRẺO 100% như mặc định của máy!
 * - Bộ lọc dải tần giọng nói (`280Hz – 3500Hz`) + Cổng chống ồn thích ứng (`Adaptive Noise-Floor Gate`):
 *   + Khi không có tiếng người nói (chỉ có tiếng ồn môi trường `< 520`): Giữ nguyên `Gain = 0.85x` (tuyệt đối không khuếch đại tiếng xì/ồn nền).
 *   + Chỉ khi phát hiện giọng nói thực sự (`> 550`): Nâng mượt giọng nói lên mức rõ ràng tự nhiên (`3.2x – 6.5x`, đích `17.500 / 32.767`).
 * - Tự động xóa bỏ file tạm nếu bị ngắt sớm (`0 bytes`), chỉ lưu file có dữ liệu âm thanh thực tế.
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

        // Nguồn thu độc lập không làm ảnh hưởng tới bộ khử ồn cuộc gọi trực tiếp của S22 Ultra
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED
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

        // QUAN TRỌNG: Không đụng chạm vào AcousticEchoCanceler / AutomaticGainControl của session phần cứng
        // để loa thoại S22 Ultra khi nghe cuộc gọi đến hoàn toàn êm ái, không bị dội âm hay sôi nền!

        // Chỉ nâng nhẹ loa thoại lên mức 65% (nếu người dùng chủ động bật và loa đang quá nhỏ < 60%)
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
        val safeTag = appTag.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val outFile = File(getRecordingsDir(), "${safeTag}_${timestamp}.wav")

        currentFilePath = outFile.absolutePath
        currentAppTag = appTag
        startTimeMs = System.currentTimeMillis()
        isRecording = true

        val activeRecorder = recorder
        val finalRate = selectedSampleRate
        recordingThread = Thread {
            writeWavWithPureVoiceBandDsp(activeRecorder, outFile, finalRate, remoteGainBoost)
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
            recordingThread?.join(3000)
        } catch (_: InterruptedException) {
        }
        recordingThread = null
        currentPeakAmplitude = 0

        val path = currentFilePath ?: return null
        val file = File(path)
        if (!file.exists() || file.length() <= 44L) {
            try {
                file.delete()
            } catch (_: Exception) {
            }
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

    /**
     * Bộ xử lý DSP Dải Giọng Nói Tinh Khiết (Voice-Band Bandpass 280Hz-3500Hz + Adaptive Noise-Floor Gate):
     * - Lọc sạch tiếng ù tần số thấp (< 280Hz) và tiếng xì tần số cao (> 3500Hz).
     * - Tự động đo nền ồn môi trường (`noiseFloor`): Khi chỉ có tiếng ồn phòng (< 520), giữ Gain = 0.85x (êm ru).
     * - Khi có tiếng người nói (> 550): khuếch đại mượt giọng nói lên mức rõ ràng tự nhiên (`3.2x – 6.5x`).
     */
    private fun writeWavWithPureVoiceBandDsp(
        recorder: AudioRecord,
        outFile: File,
        sampleRate: Int,
        userGainSetting: Float
    ) {
        val pcmBuffer = ShortArray(2048)
        val byteBuffer = ByteArray(pcmBuffer.size * 2)
        var totalAudioBytes = 0L
        var overallPeakRecorded = 0

        val normalizedSlider = userGainSetting.coerceIn(1.0f, 4.0f)
        // Hệ số khuếch đại giọng nói vừa vặn, trong trẻo (mặc định 2.2 -> tối đa 5.2x cho tiếng loa thoại, không thổi phồng tạp âm)
        val maxVoiceBoost = 2.0f + normalizedSlider * 1.45f
        val targetSpeechLevel = 17500f

        // Bộ lọc High-Pass (~260Hz ở 48kHz) cắt bỏ hoàn toàn tiếng ù nền phòng / tiếng gió
        var prevRaw = 0f
        var prevHp = 0f
        val hpAlpha = 0.968f

        // Bộ lọc Low-Pass (~3600Hz ở 48kHz) cắt bỏ hoàn toàn tiếng xì (white noise hiss) tần số cao
        var prevLp1 = 0f
        var prevLp2 = 0f
        val lpAlpha = 0.36f

        // Đường bao năng lượng giọng nói & Nền ồn thích ứng (Adaptive Noise Floor)
        var envelope = 0f
        var noiseFloor = 220f
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
                        var maxAmp = 0
                        for (i in 0 until readCount) {
                            val x = pcmBuffer[i].toFloat()

                            // Bước 1: Lọc High-Pass (> 260Hz) khử tiếng ù trầm
                            val hp = hpAlpha * (prevHp + x - prevRaw)
                            prevRaw = x
                            prevHp = hp

                            // Bước 2: Lọc Low-Pass 2 bậc (< 3600Hz) khử tiếng xì nền tần số cao
                            prevLp1 += lpAlpha * (hp - prevLp1)
                            prevLp2 += lpAlpha * (prevLp1 - prevLp2)
                            val cleanVoiceSample = prevLp2

                            val absIn = abs(cleanVoiceSample)

                            // Bước 3: Theo dõi đường bao năng lượng âm thanh (Envelope)
                            envelope = if (absIn > envelope) {
                                envelope + attackCoeff * (absIn - envelope)
                            } else {
                                envelope + releaseCoeff * (absIn - envelope)
                            }

                            // Cập nhật nền ồn môi trường (Noise Floor Tracker)
                            noiseFloor = if (envelope < noiseFloor) {
                                noiseFloor * 0.995f + envelope * 0.005f
                            } else {
                                (noiseFloor * 0.99995f + envelope * 0.00005f).coerceIn(140f, 650f)
                            }

                            val voiceThreshold = max(500f, noiseFloor * 2.1f)

                            // Bước 4: Cổng chống ồn thông minh (Adaptive Voice Gate)
                            // - Dưới ngưỡng giọng nói (< voiceThreshold): hạ Gain về 0.85x (Im lặng sạch sẽ, không sôi nền!)
                            // - Trong vùng giọng nói: nâng mượt lên maxVoiceBoost để kéo giọng nói về 17500
                            val desiredGain: Float = when {
                                envelope < voiceThreshold * 0.75f -> 0.85f
                                envelope < voiceThreshold * 1.4f -> {
                                    val t = ((envelope - voiceThreshold * 0.75f) / (voiceThreshold * 0.65f)).coerceIn(0f, 1f)
                                    0.85f + t * (maxVoiceBoost - 0.85f)
                                }
                                else -> {
                                    val needed = targetSpeechLevel / envelope
                                    needed.coerceIn(1.5f, maxVoiceBoost)
                                }
                            }

                            val gainStep = if (desiredGain < smoothedGain) 0.015f else 0.0012f
                            smoothedGain += gainStep * (desiredGain - smoothedGain)

                            var outVal = cleanVoiceSample * smoothedGain

                            // Bước 5: Bộ giới hạn mềm liên tục (Continuous Soft-Knee Limiter) ở ngưỡng 23000
                            val absOut = abs(outVal)
                            if (absOut > 23000f) {
                                val signVal = if (outVal >= 0f) 1f else -1f
                                val excess = absOut - 23000f
                                val softCompressed = 23000f + 7500f * (excess / (excess + 7500f))
                                outVal = signVal * softCompressed
                            }

                            val finalInt = outVal.toInt().coerceIn(-32767, 32767)
                            val absFinal = abs(finalInt)
                            if (absFinal > maxAmp) maxAmp = absFinal
                            if (absFinal > overallPeakRecorded) overallPeakRecorded = absFinal

                            val finalShort = finalInt.toShort()
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
                // Chuẩn hóa nhẹ nhàng (Tối đa 3.0x) nếu có giọng nói thực sự (Peak >= 800) để không đẩy tiếng ồn nền
                if (overallPeakRecorded in 800..21000) {
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
     * Chuẩn hóa âm lượng tự nhiên (Peak Normalizer tối đa 3.2x lên `22.500 / 32.767`)
     * Giúp giọng nói rõ ràng mà tuyệt đối không thổi phồng tạp âm môi trường.
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

                if (peak < 800 || peak >= 22000) return false

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
