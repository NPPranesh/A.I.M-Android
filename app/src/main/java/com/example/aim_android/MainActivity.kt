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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.example.aim_android.models.EyeMetrics
import com.example.aim_android.models.HeadPose
import com.example.aim_android.AimVisionAnalyzer

class MainActivity : ComponentActivity() {

    private val viewModel: TelemetryViewModel by viewModels()

    // Multiple permissions launcher: Camera + Audio
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (audioGranted) {
            viewModel.startStreaming()
        } else {
            Toast.makeText(this, "Microphone permission is required to run offline Whisper.", Toast.LENGTH_SHORT).show()
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
    onToggleRecording: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Vision Telemetry State
    var attentionScore by remember { mutableFloatStateOf(0.0f) }
    var eyeMetrics by remember { mutableStateOf<EyeMetrics?>(null) }
    var headPose by remember { mutableStateOf<HeadPose?>(null) }

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
                                attentionScore = score
                                eyeMetrics = eye
                                headPose = head
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
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
                        MetricBadge("Gaze", eyeMetrics?.isGazeFocused == true, "Focused", "Averted")
                        MetricBadge("Blink", eyeMetrics?.isBlinking == true, "Blinking", "Open")
                        MetricBadge("Head", headPose?.isFacingScreen == true, "Centered", "Turned")
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

                    // Live Whisper Transcript Display
                    Text(
                        text = "Transcript: ${viewModel.latestTranscript.ifEmpty { "Listening..." }}",
                        color = Color.Green,
                        fontSize = 14.sp
                    )

                    viewModel.errorMessage?.let { err ->
                        Text(text = err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }

                    Spacer(modifier = Modifier.height(6.dp))

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