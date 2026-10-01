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

class TelemetryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AimDatabase.getDatabase(application)
    private val dao = db.telemetryDao()
    private val recorder = AudioTelemetryManager()
    private val whisperEngine by lazy {
        OnDeviceWhisperEngine(application.applicationContext)
    }

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

    fun stopStreaming() {
        isRecording = false
        try {
            recorder.stopRecording()
        } catch (e: Exception) {
            Log.e("AIM_AUDIO", "Error stopping audio recorder", e)
        }
        currentSessionId = null
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

                val telemetry = TelemetryDetails(
                    sample_count = floatSamples.size,
                    rms_amplitude = rms,
                    zero_crossing_rate = zcr,
                    spectral_centroid_hz = centroid
                )

                withContext(Dispatchers.Main) {
                    latestTelemetry = telemetry
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