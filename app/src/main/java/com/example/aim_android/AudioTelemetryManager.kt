package com.example.aim_android

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
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
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate, channelConfig, audioFormat, bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                throw IllegalStateException("AudioRecord failed to initialize properly.")
            }

            audioRecord?.startRecording()

            if (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
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
            } else {
                isRecording = false
                throw IllegalStateException("AudioRecord failed to enter RECORDSTATE_RECORDING.")
            }
        } catch (e: Exception) {
            isRecording = false
            try {
                audioRecord?.release()
            } catch (_: Exception) {}
            audioRecord = null
            Log.e("AIM_AUDIO", "Error starting AudioRecord", e)
            throw e
        }
    }

    fun stopRecording() {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.e("AIM_AUDIO", "Error stopping AudioRecord", e)
        } finally {
            audioRecord?.release()
            audioRecord = null
        }
    }
}