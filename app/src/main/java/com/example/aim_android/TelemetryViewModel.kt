package com.example.aim_android

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.aim_android.audio.OnDeviceWhisperEngine
import com.example.aim_android.data.AimDatabase
import com.example.aim_android.data.SessionEntity
import com.example.aim_android.data.TelemetrySampleEntity
import com.example.aim_android.data.TranscriptEntity
import com.example.aim_android.models.EyeMetrics
import com.example.aim_android.models.HeadPose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.synchronized

class TelemetryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AimDatabase.getDatabase(application)
    private val dao = db.telemetryDao()
    private val recorder = AudioTelemetryManager()
    private val whisperEngine by lazy {
        OnDeviceWhisperEngine(application.applicationContext)
    }

    // Live observable state for UI
    var latestEngagementMetrics by mutableStateOf(com.example.aim_android.model.EngagementMetrics())
        private set

    // Math smoothing variables
    private var smoothedVerbalScore = 0.0f
    private val decayLambda = 0.15f // Determines how quickly verbal score drops when silent
    private var totalWordsInSession = 0
    private val recordedScores = mutableListOf<Float>()

    private var currentSessionId: Long? = null
    private var currentVisionScore: Float = 0f
    private var currentEyeMetrics: EyeMetrics? = null
    private var currentHeadPose: HeadPose? = null
    private var isFaceDetected: Boolean = false

    var isRecording by mutableStateOf(false)
        private set

    var latestTelemetry by mutableStateOf<TelemetryDetails?>(null)
        private set

    var latestTranscript by mutableStateOf("")
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    // Buffer to accumulate active speech samples for Whisper decoding
    private val speechAccumulator = ArrayList<Float>()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Force Room database file creation on disk immediately for Database Inspector
                db.openHelper.writableDatabase
                Log.d("AIM_DB", "Database initialized on disk successfully.")
            } catch (e: Exception) {
                Log.e("AIM_DB", "Database initialization error: ${e.localizedMessage}", e)
            }
        }
    }

    fun updateVisionMetrics(
        visionScore: Float,
        eyeMetrics: EyeMetrics? = null,
        headPose: HeadPose? = null,
        faceDetected: Boolean = true
    ) {
        currentVisionScore = visionScore
        currentEyeMetrics = eyeMetrics
        currentHeadPose = headPose
        isFaceDetected = faceDetected
    }

    fun startStreaming() {
        smoothedVerbalScore = 0f
        totalWordsInSession = 0
        recordedScores.clear()

        if (isRecording) return
        errorMessage = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newSession = SessionEntity(startTime = System.currentTimeMillis())
                val id = dao.insertSession(newSession)
                currentSessionId = id
                Log.d("AIM_DB", "SESSION STARTED -> ID: $id")
            } catch (e: Exception) {
                Log.e("AIM_DB", "Failed to insert session", e)
            }
        }

        try {
            recorder.startRecording { pcmBytes ->
                processAudioChunkLocally(pcmBytes)
            }
            isRecording = true
        } catch (e: SecurityException) {
            Log.e("AIM_AUDIO", "SecurityException starting AudioRecord", e)
            isRecording = false
            errorMessage = "Microphone permission required: ${e.localizedMessage}"
        } catch (e: IllegalStateException) {
            Log.e("AIM_AUDIO", "IllegalStateException starting AudioRecord", e)
            isRecording = false
            errorMessage = "Audio hardware error: ${e.localizedMessage}"
        } catch (e: Exception) {
            Log.e("AIM_AUDIO", "Exception starting audio recording", e)
            isRecording = false
            errorMessage = "Audio error: ${e.localizedMessage}"
        }
    }
    private var lastSavedText = ""
    fun stopStreaming() {

        synchronized(bufferLock) {
            speechAccumulator.clear()
        }
        lastSavedText = ""
        isRecording = false
        try {
            recorder.stopRecording()
        } catch (e: Exception) {
            Log.e("AIM_AUDIO", "Error stopping audio recorder", e)
        }
        val sessionId = currentSessionId
        if (sessionId != null) {
            val avgScore = if (recordedScores.isNotEmpty()) recordedScores.average().toFloat() else 0f
            val words = totalWordsInSession

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val finalizedSession = SessionEntity(
                        sessionId = sessionId,
                        startTime = System.currentTimeMillis() - (recordedScores.size * 1000L),
                        endTime = System.currentTimeMillis(),
                        averageAttentionScore = avgScore,
                        totalWordsSpoken = words
                    )
                    dao.insertSession(finalizedSession)
                    Log.d("AIM_DB", "SESSION FINALIZED: ID $sessionId | Avg: $avgScore | Words: $words")
                } catch (e: Exception) {
                    Log.e("AIM_DB", "Error finalizing session $sessionId", e)
                }
            }
        }
        currentSessionId = null
    }

    // In TelemetryViewModel.kt

    // Buffer to hold raw PCM samples (16kHz)
    private val MIN_CHUNK_SAMPLES = (16000 * 2.5).toInt() // 2.5s window
    private val bufferLock = Any()
    private var isSessionActive = false // Guard against late background inserts

    fun onAudioBufferReceived(floatSamples: FloatArray, rms: Float) {
        if (!isSessionActive) return

        synchronized(bufferLock) {
            // Collect voice audio
            if (rms > 0.018f) {
                for (sample in floatSamples) {
                    speechAccumulator.add(sample)
                }
            }

            // Only dispatch when we have accumulated a full 2.5 seconds of voice
            if (speechAccumulator.size >= MIN_CHUNK_SAMPLES) {
                val chunkToProcess = speechAccumulator.toFloatArray()
                speechAccumulator.clear()
                processAudioChunk(chunkToProcess)
            }
        }
    }

    private fun processAudioChunk(audioSamples: FloatArray) {
        viewModelScope.launch(Dispatchers.Default) {
            val rawText = whisperEngine.transcribe(audioSamples)
            val cleanedText = rawText
                .replace(Regex("\\[.*?\\]"), "")
                .replace(Regex("\\(.*?\\)"), "")
                .trim()

            if (cleanedText.isBlank()) return@launch

            // Deduplicate overlap with previous phrase
            val previousWords = lastSavedText.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }
            val currentWords = cleanedText.lowercase().split("\\s+".toRegex()).filter { it.isNotBlank() }

            var overlapCount = 0
            val maxCheck = minOf(previousWords.size, currentWords.size)
            for (i in maxCheck downTo 1) {
                if (previousWords.takeLast(i) == currentWords.take(i)) {
                    overlapCount = i
                    break
                }
            }

            val newWordsList = currentWords.drop(overlapCount)
            if (newWordsList.isEmpty()) return@launch

            val textToSave = newWordsList.joinToString(" ")
            lastSavedText = cleanedText
            val wordCount = newWordsList.size

            totalWordsInSession += wordCount

            withContext(Dispatchers.Main) {
                latestTranscript = textToSave
            }

            currentSessionId?.let { id ->
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        dao.insertTranscript(
                            TranscriptEntity(
                                sessionId = id,
                                timestamp = System.currentTimeMillis(),
                                text = textToSave,
                                wordCount = wordCount
                            )
                        )
                        Log.d("AIM_DB", "SAVED TRANSCRIPT to Session $id: $textToSave")
                    } catch (e: Exception) {
                        Log.e("AIM_DB", "Failed to insert transcript", e)
                    }
                }
            }
        }
    }

    private fun processAudioChunkLocally(bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                // 1. Convert 16-bit PCM ByteArray into FloatArray [-1.0f, 1.0f]
                val floatSamples = pcmBytesToFloats(bytes)
                if (floatSamples.isEmpty()) return@launch

                // 2. Compute On-Device Audio Features
                val rms = calculateRms(floatSamples)
                val zcr = calculateZcr(floatSamples)
                val centroid = calculateSpectralCentroid(floatSamples)

                val instantVerbal = if (rms > 0.005f) 100f else 0f
                smoothedVerbalScore = (smoothedVerbalScore * kotlin.math.exp(-decayLambda)) +
                        (instantVerbal * (1f - kotlin.math.exp(-decayLambda)))

                val cei = ((currentVisionScore * 0.6f) + (smoothedVerbalScore * 0.4f)).coerceIn(0f, 100f)
                recordedScores.add(currentVisionScore)

                val level = when {
                    cei >= 75f -> com.example.aim_android.model.EngagementLevel.HIGH
                    cei >= 50f -> com.example.aim_android.model.EngagementLevel.MODERATE
                    cei >= 25f -> com.example.aim_android.model.EngagementLevel.LOW
                    else -> com.example.aim_android.model.EngagementLevel.DISENGAGED
                }

                val telemetry = TelemetryDetails(
                    sample_count = floatSamples.size,
                    rms_amplitude = rms,
                    zero_crossing_rate = zcr,
                    spectral_centroid_hz = centroid
                )

                withContext(Dispatchers.Main) {
                    latestTelemetry = telemetry
                    latestEngagementMetrics = com.example.aim_android.model.EngagementMetrics(
                        visualAttentionScore = currentVisionScore,
                        verbalEngagementScore = smoothedVerbalScore,
                        combinedEngagementIndex = cei,
                        engagementLevel = level,
                        wordsSpokenCount = totalWordsInSession
                    )
                }

                // Insert 1 Hz Telemetry Sample into Room database if recording session active
                val sessionId = currentSessionId
                if (sessionId != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val sampleEntity = TelemetrySampleEntity(
                                sessionId = sessionId,
                                timestamp = System.currentTimeMillis(),
                                attentionScore = currentVisionScore,
                                isGazeAverted = currentEyeMetrics?.isGazeAverted ?: false,
                                isHeadTurned = currentHeadPose?.isHeadTurned ?: false,
                                isBlinking = currentEyeMetrics?.isBlinking ?: false,
                                rmsAmplitude = rms,
                                spectralCentroidHz = centroid
                            )
                            dao.insertTelemetrySample(sampleEntity)
                            Log.d("AIM_DB", "SAVED TELEMETRY SAMPLE -> Session $sessionId")
                        } catch (e: Exception) {
                            Log.e("AIM_DB", "Failed to insert telemetry sample", e)
                        }
                    }
                }

                // 3. On-Device Voice Activity Detection & Fixed Window Whisper Transcription
                if (rms > 0.005f) { // Active speech threshold
                    for (sample in floatSamples) {
                        speechAccumulator.add(sample)
                    }
                }

                if (speechAccumulator.size >= 16000) { // Transcribe every 1 second (16k samples)
                    val audioToTranscribe = speechAccumulator.toFloatArray()
                    speechAccumulator.clear()

                    val text = whisperEngine.transcribe(audioToTranscribe)
                    Log.d("AIM_AUDIO", "Transcribed: $text")
                    if (text.isNotBlank()) {
                        val words = text.trim().split("\\s+".toRegex()).size
                        totalWordsInSession += words
                        withContext(Dispatchers.Main) {
                            latestTranscript = text
                        }

                        if (sessionId != null) {
                            viewModelScope.launch(Dispatchers.IO) {
                                try {
                                    val transcriptEntity = TranscriptEntity(
                                        sessionId = sessionId,
                                        timestamp = System.currentTimeMillis(),
                                        text = text,
                                        wordCount = text.trim().split("\\s+".toRegex()).size
                                    )
                                    dao.insertTranscript(transcriptEntity)
                                    Log.d("AIM_DB", "SAVED TRANSCRIPT to Session $sessionId: $text")
                                } catch (e: Exception) {
                                    Log.e("AIM_DB", "Failed to insert transcript", e)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "On-Device Audio Error: ${e.localizedMessage}"
                }
            }
        }
    }

    // Converts 16-bit Little-Endian PCM byte array to normalized float array
    private fun pcmBytesToFloats(bytes: ByteArray): FloatArray {
        val shortBuffer = ByteBuffer.wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()

        val floats = FloatArray(shortBuffer.remaining())
        for (i in floats.indices) {
            floats[i] = shortBuffer.get() / 32768.0f
        }
        return floats
    }

    private fun calculateRms(samples: FloatArray): Float {
        var sum = 0.0f
        for (sample in samples) {
            sum += sample * sample
        }
        return sqrt(sum / samples.size)
    }

    private fun calculateZcr(samples: FloatArray): Float {
        var zeroCrossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0.0f && samples[i - 1] < 0.0f) ||
                (samples[i] < 0.0f && samples[i - 1] >= 0.0f)) {
                zeroCrossings++
            }
        }
        return zeroCrossings.toFloat() / samples.size
    }

    private fun calculateSpectralCentroid(samples: FloatArray): Float {
        // Approximate high-frequency density via sample gradient
        var num = 0.0f
        var den = 0.0001f
        for (i in 1 until samples.size) {
            val freqWeight = i.toFloat()
            val magnitude = abs(samples[i] - samples[i - 1])
            num += freqWeight * magnitude
            den += magnitude
        }
        return ((num / den) * (16000f / samples.size)).coerceIn(100f, 8000f)
    }

    override fun onCleared() {
        super.onCleared()
        recorder.stopRecording()
        whisperEngine.release()
    }
}