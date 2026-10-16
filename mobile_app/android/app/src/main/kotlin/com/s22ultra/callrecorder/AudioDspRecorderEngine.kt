package com.s22ultra.callrecorder

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
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
import kotlin.math.sign

/**
 * Động cơ thu âm & xử lý tín hiệu số (DSP) tối ưu cho Samsung Galaxy S22 Ultra (Non-Root).
 * - Khởi tạo từ ngữ cảnh của OTTCallAccessibilityService với nguồn VOICE_RECOGNITION (6)
 *   để được Android 10-15 cho phép thu âm đồng thời khi Zalo/Viber/Telegram/WhatsApp đang gọi.
 * - Tắt bộ khử vọng phần cứng (AEC) để không bị xóa mất tiếng người bên kia phát ra từ loa thoại S22 Ultra.
 * - Khuếch đại thích ứng (Adaptive Earpiece Gain Boost) cho các mẫu âm thanh nhỏ (giọng người bên kia)
 *   và giới hạn mềm (Soft Limiter) cho giọng nói lớn (giọng người dùng).
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

    fun getRecordingsDir(): File {
        val dir = File(context.getExternalFilesDir(null), "OTT_Recordings")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    @Synchronized
    fun startRecording(
        appTag: String,
        remoteGainBoost: Float = 4.5f,
        autoBoostEarpieceVolume: Boolean = true
    ): String? {
        if (isRecording) return currentFilePath

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufSize <= 0) return null

        val bufferSize = minBufSize * 4

        // Thử lần lượt VOICE_RECOGNITION (tốt nhất cho Accessibility trên S22 Ultra) -> MIC -> VOICE_COMMUNICATION
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )

        var recorder: AudioRecord? = null
        for (src in sources) {
            try {
                val candidate = AudioRecord(src, sampleRate, channelConfig, audioFormat, bufferSize)
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    recorder = candidate
                    break
                } else {
                    candidate.release()
                }
            } catch (_: Exception) {
            }
        }

        if (recorder == null) return null

        // Tắt AcousticEchoCanceler (AEC) nếu có để bảo toàn âm thanh từ loa thoại S22 Ultra
        try {
            val sessionId = recorder.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                val aec = AcousticEchoCanceler.create(sessionId)
                aec?.enabled = false
            }
            if (AutomaticGainControl.isAvailable()) {
                val agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
            }
        } catch (_: Exception) {
        }

        // Tự động nâng âm lượng loa thoại lên tối thiểu 80% trên S22 Ultra để thu rõ chiều bên kia
        if (autoBoostEarpieceVolume) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                val targetVol = (maxVol * 0.85).toInt().coerceAtLeast(1)
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
        recordingThread = Thread {
            writeWavWithAdaptiveDsp(activeRecorder, outFile, sampleRate, remoteGainBoost)
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

    private fun writeWavWithAdaptiveDsp(
        recorder: AudioRecord,
        outFile: File,
        sampleRate: Int,
        remoteGainBoost: Float
    ) {
        val pcmBuffer = ShortArray(2048)
        val byteBuffer = ByteArray(pcmBuffer.size * 2)
        var totalAudioBytes = 0L

        try {
            FileOutputStream(outFile).use { fos ->
                // Ghi header WAV 44 bytes tạm thời
                fos.write(ByteArray(44))
                recorder.startRecording()

                while (isRecording) {
                    val readCount = recorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readCount > 0) {
                        var maxAmp = 0
                        for (i in 0 until readCount) {
                            val rawSample = pcmBuffer[i].toInt()
                            val absVal = abs(rawSample)
                            if (absVal > maxAmp) maxAmp = absVal

                            // Thuật toán Adaptive DSP Gain cho Samsung S22 Ultra:
                            // - Ngưỡng nhiễu nền tĩnh (Noise Gate): < 90 giữ nguyên
                            // - Vùng âm thanh loa thoại người bên kia (90..4800): khuếch đại bằng remoteGainBoost
                            // - Vùng giọng nói trực tiếp của người dùng (> 4800): nén mềm chống vỡ tiếng (Soft Limiter)
                            val processedSample: Int = when {
                                absVal < 90 -> rawSample
                                absVal <= 4800 -> {
                                    (rawSample * remoteGainBoost).toInt().coerceIn(-28000, 28000)
                                }
                                else -> {
                                    val base = 4800f * remoteGainBoost
                                    val excess = (absVal - 4800).toFloat()
                                    val compressed = base + excess * 0.45f
                                    (sign(rawSample.toFloat()) * min(32000f, compressed)).toInt()
                                }
                            }

                            val clamped = processedSample.coerceIn(-32767, 32767).toShort()
                            byteBuffer[i * 2] = (clamped.toInt() and 0x00FF).toByte()
                            byteBuffer[i * 2 + 1] = ((clamped.toInt() shr 8) and 0x00FF).toByte()
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
            }
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
        writeIntLE(header, 16, 16) // PCM chunk size
        header[20] = 1 // AudioFormat = 1 (PCM)
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
