package com.yourcompany.videoplayer

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap

data class VideoFile(
    val id: Long,
    val path: String,
    val title: String,
    val folderName: String,
    val size: Long,
    var duration: Long,
    val dateModified: Long = 0L,
    val mimeType: String = ""
) : Serializable {

    val formattedDuration: String get() = formatDuration(duration)
    val formattedSize: String get() = formatSize(size)
    val displayTitle: String get() = if (title.isNotBlank() && title != "Unknown") title else path.substringAfterLast("/")

    companion object {
        private val encryptionCache = ConcurrentHashMap<String, Boolean>()

        fun formatDuration(durationMs: Long): String {
            if (durationMs <= 0) return "--:--"
            val seconds = (durationMs / 1000) % 60
            val minutes = (durationMs / (1000 * 60)) % 60
            val hours = (durationMs / (1000 * 60 * 60))
            return if (hours > 0) String.format("%d:%02d:%02d", hours, minutes, seconds)
            else String.format("%02d:%02d", minutes, seconds)
        }
        
        private fun formatSize(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val mb = bytes / (1024.0 * 1024.0)
            return if (mb >= 1) String.format("%.1f MB", mb) else "${bytes / 1024} KB"
        }

        // 🔥 Now uses Dynamic Signature from Settings
        fun isFileEncrypted(context: Context, path: String): Boolean {
            val prefs = context.getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
            val currentSignature = prefs.getString("SECURITY_SIGNATURE", "HAMZA") ?: "HAMZA"
            val sigBytes = currentSignature.toByteArray()
            
            // Cache per signature to handle changes instantly
            val cacheKey = "${path}_${currentSignature}"
            encryptionCache[cacheKey]?.let { return it }

            val file = File(path)
            if (!file.exists() || file.length() < 20) return false
            
            val isEnc = try {
                RandomAccessFile(file, "r").use { raf ->
                    val buffer = ByteArray(1024 * 256)
                    val scanLimit = 1024 * 1024 * 50L // 50MB for large decoys
                    var totalRead = 0L
                    var found = false
                    
                    while (totalRead < scanLimit) {
                        val readCount = raf.read(buffer)
                        if (readCount < sigBytes.size) break
                        
                        for (i in 0 .. (readCount - sigBytes.size)) {
                            var match = true
                            for (j in sigBytes.indices) {
                                if (buffer[i + j] != sigBytes[j]) {
                                    match = false
                                    break
                                }
                            }
                            if (match) { found = true; break }
                        }
                        if (found) break
                        totalRead += (readCount - sigBytes.size + 1)
                        raf.seek(totalRead)
                    }
                    found
                }
            } catch (e: Exception) { false }
            
            encryptionCache[cacheKey] = isEnc
            return isEnc
        }
    }
}
