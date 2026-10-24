package com.example.aim_android

import android.content.Context
import java.io.File
import java.io.RandomAccessFile

object ModelVerifier {

    data class ValidationResult(
        val exists: Boolean,
        val path: String,
        val sizeMB: Long,
        val isHeaderValid: Boolean,
        val error: String? = null
    )

    fun checkPushedModel(context: Context): ValidationResult {
        val targetFile = File(context.getExternalFilesDir(null), "aim_mentor_q4.gguf")

        if (!targetFile.exists()) {
            return ValidationResult(
                exists = false,
                path = targetFile.absolutePath,
                sizeMB = 0,
                isHeaderValid = false,
                error = "File does not exist in app external storage."
            )
        }

        val sizeBytes = targetFile.length()
        val sizeMB = sizeBytes / (1024 * 1024)

        // Validate GGUF Magic Header ("GGUF" bytes: 0x47, 0x47, 0x55, 0x46)
        var isValidHeader = false
        try {
            RandomAccessFile(targetFile, "r").use { raf ->
                val magic = ByteArray(4)
                raf.readFully(magic)
                val magicString = String(magic, Charsets.US_ASCII)
                isValidHeader = (magicString == "GGUF")
            }
        } catch (e: Exception) {
            return ValidationResult(
                exists = true,
                path = targetFile.absolutePath,
                sizeMB = sizeMB,
                isHeaderValid = false,
                error = "Failed to read header: ${e.message}"
            )
        }

        return ValidationResult(
            exists = true,
            path = targetFile.absolutePath,
            sizeMB = sizeMB,
            isHeaderValid = isValidHeader,
            error = if (!isValidHeader) "Invalid magic byte header (not a valid GGUF)" else null
        )
    }
}