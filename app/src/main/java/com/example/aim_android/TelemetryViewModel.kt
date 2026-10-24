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
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class TelemetryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AimDatabase.getDatabase(application)
    private val dao = db.telemetryDao()
    private val recorder = AudioTelemetryManager()

    private val whisperEngine by lazy {
        OnDeviceWhisperEngine(application.applicationContext)
    }

    // --- Observable UI States ---
    var latestEngagementMetrics by mutableStateOf(com.example.aim_android.model.EngagementMetrics())
        private set

    var isRecording by mutableStateOf(false)
        private set

    var latestTelemetry by mutableStateOf<TelemetryDetails?>(null)
        private set

    var latestTranscript by mutableStateOf("")
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    // --- Offline Llama GGUF Mentor State ---
    var currentQuestion by mutableStateOf("What is a deadlock in OS?")
        private set

    var mentorFeedback by mutableStateOf<String?>(null)
        private set

    var isEvaluating by mutableStateOf(false)
        private set

    var isModelReady by mutableStateOf(false)
        private set

    // Math smoothing variables
    private var smoothedVerbalScore = 0.0f
    private val decayLambda = 0.15f
    private var totalWordsInSession = 0
    private val recordedScores = mutableListOf<Float>()

    private var currentSessionId: Long? = null
    private var currentVisionScore: Float = 0f
    private var currentEyeMetrics: EyeMetrics? = null
    private var currentHeadPose: HeadPose? = null
    private var isFaceDetected: Boolean = false

    // Audio Accumulation & VAD Buffer (16 kHz, 1.0s window = 16,000 samples)
    private val speechAccumulator = ArrayList<Float>()
    private val bufferLock = Any()
    private val targetChunkSamples = 16000
    private var silenceFrames = 0
    private var lastSavedText = ""

    // LLaMA native wrapper instance
    private var llamaInstance: LlamaInstance? = null

    init {
        // Initialize Room DB schema
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.openHelper.writableDatabase
                Log.d("AIM_DB", "Database initialized on disk successfully.")
            } catch (e: Exception) {
                Log.e("AIM_DB", "Database initialization error: ${e.localizedMessage}", e)
            }
        }

        // Verify model and preload weights once into RAM
        checkAndPreloadModel()
    }

    private fun checkAndPreloadModel() {
        val modelFile = File(getApplication<Application>().getExternalFilesDir(null), "aim_mentor_q4.gguf")
        isModelReady = modelFile.exists() && modelFile.canRead()
        Log.d("AIM_MENTOR", "Llama GGUF Model Available: $isModelReady at ${modelFile.absolutePath}")

        if (isModelReady) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    if (llamaInstance == null) {
                        Log.d("AIM_MENTOR", "Pre-warming LlamaInstance into memory...")
                        llamaInstance = LlamaInstance(modelFile.absolutePath)
                        Log.d("AIM_MENTOR", "LlamaInstance preloaded successfully.")
                    }
                } catch (e: Throwable) {
                    Log.e("AIM_MENTOR", "Failed to preload model: ${e.localizedMessage}", e)
                }
            }
        }
    }

    private suspend fun runInferenceBridge(
        modelPath: String,
        question: String,
        candidateAnswer: String
    ): String = withContext(Dispatchers.IO) {
        try {
            if (llamaInstance == null) {
                Log.d("AIM_MENTOR", "Initializing LlamaInstance at: $modelPath")
                llamaInstance = LlamaInstance(modelPath)
            }

            val llama = llamaInstance ?: throw IllegalStateException("LlamaInstance failed to load")

            val systemPrompt = """
                You are a strict, objective technical interviewer. 
                Grade the candidate's answer to the given question on a scale of 0 to 100.
                
                Grading Rubric:
                - 0-20: Admitting lack of knowledge (e.g., "I don't know"), irrelevant, or blank answers.
                - 21-50: Partially correct or missing crucial core concepts.
                - 51-80: Conceptually correct but missing technical depth or edge cases.
                - 81-100: Thorough, technically precise, and complete.

                Output strictly in this format:
                Score: <number>/100
                Feedback: <1-2 sentences explaining why the score was given>
            """.trimIndent()

            val userPrompt = """
                Question: $question
                Candidate Answer: "$candidateAnswer"
            """.trimIndent()

            val formattedPrompt = "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n$systemPrompt<|eot_id|><|start_header_id|>user<|end_header_id|>\n\n$userPrompt<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n"
            Log.d("AIM_MENTOR", "Dispatching formatted prompt to native inference...")

            val rawOutput = llama.generateEvaluation(formattedPrompt, maxTokens = 40)
            Log.d("AIM_MENTOR", "Native generateEvaluation returned output length: ${rawOutput.length}")

            rawOutput
                .replace("<|eot_id|>", "")
                .replace("<|start_header_id|>assistant<|end_header_id|>", "")
                .replace("<|end_of_text|>", "")
                .trim()
                .ifBlank { "Score: 0/100\nFeedback: No evaluation returned from model." }
        } catch (e: Throwable) {
            Log.e("AIM_MENTOR", "Llama inference error", e)
            "Score: 0/100\nFeedback: Model execution error: ${e.localizedMessage}"
        }
    }

    @Suppress("unused")
    fun setInterviewQuestion(question: String) {
        currentQuestion = question
        mentorFeedback = null
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

        smoothedVerbalScore = 0f
        totalWordsInSession = 0
        recordedScores.clear()
        lastSavedText = ""
        latestTranscript = ""
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
        } catch (e: Exception) {
            Log.e("AIM_AUDIO", "Exception starting audio recording", e)
            isRecording = false
            errorMessage = "Audio error: ${e.localizedMessage}"
        }
    }

    fun stopStreaming() {
        isRecording = false

        // Flush any pending audio before stopping
        synchronized(bufferLock) {
            if (speechAccumulator.isNotEmpty()) {
                val remainingAudio = speechAccumulator.toFloatArray()
                speechAccumulator.clear()
                silenceFrames = 0
                if (remainingAudio.size >= 8000) {
                    dispatchTranscription(remainingAudio)
                }
            }
        }
        lastSavedText = ""

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

    private fun processAudioChunkLocally(bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val floatSamples = pcmBytesToFloats(bytes)
                if (floatSamples.isEmpty()) return@launch

                val rms = calculateRms(floatSamples)
                val zcr = calculateZcr(floatSamples)
                val centroid = calculateSpectralCentroid(floatSamples)

                val instantVerbal = if (rms > 0.005f) 100f else 0f
                val decay = exp(-decayLambda)
                smoothedVerbalScore = (smoothedVerbalScore * decay) + (instantVerbal * (1f - decay))

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
                        } catch (e: Exception) {
                            Log.e("AIM_DB", "Failed to insert telemetry sample", e)
                        }
                    }
                }

                // Voice Activity Accumulation for Whisper
                synchronized(bufferLock) {
                    if (rms > 0.004f) {
                        for (sample in floatSamples) {
                            speechAccumulator.add(sample)
                        }
                        silenceFrames = 0
                    } else if (speechAccumulator.isNotEmpty()) {
                        silenceFrames++
                    }

                    val shouldFlush = speechAccumulator.size >= targetChunkSamples ||
                            (speechAccumulator.size >= 8000 && silenceFrames >= 2)

                    if (shouldFlush) {
                        val audioToTranscribe = speechAccumulator.toFloatArray()
                        speechAccumulator.clear()
                        silenceFrames = 0
                        dispatchTranscription(audioToTranscribe)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = "Audio Error: ${e.localizedMessage}"
                }
            }
        }
    }

    private fun dispatchTranscription(audioSamples: FloatArray) {
        Log.d("AIM_WHISPER", "dispatchTranscription called with ${audioSamples.size} samples")
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val rawText = whisperEngine.transcribe(audioSamples).trim()
                Log.d("AIM_WHISPER", "Whisper raw output: '$rawText'")
                if (rawText.isBlank()) return@launch

                val cleaned = rawText
                    .replace(Regex("\\[.*?\\]"), "")
                    .replace(Regex("\\(.*?\\)"), "")
                    .replace(Regex("\\.{2,}"), ".")
                    .trim()

                val words = cleaned.split("\\s+".toRegex()).filter { it.isNotBlank() }
                if (words.size > 4) {
                    val half = words.size / 2
                    val firstHalf = words.subList(0, half).joinToString(" ")
                    val secondHalf = words.subList(half, words.size).joinToString(" ")
                    if (firstHalf.equals(secondHalf, ignoreCase = true)) {
                        return@launch
                    }
                }

                if (lastSavedText.contains(cleaned, ignoreCase = true) || cleaned.contains(lastSavedText, ignoreCase = true)) {
                    if (cleaned.length <= lastSavedText.length) return@launch
                }

                lastSavedText = cleaned
                val wordCount = words.size
                totalWordsInSession += wordCount

                withContext(Dispatchers.Main) {
                    if (latestTranscript.isBlank()) {
                        latestTranscript = cleaned
                    } else if (!latestTranscript.endsWith(cleaned, ignoreCase = true)) {
                        latestTranscript = "$latestTranscript $cleaned"
                    }
                }

                currentSessionId?.let { sId ->
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            dao.insertTranscript(
                                TranscriptEntity(
                                    sessionId = sId,
                                    timestamp = System.currentTimeMillis(),
                                    text = cleaned,
                                    wordCount = wordCount
                                )
                            )
                        } catch (e: Exception) {
                            Log.e("AIM_DB", "Failed to insert transcript", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AIM_WHISPER", "Transcription failure", e)
            }
        }
    }

    // --- On-Device Llama GGUF Evaluation Trigger ---
    fun evaluateCandidateAnswer(customAnswer: String? = null) {
        val answerToEvaluate = (customAnswer ?: latestTranscript).trim()
        if (answerToEvaluate.isBlank()) {
            errorMessage = "No answer transcribed to evaluate yet. Speak your response first."
            return
        }

        val modelFile = File(getApplication<Application>().getExternalFilesDir(null), "aim_mentor_q4.gguf")
        if (!modelFile.exists() || !modelFile.canRead()) {
            errorMessage = "aim_mentor_q4.gguf not found at ${modelFile.absolutePath}"
            return
        }

        isEvaluating = true
        errorMessage = null

        viewModelScope.launch(Dispatchers.Default) {
            try {
                Log.d("AIM_MENTOR", "Evaluating answer: \"$answerToEvaluate\" for question: \"$currentQuestion\"")
                val result = runInferenceBridge(modelFile.absolutePath, currentQuestion, answerToEvaluate)
                Log.d("AIM_MENTOR", "Inference bridge returned result: $result")

                withContext(Dispatchers.Main) {
                    mentorFeedback = result
                }
            } catch (e: Throwable) {
                Log.e("AIM_MENTOR", "Evaluation coroutine error", e)
                withContext(Dispatchers.Main) {
                    errorMessage = "Evaluation failed: ${e.localizedMessage}"
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isEvaluating = false
                }
            }
        }
    }

    // --- Feature Extraction Math ---
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
        llamaInstance?.release()
        llamaInstance = null
    }
}
