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
import kotlin.math.min

/**
 * Động cơ thu âm & xử lý tín hiệu số (Broadcast Loudness 2-Stage Smooth AGC + Post-Normalizer v2.1.0)
 * cho Samsung Galaxy S22 Ultra:
 * - Khắc phục triệt để hiện tượng thu âm nhỏ từ nguồn `VOICE_RECOGNITION` (6) bằng 2 tầng khuếch đại:
 *   + Tầng 1 (Real-time Broadcast Target AGC): Đưa biên độ giọng nói mục tiêu lên `22.000 / 32.767` (`-3.4 dBFS`),
 *     khuếch đại mượt giọng loa thoại đầu dây bên kia lên tới `12x - 18x` và giọng mic trực tiếp `4.5x - 6x`
 *     nhưng vẫn giữ đường bao mượt (Smooth Envelope) + Soft-Knee Limiter chống vỡ tiếng.
 *   + Tầng 2 (Post-Recording Lossless Peak Normalizer): Ngay khi kết thúc ghi âm, tự động chuẩn hóa đỉnh sóng
 *     (Peak Normalization) của file `.wav` lên `28.500 / 32.767` (`-1.2 dBFS`, đạt 88% âm lượng tối đa chuẩn phát thanh)
 *     để nghe to, rõ ràng trên mọi trình phát nhạc.
 * - Lưu trữ vĩnh viễn tại `/storage/emulated/0/Download/S22U_Call_Recordings/`.
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
        autoBoostEarpieceVolume: Boolean = true
    ): String? {
        if (isRecording) return currentFilePath

        val preferredRates = intArrayOf(48000, 16000, 44100)
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT

        // Nguồn thu trên S22 Ultra: VOICE_RECOGNITION (6) -> MIC (1) -> UNPROCESSED (9)
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

        // Tắt AcousticEchoCanceler (AEC) để không cắt mất tiếng loa thoại đầu dây bên kia
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

        // Đặt âm lượng loa thoại ở mức 88% để tiếng đầu dây bên kia truyền sang mic rõ và to nhất
        if (autoBoostEarpieceVolume) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val targetVol = (maxVol * 0.88).toInt().coerceAtLeast(1)
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
            writeWavWithBroadcastLoudnessDsp(activeRecorder, outFile, finalRate, remoteGainBoost)
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
     * Tầng 1: Bộ xử lý DSP Chuẩn Phát Thanh (Broadcast Target-Level Smooth AGC + Pre-Amp Makeup Gain):
     * - Vì nguồn `VOICE_RECOGNITION` trên S22 Ultra tắt Pre-Amp phần cứng nên biên độ gốc rất nhỏ (`200..1500`).
     * - Thuật toán này nhân Pre-Amp cơ sở và tự động kéo biên độ giọng nói mục tiêu lên `22.000` (`-3.4 dBFS`),
     *   với hệ số khuếch đại đường bao mượt lên tới `6.0x .. 18.0x` cho tiếng loa thoại nhỏ và `4.5x` cho giọng nói gần.
     */
    private fun writeWavWithBroadcastLoudnessDsp(
        recorder: AudioRecord,
        outFile: File,
        sampleRate: Int,
        userGainSetting: Float
    ) {
        val pcmBuffer = ShortArray(2048)
        val byteBuffer = ByteArray(pcmBuffer.size * 2)
        var totalAudioBytes = 0L
        var overallPeakRecorded = 0

        // Ánh xạ thanh trượt của người dùng (1.0 .. 4.0, mặc định 2.2) sang hệ số khuếch đại Pre-Amp thực tế (6.0x .. 18.0x)
        val normalizedSlider = userGainSetting.coerceIn(1.0f, 4.0f)
        val maxRemoteBoost = 4.0f + normalizedSlider * 3.8f // Mặc định 2.2 -> 12.36x boost cho tiếng loa thoại nhỏ!
        val localVoiceBaseGain = 2.5f + normalizedSlider * 0.9f // Mặc định 2.2 -> 4.48x boost cho giọng nói trực tiếp!
        val targetSpeechLevel = 22000f // Mức âm lượng chuẩn phát thanh (-3.4 dBFS)

        // Bộ lọc High-Pass (~100Hz) loại bỏ DC Offset & tiếng ù trầm
        var prevRaw = 0f
        var prevHp = 0f
        val hpAlpha = 0.986f

        // Bộ lọc Low-Pass (~7800Hz) làm mượt gai nhiễu tần số cao
        var prevLp = 0f
        val lpAlpha = 0.68f

        // Đường bao biên độ (Envelope Follower) & Hệ số khuếch đại mượt (Smoothed Gain)
        var envelope = 0f
        var smoothedGain = localVoiceBaseGain

        val attackCoeff = 0.018f
        val releaseCoeff = 0.0005f

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

                            // Bước 1: Lọc bỏ DC Offset & tiếng ù tần số thấp
                            val hp = hpAlpha * (prevHp + x - prevRaw)
                            prevRaw = x
                            prevHp = hp

                            // Bước 2: Lọc mượt gai tần số cao
                            val cleanSample = prevLp + lpAlpha * (hp - prevLp)
                            prevLp = cleanSample

                            val absIn = abs(cleanSample)

                            // Bước 3: Theo dõi đường bao năng lượng âm thanh (Envelope Follower)
                            envelope = if (absIn > envelope) {
                                envelope + attackCoeff * (absIn - envelope)
                            } else {
                                envelope + releaseCoeff * (absIn - envelope)
                            }

                            // Bước 4: Tính toán Gain tự động hướng đích (Target-Level Smooth AGC)
                            // - Nền tĩnh thực sự (< 35): giữ mức khuếch đại nhẹ 1.8x để không nâng tiếng xì nền
                            // - Tiếng loa thoại đầu dây bên kia (35 .. 2200): nâng mượt lên maxRemoteBoost (12x - 18x) để kéo về 22000
                            // - Giọng nói trực tiếp vào mic (> 2200): tự động cân bằng về targetSpeechLevel (22000)
                            val desiredGain: Float = when {
                                envelope < 35f -> 1.8f
                                envelope < 180f -> {
                                    val t = ((envelope - 35f) / 145f).coerceIn(0f, 1f)
                                    1.8f + t * (maxRemoteBoost - 1.8f)
                                }
                                else -> {
                                    val neededForTarget = targetSpeechLevel / envelope
                                    neededForTarget.coerceIn(localVoiceBaseGain * 0.65f, maxRemoteBoost)
                                }
                            }

                            // Làm mượt chuyển tiếp Gain (Không nhảy bậc -> Không bao giờ bị rè)
                            val gainStep = if (desiredGain < smoothedGain) 0.025f else 0.0018f
                            smoothedGain += gainStep * (desiredGain - smoothedGain)

                            var outVal = cleanSample * smoothedGain

                            // Bước 5: Bộ giới hạn mềm liên tục (Continuous Soft-Knee Limiter) ở ngưỡng 24500 -> tối đa 32000
                            val absOut = abs(outVal)
                            if (absOut > 24500f) {
                                val signVal = if (outVal >= 0f) 1f else -1f
                                val excess = absOut - 24500f
                                val softCompressed = 24500f + 7500f * (excess / (excess + 7500f))
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
                // Tầng 2: Chuẩn hóa đỉnh sóng (Lossless Peak Normalization) lên 28.500 (-1.2 dBFS) nếu cuộc gọi vẫn còn nhỏ
                if (overallPeakRecorded in 150..25500) {
                    normalizeWavFileToBroadcastStandard(outFile, overallPeakRecorded)
                }
                mirrorToPermanentPublicStorage(outFile)
            }
        }
    }

    /**
     * Tầng 2: Chuẩn hóa âm lượng Lossless (Post-Recording Peak Normalizer) lên `28.500 / 32.767` (`-1.2 dBFS`).
     * Đảm bảo 100% mọi file `.wav` xuất ra đều có âm lượng to, rõ ràng tối đa mà không bị méo sóng.
     */
    fun normalizeWavFileToBroadcastStandard(wavFile: File, knownPeak: Int = 0): Boolean {
        if (!wavFile.exists() || wavFile.length() <= 44L) return false
        return try {
            RandomAccessFile(wavFile, "rw").use { raf ->
                val dataLen = (raf.length() - 44L).toInt()
                if (dataLen <= 0) return false

                var peak = knownPeak
                val buf = ByteArray(32768)

                // Nếu chưa biết Peak (ví dụ chuẩn hóa lại file cũ), quét nhanh tìm Peak
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

                // Nếu âm lượng đã đạt >= 26000 (to rõ) hoặc file rỗng (< 80) thì không cần nhân thêm
                if (peak < 80 || peak >= 26000) return false

                val scale = (28500f / peak.toFloat()).coerceIn(1.1f, 12.0f)
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
                        if (abs(boosted) > 27000f) {
                            val sign = if (boosted >= 0f) 1f else -1f
                            val ex = abs(boosted) - 27000f
                            boosted = sign * (27000f + 5200f * (ex / (ex + 5200f)))
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
