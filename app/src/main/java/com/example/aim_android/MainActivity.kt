package com.example.aim_android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val viewModel: TelemetryViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (audioGranted) {
            viewModel.startStreaming()
        } else {
            Toast.makeText(this, "Microphone permission is required for offline Whisper.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MultimodalTelemetryDashboard(
                        viewModel = viewModel,
                        onToggleRecording = {
                            if (viewModel.isRecording) {
                                viewModel.stopStreaming()
                            } else {
                                checkAndStartAudio()
                            }
                        },
                        onEvaluateAnswer = { transcript ->
                            Log.d("AIM_MENTOR", "MainActivity: onEvaluateAnswer triggered with: '$transcript'")
                            viewModel.evaluateCandidateAnswer(transcript)
                        }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun checkAndStartAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startStreaming()
        } else {
            permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
        }
    }
}

@Composable
fun MultimodalTelemetryDashboard(
    viewModel: TelemetryViewModel,
    onToggleRecording: () -> Unit,
    onEvaluateAnswer: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val hasCameraPermission = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    Box(modifier = Modifier.fillMaxSize()) {
        // --- 1. Background CameraX Feed ---
        if (hasCameraPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val analyzer = AimVisionAnalyzer(
                            context = ctx,
                            onMetricsCalculated = { eye, head, score ->
                                // Delegate exclusively to the ViewModel to prevent 30fps recomposition jitter
                                viewModel.updateVisionMetrics(
                                    visionScore = score,
                                    eyeMetrics = eye,
                                    headPose = head,
                                    faceDetected = true
                                )
                            },
                            onError = { err ->
                                Log.e("AIM_VISION", err)
                            }
                        )

                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                            .build()

                        imageAnalysis.setAnalyzer(
                            ContextCompat.getMainExecutor(ctx),
                            analyzer
                        )

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_FRONT_CAMERA,
                                preview,
                                imageAnalysis
                            )
                        } catch (e: Exception) {
                            Log.e("AIM_CAMERA", "Binding failed", e)
                        }
                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text("Camera permission required for vision telemetry", color = Color.White)
            }
        }

        // --- 2. Live Multimodal HUD Overlay ---
        val engagement = viewModel.latestEngagementMetrics
        val attentionScore = engagement.visualAttentionScore

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Top Card: Vision Telemetry
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xDD121212))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Composite Attention",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            "${attentionScore.toInt()}%",
                            color = if (attentionScore > 65f) Color(0xFF4CAF50) else Color(0xFFFF5252),
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    LinearProgressIndicator(
                        progress = { (attentionScore / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (attentionScore > 65f) Color(0xFF4CAF50) else Color(0xFFFF5252),
                        trackColor = Color(0xFF333333)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        MetricBadge("Level", true, engagement.engagementLevel.name, "LOW")
                        MetricBadge("Verbal", engagement.verbalEngagementScore > 30f, "Active", "Quiet")
                        MetricBadge("CEI", engagement.combinedEngagementIndex > 50f, "${engagement.combinedEngagementIndex.toInt()}%", "Low")
                    }
                }
            }

            // Middle Card: AI Interview Mentor Evaluation (Llama-3.2 GGUF)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xDD1A1A2E))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "AI Mentor Evaluation",
                            color = Color(0xFF90CAF9),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (viewModel.isModelReady) "Q4 Model Ready" else "Model Missing",
                            color = if (viewModel.isModelReady) Color(0xFF81C784) else Color(0xFFFF8A80),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "Q: ${viewModel.currentQuestion}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    if (viewModel.isEvaluating) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color(0xFF90CAF9),
                                strokeWidth = 2.dp
                            )
                            Text(
                                "Evaluating response on-device...",
                                color = Color.LightGray,
                                fontSize = 12.sp
                            )
                        }
                    } else if (viewModel.mentorFeedback != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0x55000000),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = viewModel.mentorFeedback ?: "",
                                color = Color(0xFFE0E0E0),
                                fontSize = 13.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Button(
                        onClick = {
                            Log.d("AIM_MENTOR", "Button tapped directly! latestTranscript='${viewModel.latestTranscript}'")
                            onEvaluateAnswer(viewModel.latestTranscript)
                        },
                        enabled = !viewModel.isEvaluating && viewModel.isModelReady,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3F51B5)
                        )
                    ) {
                        Text("Evaluate Spoken Answer", fontSize = 13.sp)
                    }
                }
            }

            // Bottom Section: Audio Telemetry & Stream Control
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xDD121212))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Audio Telemetry Engine",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    val audioMetrics = viewModel.latestTelemetry

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("RMS Amplitude", color = Color.Gray, fontSize = 11.sp)
                            Text(
                                "${audioMetrics?.rms_amplitude ?: "0.0"}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Column {
                            Text("Zero Crossing", color = Color.Gray, fontSize = 11.sp)
                            Text(
                                "${audioMetrics?.zero_crossing_rate ?: "0.0"}",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Column {
                            Text("Spectral Centroid", color = Color.Gray, fontSize = 11.sp)
                            Text(
                                "${audioMetrics?.spectral_centroid_hz ?: "0.0"} Hz",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Text(
                        text = "Transcript: ${viewModel.latestTranscript.ifEmpty { "Listening..." }}",
                        color = Color.Green,
                        fontSize = 14.sp
                    )

                    viewModel.errorMessage?.let { err ->
                        Text(text = err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Button(
                        onClick = onToggleRecording,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (viewModel.isRecording) Color(0xFFD32F2F) else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (viewModel.isRecording) "Stop Audio Stream" else "Start Audio Stream")
                    }
                }
            }
        }
    }
}

@Composable
fun MetricBadge(label: String, active: Boolean, activeText: String, inactiveText: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color.LightGray, fontSize = 11.sp)
        Text(
            if (active) activeText else inactiveText,
            color = if (active) Color(0xFF81C784) else Color(0xFFFFB74D),
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp
        )
    }
}
