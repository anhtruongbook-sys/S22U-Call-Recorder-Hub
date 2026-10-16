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
 * Động cơ thu âm & xử lý tín hiệu số v2.4.0 cho Samsung Galaxy S22 Ultra (SM-S908U):
 * 1. Giữ nguyên âm lượng loa thoại tự nhiên của máy (`autoBoostEarpieceVolume = false` mặc định,
 *    KHÔNG dùng nguồn `VOICE_COMMUNICATION` gây nhiễu đường truyền loa cuộc gọi đến).
 * 2. Tắt `AcousticEchoCanceler` & `AutomaticGainControl` CHỈ TRÊN `recorder.audioSessionId` (luồng thu của App)
 *    để chip Qualcomm không cắt mất tiếng người bên kia phát ra từ loa thoại.
 * 3. Khuếch đại Đường Bao Mượt Chuẩn Phát Thanh (Broadcast Smooth AGC `12.5x – 18.0x` ngay từ ngưỡng `envelope >= 12`)
 *    kết hợp `Continuous Preemption Watchdog` (tự động mở lại `AudioRecord` nếu Zalo/SIM chiếm quyền mic lúc vừa bấm nghe).
 * 4. Chuẩn hóa Đỉnh Sóng (`normalizeWavFileToBroadcastStandard` lên `26.500 / 32.767` ~ 81% âm lượng tối đa)
 *    cho mọi bản ghi có tín hiệu (`peak >= 3`), đồng thời tự động cứu và kích âm lượng các file `.wav` cũ khi bấm Nghe.
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

    private fun openConfiguredAudioRecord(audioSource: Int, sampleRate: Int): AudioRecord? {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuf <= 0) return null
        val bufferSize = minBuf * 6
        return try {
            val candidate = AudioRecord(audioSource, sampleRate, channelConfig, audioFormat, bufferSize)
            if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                // Tắt AEC & AGC trên đúng session thu của App để thu trọn vẹn cả 2 chiều (giọng mình + loa thoại)
                // mà hoàn toàn không ảnh hưởng đến loa nghe cuộc gọi của máy.
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
                candidate
            } else {
                candidate.release()
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun startRecording(
        appTag: String,
        remoteGainBoost: Float = 2.2f,
        autoBoostEarpieceVolume: Boolean = false
    ): String? {
        if (isRecording) return currentFilePath

        val preferredRates = intArrayOf(48000, 16000, 44100)
        // Tuyệt đối KHÔNG dùng VOICE_COMMUNICATION (7) để tránh làm ồn loa thoại khi có cuộc gọi đến.
        // Ưu tiên VOICE_RECOGNITION (6) -> MIC (1) -> UNPROCESSED (9) -> CAMCORDER (5).
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.CAMCORDER
        )

        var recorder: AudioRecord? = null
        var selectedSampleRate = 48000
        var selectedSourceIndex = 0

        outer@ for (rate in preferredRates) {
            for (idx in sources.indices) {
                val candidate = openConfiguredAudioRecord(sources[idx], rate)
                if (candidate != null) {
                    recorder = candidate
                    selectedSampleRate = rate
                    selectedSourceIndex = idx
                    break@outer
                }
            }
        }

        if (recorder == null) return null

        // Mặc định autoBoostEarpieceVolume = false để giữ nguyên âm lượng loa thoại êm ái của người dùng
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

        val initialRecorder = recorder
        val finalRate = selectedSampleRate
        val startIdx = selectedSourceIndex
        recordingThread = Thread {
            writeWavWithContinuousWatchdogAndAgc(
                initialRecorder = initialRecorder,
                outFile = outFile,
                sampleRate = finalRate,
                initialSourceIdx = startIdx,
                allSources = sources,
                userGainSetting = remoteGainBoost
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

    private fun writeWavWithContinuousWatchdogAndAgc(
        initialRecorder: AudioRecord,
        outFile: File,
        sampleRate: Int,
        initialSourceIdx: Int,
        allSources: IntArray,
        userGainSetting: Float
    ) {
        var activeRecorder: AudioRecord = initialRecorder
        var currentSourceIdx = initialSourceIdx
        var consecutiveSilentBuffers = 0
        var failoverCount = 0

        val pcmBuffer = ShortArray(2048)
        val byteBuffer = ByteArray(pcmBuffer.size * 2)
        var totalAudioBytes = 0L
        var overallPeakRecorded = 0

        val normalizedSlider = userGainSetting.coerceIn(1.0f, 4.0f)
        // Hệ số khuếch đại nhạy cho nguồn VOICE_RECOGNITION trên S22 Ultra (Mặc định 2.2 -> 12.6x max boost, 4.4x base gain)
        val maxRemoteBoost = 4.2f + normalizedSlider * 3.8f
        val localVoiceBaseGain = 2.4f + normalizedSlider * 0.9f
        val targetSpeechLevel = 22000f

        // Bộ lọc High-Pass (~100Hz) khử DC Offset & tiếng ù tần số thấp mà giữ nguyên 100% biên độ giọng nói
        var prevRaw = 0f
        var prevHp = 0f
        val hpAlpha = 0.988f

        // Bộ lọc Low-Pass (~7500Hz) làm mượt gai nhiễu tần số cao
        var prevLp = 0f
        val lpAlpha = 0.68f

        var envelope = 0f
        var smoothedGain = localVoiceBaseGain

        val attackCoeff = 0.018f
        val releaseCoeff = 0.0005f

        try {
            FileOutputStream(outFile).use { fos ->
                fos.write(ByteArray(44))
                activeRecorder.startRecording()

                while (isRecording) {
                    val readCount = activeRecorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readCount > 0) {
                        var maxRawInBuffer = 0
                        var maxOutInBuffer = 0

                        for (i in 0 until readCount) {
                            val rawShort = pcmBuffer[i].toInt()
                            val absRaw = abs(rawShort)
                            if (absRaw > maxRawInBuffer) maxRawInBuffer = absRaw

                            val x = rawShort.toFloat()

                            // Bước 1: Lọc bỏ DC Offset & tiếng ù trầm (< 100Hz)
                            val hp = hpAlpha * (prevHp + x - prevRaw)
                            prevRaw = x
                            prevHp = hp

                            // Bước 2: Lọc mượt gai tần số cao (> 7.5kHz)
                            val cleanSample = prevLp + lpAlpha * (hp - prevLp)
                            prevLp = cleanSample

                            val absIn = abs(cleanSample)

                            // Bước 3: Theo dõi đường bao năng lượng âm thanh (Envelope Follower)
                            envelope = if (absIn > envelope) {
                                envelope + attackCoeff * (absIn - envelope)
                            } else {
                                envelope + releaseCoeff * (absIn - envelope)
                            }

                            // Bước 4: Khuếch đại tự động hướng đích (Broadcast Target-Level Smooth AGC):
                            // Nguồn VOICE_RECOGNITION trên S22 Ultra có biên độ gốc rất nhỏ (15..450),
                            // nên khuếch đại ngay từ ngưỡng envelope >= 12f để bắt trọn cả tiếng loa thoại nhỏ!
                            val desiredGain: Float = when {
                                envelope < 12f -> 1.5f
                                envelope < 140f -> {
                                    val t = ((envelope - 12f) / 128f).coerceIn(0f, 1f)
                                    1.5f + t * (maxRemoteBoost - 1.5f)
                                }
                                else -> {
                                    val needed = targetSpeechLevel / envelope
                                    needed.coerceIn(localVoiceBaseGain * 0.65f, maxRemoteBoost)
                                }
                            }

                            val gainStep = if (desiredGain < smoothedGain) 0.022f else 0.0018f
                            smoothedGain += gainStep * (desiredGain - smoothedGain)

                            var outVal = cleanSample * smoothedGain

                            // Bước 5: Soft-Knee Limiter ở ngưỡng 24500 chống vỡ tiếng
                            val absOut = abs(outVal)
                            if (absOut > 24500f) {
                                val signVal = if (outVal >= 0f) 1f else -1f
                                val excess = absOut - 24500f
                                val softCompressed = 24500f + 7500f * (excess / (excess + 7500f))
                                outVal = signVal * softCompressed
                            }

                            val finalInt = outVal.toInt().coerceIn(-32767, 32767)
                            val absFinal = abs(finalInt)
                            if (absFinal > maxOutInBuffer) maxOutInBuffer = absFinal
                            if (absFinal > overallPeakRecorded) overallPeakRecorded = absFinal

                            val finalShort = finalInt.toShort()
                            byteBuffer[i * 2] = (finalShort.toInt() and 0x00FF).toByte()
                            byteBuffer[i * 2 + 1] = ((finalShort.toInt() shr 8) and 0x00FF).toByte()
                        }

                        currentPeakAmplitude = maxOutInBuffer
                        val bytesToWrite = readCount * 2
                        fos.write(byteBuffer, 0, bytesToWrite)
                        totalAudioBytes += bytesToWrite

                        // Continuous Preemption Watchdog:
                        // Nếu hệ điều hành hoặc Zalo vừa trả lời cuộc gọi và chiếm quyền khiến luồng thu trả về toàn 0
                        // (maxRawInBuffer <= 1 trong 16 buffer liên tiếp ~ 680ms), lập tức khởi tạo lại AudioRecord!
                        if (maxRawInBuffer <= 1) {
                            consecutiveSilentBuffers++
                            if (consecutiveSilentBuffers >= 16 && failoverCount < 12) {
                                consecutiveSilentBuffers = 0
                                failoverCount++
                                currentSourceIdx = (currentSourceIdx + 1) % allSources.size
                                val nextRecorder = openConfiguredAudioRecord(allSources[currentSourceIdx], sampleRate)
                                if (nextRecorder != null) {
                                    try {
                                        activeRecorder.stop()
                                    } catch (_: Exception) {
                                    }
                                    try {
                                        activeRecorder.release()
                                    } catch (_: Exception) {
                                    }
                                    activeRecorder = nextRecorder
                                    activeRecorder.startRecording()
                                    prevRaw = 0f
                                    prevHp = 0f
                                    prevLp = 0f
                                }
                            }
                        } else {
                            consecutiveSilentBuffers = 0
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
            if (outFile.exists() && totalAudioBytes > 0) {
                updateWavHeader(outFile, totalAudioBytes, sampleRate, 1, 16)
                // Chuẩn hóa Peak lên 26.500 (-1.8 dBFS) cho mọi bản ghi có tín hiệu âm thanh (Peak >= 3)
                if (overallPeakRecorded in 3..24000) {
                    normalizeWavFileToBroadcastStandard(outFile, overallPeakRecorded)
                }
                mirrorToPermanentPublicStorage(outFile)
            }
        }
    }

    /**
     * Chuẩn hóa đỉnh sóng + Khử DC Offset (Peak Normalizer lên `26.500 / 32.767` ~ 81% âm lượng tối đa)
     * cho mọi file `.wav` có tín hiệu âm thanh (`peak >= 3`).
     * Đặc biệt: Tự động cứu và kích âm lượng lên rõ ràng cho cả các file `.wav` cũ bị thu nhỏ trước đây khi bấm Nghe!
     */
    fun normalizeWavFileToBroadcastStandard(wavFile: File, knownPeak: Int = 0): Boolean {
        if (!wavFile.exists() || wavFile.length() <= 44L) return false
        return try {
            RandomAccessFile(wavFile, "rw").use { raf ->
                val dataLen = (raf.length() - 44L).toInt()
                if (dataLen <= 0) return false

                val buf = ByteArray(32768)
                var sumSamples = 0L
                var countSamples = 0L

                // Bước 1: Quét tính DC Offset trung bình (mean) và biên độ đỉnh thực (AC Peak)
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
                        sumSamples += sample
                        countSamples++
                        idx += 2
                    }
                    remaining -= read
                }

                if (countSamples <= 0L) return false
                val dcOffset = (sumSamples / countSamples).toInt()

                var acPeak = 0
                raf.seek(44L)
                remaining = dataLen
                while (remaining > 0) {
                    val toRead = min(buf.size, remaining)
                    val read = raf.read(buf, 0, toRead)
                    if (read <= 0) break
                    var idx = 0
                    while (idx + 1 < read) {
                        val lo = buf[idx].toInt() and 0xFF
                        val hi = buf[idx + 1].toInt()
                        val sample = ((hi shl 8) or lo).toShort().toInt() - dcOffset
                        val absVal = abs(sample)
                        if (absVal > acPeak) acPeak = absVal
                        idx += 2
                    }
                    remaining -= read
                }

                val effectivePeak = if (acPeak > 0) acPeak else knownPeak
                if (effectivePeak < 3 || effectivePeak >= 24500) return false

                // Cho phép khuếch đại lên tới 85x để cứu cả những file thu âm bị nhỏ trước đó
                val scale = (26500f / effectivePeak.toFloat()).coerceIn(1.1f, 85.0f)
                var readPos = 44L
                remaining = dataLen

                var prevLp = 0f
                val lpAlpha = 0.65f

                while (remaining > 0) {
                    val toRead = min(buf.size, remaining)
                    raf.seek(readPos)
                    val read = raf.read(buf, 0, toRead)
                    if (read <= 0) break

                    var idx = 0
                    while (idx + 1 < read) {
                        val lo = buf[idx].toInt() and 0xFF
                        val hi = buf[idx + 1].toInt()
                        val centered = (((hi shl 8) or lo).toShort().toInt() - dcOffset).toFloat()
                        val smoothed = prevLp + lpAlpha * (centered - prevLp)
                        prevLp = smoothed

                        var boosted = smoothed * scale
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
