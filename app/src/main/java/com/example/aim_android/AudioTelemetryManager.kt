package com.example.aim_android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

class AudioTelemetryManager {
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT

    // 3 Seconds Window = 96,000 bytes (for Whisper context)
    private val windowSizeBytes = 96000
    // Step size = 1 Second = 32,000 bytes (send frequency)
    private val stepSizeBytes = 32000

    private val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    private val bufferSize = maxOf(minBufferSize, 4096)

    private var isRecording = false
    private var audioRecord: AudioRecord? = null

    @SuppressLint("MissingPermission")
    fun startRecording(onChunkCaptured: (ByteArray) -> Unit) {
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate, channelConfig, audioFormat, bufferSize
        )
        audioRecord?.startRecording()
        isRecording = true

        Thread {
            val readBuffer = ByteArray(bufferSize)
            val rollingStream = ByteArrayOutputStream()
            var unpostedBytes = 0

            while (isRecording) {
                val bytesRead = audioRecord?.read(readBuffer, 0, bufferSize) ?: 0
                if (bytesRead > 0) {
                    rollingStream.write(readBuffer, 0, bytesRead)
                    unpostedBytes += bytesRead

                    // Dispatch every time 1 second of NEW audio arrives
                    if (unpostedBytes >= stepSizeBytes) {
                        val fullStreamBytes = rollingStream.toByteArray()

                        // Grab up to the last 3 seconds of continuous audio
                        val startIdx = maxOf(0, fullStreamBytes.size - windowSizeBytes)
                        val windowChunk = fullStreamBytes.copyOfRange(startIdx, fullStreamBytes.size)

                        onChunkCaptured(windowChunk)
                        unpostedBytes = 0

                        // Prune memory if stream gets excessively large
                        if (fullStreamBytes.size > windowSizeBytes * 2) {
                            rollingStream.reset()
                            rollingStream.write(windowChunk)
                        }
                    }
                }
            }
        }.start()
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}