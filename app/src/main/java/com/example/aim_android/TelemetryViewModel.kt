package com.example.aim_android

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

class TelemetryViewModel : ViewModel() {

    private val recorder = AudioTelemetryManager()

    var isRecording by mutableStateOf(false)
        private set

    var latestTelemetry by mutableStateOf<TelemetryDetails?>(null)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun startStreaming() {
        isRecording = true
        errorMessage = null

        recorder.startRecording { pcmBytes ->
            sendAudioChunkToBackend(pcmBytes)
        }
    }

    fun stopStreaming() {
        isRecording = false
        recorder.stopRecording()
    }

    private fun sendAudioChunkToBackend(bytes: ByteArray) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Wrap raw PCM bytes into a Multipart body part
                val requestFile = bytes.toRequestBody("audio/x-raw".toMediaTypeOrNull())
                val body = MultipartBody.Part.createFormData("file", "chunk.pcm", requestFile)

                val response = NetworkClient.apiService.uploadAudioChunk(body)

                if (response.isSuccessful) {
                    val responseBody = response.body()
                    if (responseBody?.status == "success") {
                        latestTelemetry = responseBody.telemetry
                    }
                } else {
                    errorMessage = "Server Error: ${response.code()}"
                }
            } catch (e: Exception) {
                errorMessage = "Network Exception: ${e.localizedMessage}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        recorder.stopRecording()
    }
}