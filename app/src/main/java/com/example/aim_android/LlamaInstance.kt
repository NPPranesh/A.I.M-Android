package com.example.aim_android

import android.util.Log

class LlamaInstance(private val modelPath: String) {

    private var nativeContextPtr: Long = 0L

    init {
        try {
            // Pin to 4 performance cores and limit context window to 1024
            nativeContextPtr = loadModelNative(
                path = modelPath,
                contextSize = 1024,
                threads = 4
            )
            Log.d("AIM_LLAMA", "Native model initialized with ptr: $nativeContextPtr")
        } catch (e: Exception) {
            Log.e("AIM_LLAMA", "Failed to initialize native model", e)
        }
    }

    fun generateEvaluation(prompt: String, maxTokens: Int = 45): String {
        if (nativeContextPtr == 0L) {
            return "Score: 0/100\nFeedback: Model context is not initialized."
        }
        return evaluateNative(
            ctxPtr = nativeContextPtr,
            prompt = prompt,
            maxTokens = maxTokens,
            temp = 0.2f
        )
    }

    fun release() {
        if (nativeContextPtr != 0L) {
            freeModelNative(nativeContextPtr)
            nativeContextPtr = 0L
            Log.d("AIM_LLAMA", "Native model released.")
        }
    }

    companion object {
        init {
            try {
                System.loadLibrary("aim_bridge")
                Log.d("AIM_LLAMA", "aim_bridge loaded successfully without OpenMP dependencies!")
            } catch (e: UnsatisfiedLinkError) {
                Log.e("AIM_LLAMA", "Native load failure: ${e.message}")
            }
        }
    }

    private external fun loadModelNative(path: String, contextSize: Int, threads: Int): Long
    private external fun evaluateNative(ctxPtr: Long, prompt: String, maxTokens: Int, temp: Float): String
    private external fun freeModelNative(ctxPtr: Long)
}