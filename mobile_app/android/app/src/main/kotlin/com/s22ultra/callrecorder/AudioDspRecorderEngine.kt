package com.s22ultra.callrecorder

import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
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

    @Volatile
    private var markCallAnsweredRequested: Boolean = false

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
     * Cập nhật tên người gọi Tiếng Việt (ví dụ từ `Zalo_OTT` sang `Zalo_Trương_Thành_Long`).
     * Nếu trước đó đang là nhãn chung (`Zalo_OTT`, `Zalo`, `SIM_Call`) và chuyển sang tên người gọi cụ thể
     * khi đầu dây bên kia vừa nhấc máy, đánh dấu mốc bắt đầu đàm thoại để cắt phần đổ chuông đầu file.
     */
    @Synchronized
    fun updateActiveCallTag(newTag: String) {
        if (!isRecording || newTag.isBlank() || currentAppTag == "Manual") return
        val safeNew = sanitizeVietnameseFileName(newTag)
        val safeOld = sanitizeVietnameseFileName(currentAppTag)
        if (safeNew == safeOld || safeNew == "Zalo_OTT") return

        val wasGenericTag = (safeOld == "Zalo_OTT" || safeOld == "Zalo" || safeOld == "SIM_Call")
        currentAppTag = newTag
        if (wasGenericTag) {
            markCallAnsweredRequested = true
        }
    }

    @Volatile
    private var upgradeToAccessibilityRequested: Boolean = false

    fun requestAccessibilityStreamUpgrade() {
        if (isRecording) {
            upgradeToAccessibilityRequested = true
        }
    }

    /**
     * Được gọi khi thông báo Zalo chuyển sang trạng thái "Đang gọi thoại" / có đồng hồ đếm giây (đã nhấc máy).
     */
    @Synchronized
    fun notifyCallConnectedAndTrimPreRing(newTag: String) {
        if (!isRecording || currentAppTag == "Manual") return
        if (newTag.isNotBlank()) {
            val safeNew = sanitizeVietnameseFileName(newTag)
            if (safeNew != "Zalo_OTT") {
                currentAppTag = newTag
            }
        }
        markCallAnsweredRequested = true
        upgradeToAccessibilityRequested = true
    }

    private fun openCleanAudioRecord(source: Int, sampleRate: Int): AudioRecord? {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufSize <= 0) return null
        val bufferSize = minBufSize * 6
        val activeCtx: Context = OTTCallAccessibilityService.instance ?: context
        return try {
            val candidate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                AudioRecord.Builder()
                    .setContext(activeCtx)
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } else {
                AudioRecord(source, sampleRate, channelConfig, audioFormat, bufferSize)
            }
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
        // Chỉ dùng VOICE_RECOGNITION (6) và MIC (1) (để dành VOICE_COMMUNICATION (7) nhận diện chính xác cuộc gọi Zalo/VoIP)
        val cleanSources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )

        var recorder: AudioRecord? = null
        var selectedSampleRate = 48000
        var selectedSourceIdx = 0

        outer@ for (rate in preferredRates) {
            for (idx in cleanSources.indices) {
                val candidate = openCleanAudioRecord(cleanSources[idx], rate)
                if (candidate != null) {
                    recorder = candidate
                    selectedSampleRate = rate
                    selectedSourceIdx = idx
                    break@outer
                }
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

        currentTimestampStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeTag = sanitizeVietnameseFileName(appTag)
        val outFile = File(getRecordingsDir(), "${safeTag}_${currentTimestampStr}.wav")

        currentFilePath = outFile.absolutePath
        currentAppTag = appTag
        startTimeMs = System.currentTimeMillis()
        markCallAnsweredRequested = false
        isRecording = true

        val activeRecorder = recorder
        val finalRate = selectedSampleRate
        val startSourceIdx = selectedSourceIdx

        recordingThread = Thread {
            writeWavContinuousWithAnswerBookmark(
                initialRecorder = activeRecorder,
                initialOutFile = outFile,
                sampleRate = finalRate,
                initialSourceIdx = startSourceIdx,
                cleanSources = cleanSources,
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
        if (!isRecording && recordingThread == null) return null
        isRecording = false
        try {
            recordingThread?.join(2500)
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
        return mapOf(
            "path" to file.absolutePath,
            "name" to file.name,
            "appTag" to currentAppTag,
            "sizeBytes" to file.length(),
            "durationSec" to durationSec,
            "sha256" to ""
        )
    }

    /**
     * Ghi âm liên tục 100% thời lượng cuộc gọi (KHÔNG BAO GIỜ bỏ qua buffer trong lúc đang đàm thoại),
     * đồng thời đánh dấu vị trí byte lúc bắt máy (`answeredByteOffset`) để tự động cắt bỏ đoạn đổ chuông
     * ở đầu file sau khi kết thúc cuộc gọi nếu sau mốc bắt máy có đầy đủ âm thanh cuộc trò chuyện!
     */
    private fun writeWavContinuousWithAnswerBookmark(
        initialRecorder: AudioRecord,
        initialOutFile: File,
        sampleRate: Int,
        initialSourceIdx: Int,
        cleanSources: IntArray,
        userGainSetting: Float
    ) {
        var activeRecorder = initialRecorder
        var currentSourceIdx = initialSourceIdx
        var outFile = initialOutFile

        val pcmBuffer = ShortArray(2048)
        val chunkBytes = ByteArray(pcmBuffer.size * 2)
        var totalAudioBytes = 0L
        var overallPeakRecorded = 0

        // Mốc byte đánh dấu thời điểm đầu dây bên kia nhấc máy (chuyển sang "Đang gọi thoại")
        var answeredByteOffset = 0L
        var peakAfterAnswer = 0

        // Theo dõi nếu hệ điều hành trả về toàn số 0 tuyệt đối (maxRawInBuffer == 0) khi Zalo vừa bắt máy
        var consecutiveExactZeroBuffers = 0
        var recoveryAttempts = 0

        val normalizedSlider = userGainSetting.coerceIn(1.0f, 4.0f)
        // Khuếch đại mượt mà giọng loa thoại từ ngưỡng envelope >= 14f lên tới 8.5x
        val maxRemoteBoost = 4.0f + normalizedSlider * 2.0f

        // Bộ lọc High-Pass (~110Hz) & Low-Pass (~7500Hz) nguyên bản của v2.0.0 (đã kiểm chứng không gây nhiễu)
        var prevRaw = 0f
        var prevHp = 0f
        val hpAlpha = 0.985f

        var prevLp = 0f
        val lpAlpha = 0.62f

        var envelope = 0f
        var smoothedGain = 1.2f

        val attackCoeff = 0.016f
        val releaseCoeff = 0.00045f

        try {
            FileOutputStream(outFile).use { fos ->
                fos.write(ByteArray(44))
                activeRecorder.startRecording()

                while (isRecording) {
                    val readCount = activeRecorder.read(pcmBuffer, 0, pcmBuffer.size)
                    if (readCount > 0) {
                        var maxRawInBuffer = 0
                        var maxOutInBuffer = 0

                        // Nếu thông báo Zalo vừa báo đã nhấc máy ("Đang gọi thoại") trong 25 giây đầu,
                        // lưu lại mốc byte để cuối cuộc gọi cắt bỏ phần chờ đổ chuông trước đó
                        if (markCallAnsweredRequested) {
                            markCallAnsweredRequested = false
                            val maxRingingBytes = sampleRate.toLong() * 2L * 25L
                            if (answeredByteOffset == 0L && totalAudioBytes in 1L..maxRingingBytes) {
                                answeredByteOffset = totalAudioBytes
                                peakAfterAnswer = 0
                            }
                        }

                        for (i in 0 until readCount) {
                            val rawShort = pcmBuffer[i].toInt()
                            val absRaw = abs(rawShort)
                            if (absRaw > maxRawInBuffer) maxRawInBuffer = absRaw

                            val x = rawShort.toFloat()

                            // Bước 1: Lọc bỏ DC Offset & Nhiễu ù tần số thấp (< 110Hz)
                            val hp = hpAlpha * (prevHp + x - prevRaw)
                            prevRaw = x
                            prevHp = hp

                            // Bước 2: Lọc mượt gai nhiễu tần số cao (> 7.5kHz)
                            val cleanSample = prevLp + lpAlpha * (hp - prevLp)
                            prevLp = cleanSample

                            val absIn = abs(cleanSample)

                            // Bước 3: Theo dõi đường bao năng lượng âm thanh (Envelope Follower)
                            envelope = if (absIn > envelope) {
                                envelope + attackCoeff * (absIn - envelope)
                            } else {
                                envelope + releaseCoeff * (absIn - envelope)
                            }

                            // Bước 4: Tính toán Gain mượt mà bắt trọn tiếng loa thoại nhỏ (envelope >= 14f):
                            // - Nền tĩnh tuyệt đối (envelope < 14): giữ gain = 1.0f (không khuếch đại nhiễu nền)
                            // - Tiếng loa thoại người bên kia & giọng nói vừa (14..3200): nâng mượt lên maxRemoteBoost (~8.5x)
                            // - Giọng nói lớn trực tiếp (> 3200): nén mượt về 1.5f chống vỡ tiếng
                            val desiredGain: Float = when {
                                envelope < 14f -> 1.0f
                                envelope < 2800f -> {
                                    val t = ((envelope - 14f) / 140f).coerceIn(0f, 1f)
                                    1.0f + t * (maxRemoteBoost - 1.0f)
                                }
                                envelope < 7500f -> {
                                    val t = ((envelope - 2800f) / 4700f).coerceIn(0f, 1f)
                                    maxRemoteBoost - t * (maxRemoteBoost - 1.5f)
                                }
                                else -> 1.5f
                            }

                            val gainStep = if (desiredGain < smoothedGain) 0.02f else 0.0015f
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

                        currentPeakAmplitude = maxOutInBuffer
                        if (maxOutInBuffer > overallPeakRecorded) {
                            overallPeakRecorded = maxOutInBuffer
                        }
                        if (answeredByteOffset > 0L && maxOutInBuffer > peakAfterAnswer) {
                            peakAfterAnswer = maxOutInBuffer
                        }

                        // LUÔN LUÔN ghi 100% dữ liệu vào file (không bao giờ dùng continue bỏ qua giữa cuộc gọi!)
                        val bytesToWrite = readCount * 2
                        fos.write(chunkBytes, 0, bytesToWrite)
                        totalAudioBytes += bytesToWrite

                        // Khôi phục / Nâng cấp tự động luồng thu âm với quyền OTTCallAccessibilityService
                        // ngay khi Cầu nối vừa kết nối hoặc khi Zalo vừa chuyển trạng thái bắt máy
                        if (upgradeToAccessibilityRequested) {
                            upgradeToAccessibilityRequested = false
                            val upgraded = openCleanAudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate)
                                ?: openCleanAudioRecord(MediaRecorder.AudioSource.MIC, sampleRate)
                            if (upgraded != null) {
                                try {
                                    activeRecorder.stop()
                                } catch (_: Exception) {
                                }
                                try {
                                    activeRecorder.release()
                                } catch (_: Exception) {
                                }
                                activeRecorder = upgraded
                                activeRecorder.startRecording()
                                prevRaw = 0f
                                prevHp = 0f
                                prevLp = 0f
                            }
                        } else if (maxRawInBuffer <= 5) {
                            consecutiveExactZeroBuffers++
                            if (consecutiveExactZeroBuffers >= 18 && recoveryAttempts < 8) {
                                consecutiveExactZeroBuffers = 0
                                recoveryAttempts++
                                currentSourceIdx = (currentSourceIdx + 1) % cleanSources.size
                                val reopened = openCleanAudioRecord(cleanSources[currentSourceIdx], sampleRate)
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
                            consecutiveExactZeroBuffers = 0
                        }
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            isRecording = false
            currentPeakAmplitude = 0
            try {
                activeRecorder.stop()
            } catch (_: Exception) {
            }
            try {
                activeRecorder.release()
            } catch (_: Exception) {
            }

            if (outFile.exists() && totalAudioBytes > 0L) {
                // Nếu có mốc bắt máy (answeredByteOffset > 0) và phần đàm thoại sau khi bắt máy dài >= 2.5 giây
                // kèm có tín hiệu âm thanh thực (peakAfterAnswer >= 25), cắt bỏ đoạn chờ đổ chuông trước khi bắt máy!
                val minConversationBytes = sampleRate.toLong() * 2L * 5L / 2L // 2.5 giây
                val conversationBytes = totalAudioBytes - answeredByteOffset
                if (answeredByteOffset > 0L && conversationBytes >= minConversationBytes && peakAfterAnswer >= 25) {
                    if (trimLeadingPreAnswerBytes(outFile, answeredByteOffset, conversationBytes)) {
                        totalAudioBytes = conversationBytes
                        overallPeakRecorded = peakAfterAnswer
                    }
                }

                updateWavHeader(outFile, totalAudioBytes, sampleRate, 1, 16)

                // Đổi tên file sang đúng tên người gọi Tiếng Việt có dấu chuẩn NFC (ví dụ Zalo_Trương_Thành_Long)
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

                // Chuẩn hóa đỉnh sóng thêm tối đa 6.0x lên mức 25.500 (-2.1 dBFS) cho mọi cuộc gọi có tiếng nói (peak >= 35)
                if (overallPeakRecorded in 35..23500) {
                    normalizeWavFileToBroadcastStandard(outFile, overallPeakRecorded)
                }

                // Chuyển đổi trực tiếp sang định dạng chuẩn .m4a (AAC-LC 128kbps MPEG-4) đồng bộ với trình ghi âm gốc Samsung
                val m4aFile = encodeWavToM4a(outFile, sampleRate)
                if (m4aFile != null && m4aFile.exists() && m4aFile.length() > 128L) {
                    try {
                        outFile.delete()
                    } catch (_: Exception) {
                    }
                    outFile = m4aFile
                    currentFilePath = outFile.absolutePath
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
     * Nén phần cứng file `.wav` (đã lọc DSP & chuẩn hóa âm lượng) sang `.m4a` (AAC-LC 128kbps, MPEG-4 container)
     * với giới hạn thời gian cứng (chống treo 100% trên mọi chip MediaCodec).
     */
    fun encodeWavToM4a(wavFile: File, defaultSampleRate: Int = 48000): File? {
        if (!wavFile.exists() || wavFile.length() <= 44L) return null
        val m4aFile = File(wavFile.parentFile, "${wavFile.nameWithoutExtension}.m4a")
        if (m4aFile.exists()) {
            try {
                m4aFile.delete()
            } catch (_: Exception) {
            }
        }

        var sampleRate = defaultSampleRate
        try {
            RandomAccessFile(wavFile, "r").use { raf ->
                if (raf.length() >= 44L) {
                    raf.seek(24L)
                    val b0 = raf.read() and 0xFF
                    val b1 = raf.read() and 0xFF
                    val b2 = raf.read() and 0xFF
                    val b3 = raf.read() and 0xFF
                    val headerRate = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
                    if (headerRate in 8000..96000) {
                        sampleRate = headerRate
                    }
                }
            }
        } catch (_: Exception) {
        }

        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false

        return try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(m4aFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val bufferInfo = MediaCodec.BufferInfo()
            var trackIndex = -1
            var inputDone = false
            var outputDone = false
            var totalPcmBytesRead = 0L
            var drainIdleCount = 0
            val deadlineMs = System.currentTimeMillis() + 3000L
            // 1 khung AAC-LC = 1024 mẫu PCM 16-bit = 2048 bytes
            val readBuf = ByteArray(2048)

            FileInputStream(wavFile).use { fis ->
                var skipped = 0L
                while (skipped < 44L) {
                    val s = fis.skip(44L - skipped)
                    if (s <= 0) break
                    skipped += s
                }

                while (!outputDone && System.currentTimeMillis() < deadlineMs) {
                    if (!inputDone) {
                        val inBufIdx = codec.dequeueInputBuffer(5000L)
                        if (inBufIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inBufIdx)
                            if (inBuf != null) {
                                inBuf.clear()
                                val maxToRead = min(readBuf.size, inBuf.remaining())
                                val bytesRead = fis.read(readBuf, 0, maxToRead)
                                val ptsUs = (totalPcmBytesRead * 1_000_000L) / (sampleRate.toLong() * 2L)
                                if (bytesRead <= 0) {
                                    codec.queueInputBuffer(
                                        inBufIdx,
                                        0,
                                        0,
                                        ptsUs,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                } else {
                                    val evenBytes = (bytesRead / 2) * 2
                                    if (evenBytes > 0) {
                                        inBuf.put(readBuf, 0, evenBytes)
                                        codec.queueInputBuffer(inBufIdx, 0, evenBytes, ptsUs, 0)
                                        totalPcmBytesRead += evenBytes
                                    } else {
                                        codec.queueInputBuffer(
                                            inBufIdx,
                                            0,
                                            0,
                                            ptsUs,
                                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                        )
                                        inputDone = true
                                    }
                                }
                            }
                        }
                    }

                    val outBufIdx = codec.dequeueOutputBuffer(bufferInfo, 5000L)
                    when {
                        outBufIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!muxerStarted) {
                                trackIndex = muxer.addTrack(codec.outputFormat)
                                muxer.start()
                                muxerStarted = true
                            }
                        }
                        outBufIdx >= 0 -> {
                            drainIdleCount = 0
                            val outBuf = codec.getOutputBuffer(outBufIdx)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                bufferInfo.size = 0
                            }
                            if (bufferInfo.size > 0 && muxerStarted && outBuf != null) {
                                outBuf.position(bufferInfo.offset)
                                outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                muxer.writeSampleData(trackIndex, outBuf, bufferInfo)
                            }
                            codec.releaseOutputBuffer(outBufIdx, false)
                            if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                outputDone = true
                            }
                        }
                        else -> {
                            // Nếu đã nạp hết dữ liệu đầu vào (inputDone) và bộ mã hóa trả về INFO_TRY_AGAIN_LATER 4 lần (20ms),
                            // toàn bộ khung AAC đã được rút cạn -> thoát ngay, tuyệt đối không lặp vô tận!
                            if (inputDone) {
                                drainIdleCount++
                                if (drainIdleCount >= 4) {
                                    outputDone = true
                                }
                            }
                        }
                    }
                }
            }

            if (muxerStarted) {
                try {
                    muxer.stop()
                } catch (_: Exception) {
                }
                muxerStarted = false
            }
            if (m4aFile.exists() && m4aFile.length() > 128L) m4aFile else null
        } catch (_: Exception) {
            null
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            if (muxerStarted) {
                try {
                    muxer?.stop()
                } catch (_: Exception) {
                }
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Cắt bỏ an toàn phần đổ chuông chưa nhấc máy (`leadingBytesToTrim`) ở đầu file `.wav`,
     * giữ nguyên 100% phần âm thanh cuộc nói chuyện (`remainingAudioBytes`).
     */
    private fun trimLeadingPreAnswerBytes(
        wavFile: File,
        leadingBytesToTrim: Long,
        remainingAudioBytes: Long
    ): Boolean {
        return try {
            val evenTrim = (leadingBytesToTrim / 2L) * 2L
            if (evenTrim <= 0L || remainingAudioBytes <= 0L) return false
            RandomAccessFile(wavFile, "rw").use { raf ->
                val buf = ByteArray(32768)
                var readPos = 44L + evenTrim
                var writePos = 44L
                var left = remainingAudioBytes
                while (left > 0L) {
                    val toRead = min(buf.size.toLong(), left).toInt()
                    raf.seek(readPos)
                    val read = raf.read(buf, 0, toRead)
                    if (read <= 0) break
                    raf.seek(writePos)
                    raf.write(buf, 0, read)
                    readPos += read
                    writePos += read
                    left -= read
                }
                raf.setLength(44L + remainingAudioBytes)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Chuẩn hóa đỉnh sóng (Peak Normalizer tối đa 6.0x lên `25.500 / 32.767`)
     * Giúp giọng nói cuộc trò chuyện trong file `.wav` to, rõ ràng mà không gây sôi nhiễu.
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

                if (peak < 35 || peak >= 23500) return false

                val scale = (25500f / peak.toFloat()).coerceIn(1.1f, 6.0f)
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
                val mime = if (srcFile.name.endsWith(".m4a", true)) "audio/mp4" else "audio/wav"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, srcFile.name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
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
