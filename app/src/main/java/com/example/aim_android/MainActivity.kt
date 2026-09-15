package com.example.aim_android

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val viewModel: TelemetryViewModel by viewModels()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startStreaming()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TelemetryDashboard(
                        viewModel = viewModel,
                        onToggleRecording = {
                            if (viewModel.isRecording) {
                                viewModel.stopStreaming()
                            } else {
                                checkAndStart()
                            }
                        }
                    )
                }
            }
        }
    }

    private fun checkAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            viewModel.startStreaming()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

@Composable
fun TelemetryDashboard(
    viewModel: TelemetryViewModel,
    onToggleRecording: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Audio Telemetry Engine",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Live Telemetry Readout
        val metrics = viewModel.latestTelemetry
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("RMS Amplitude: ${metrics?.rms_amplitude ?: "0.0"}")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Zero Crossing Rate: ${metrics?.zero_crossing_rate ?: "0.0"}")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Spectral Centroid: ${metrics?.spectral_centroid_hz ?: "0.0"} Hz")
            }
        }

        viewModel.errorMessage?.let { err ->
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = err, color = MaterialTheme.colorScheme.error)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onToggleRecording,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Text(if (viewModel.isRecording) "Stop Telemetry Stream" else "Start Telemetry Stream")
        }
    }
}