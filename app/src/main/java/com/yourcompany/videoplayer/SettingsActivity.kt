package com.yourcompany.videoplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class SettingsActivity : AppCompatActivity() {

    private lateinit var progressCard: MaterialCardView
    private lateinit var progressOverlay: View
    private lateinit var progressPercentage: TextView
    private lateinit var progressText: TextView
    private lateinit var progressTitle: TextView
    private lateinit var linearProgress: ProgressBar
    private lateinit var etaText: TextView
    private lateinit var etSignature: TextInputEditText

    private var isGhostMode = false
    private var totalBytesToProcess = 0L
    private var bytesProcessedSoFar = 0L
    private var batchStartTime = 0L

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val paths = result.data?.getStringArrayListExtra("PATHS")
            if (!paths.isNullOrEmpty()) {
                startBatchConversion(paths, isGhostMode)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<Toolbar>(R.id.toolbar_settings)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressed() }

        progressCard = findViewById(R.id.progress_card)
        progressOverlay = findViewById(R.id.progress_overlay)
        progressPercentage = findViewById(R.id.progress_percentage)
        progressText = findViewById(R.id.progress_text)
        progressTitle = findViewById(R.id.progress_title)
        linearProgress = findViewById(R.id.linear_progress)
        etaText = findViewById(R.id.eta_text)
        etSignature = findViewById(R.id.et_custom_signature)

        val prefs = getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
        val currentSig = prefs.getString("SECURITY_SIGNATURE", "HAMZA") ?: "HAMZA"
        etSignature.setText(currentSig)

        findViewById<Button>(R.id.btn_save_signature).setOnClickListener {
            val newSig = etSignature.text.toString().trim()
            if (newSig.length >= 3) {
                prefs.edit().putString("SECURITY_SIGNATURE", newSig).apply()
                Toast.makeText(this, "Signature updated to: $newSig", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Signature too short!", Toast.LENGTH_SHORT).show()
            }
        }

        val vlcSwitch = findViewById<SwitchMaterial>(R.id.switch_vlc_mode_settings)
        vlcSwitch.isChecked = prefs.getBoolean("ALWAYS_VLC", false)
        vlcSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("ALWAYS_VLC", isChecked).apply()
        }

        findViewById<MaterialCardView>(R.id.btn_video_converter).setOnClickListener {
            isGhostMode = false
            val intent = Intent(this, VideoPickerActivity::class.java)
            videoPickerLauncher.launch(intent)
        }

        findViewById<MaterialCardView>(R.id.btn_ghost_converter).setOnClickListener {
            isGhostMode = true
            val intent = Intent(this, VideoPickerActivity::class.java)
            intent.putExtra("IS_GHOST_REQUEST", true)
            videoPickerLauncher.launch(intent)
        }
    }

    private fun standardizePath(path: String): String {
        if (path.isEmpty()) return path
        return path.replace("/mnt/media_rw/", "/storage/").replace("//", "/")
    }

    private fun getVolumeRoot(path: String): String {
        val stdPath = standardizePath(path)
        if (stdPath.startsWith("/storage/emulated/0")) return "/storage/emulated/0"
        val parts = stdPath.split("/")
        if (parts.size >= 3 && parts[1] == "storage") return "/storage/${parts[2]}"
        return Environment.getExternalStorageDirectory().absolutePath
    }

    private fun startBatchConversion(paths: List<String>, isGhost: Boolean) {
        progressCard.visibility = View.VISIBLE
        progressOverlay.visibility = View.VISIBLE
        progressTitle.text = if (isGhost) "GHOSTING MODE" else "SECURE ENCODING"
        etaText.text = "Calculating ETA..."

        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            val durPrefs = getSharedPreferences("SECURE_DUR", Context.MODE_PRIVATE)
            
            totalBytesToProcess = paths.sumOf { File(it).length() }
            bytesProcessedSoFar = 0L
            batchStartTime = System.currentTimeMillis()

            paths.forEachIndexed { index, path ->
                val stdPath = standardizePath(path)
                val file = File(stdPath)
                if (file.exists()) {
                    val volumeRoot = getVolumeRoot(stdPath)
                    val folderName = if (isGhost) "Ghost" else "Convert"
                    val outDir = File(volumeRoot, folderName).apply { if (!exists()) mkdirs() }
                    val suffix = if (isGhost) "_ghost.mp4" else "_conv.mp4"
                    val finalName = "${file.nameWithoutExtension}$suffix"
                    val outFile = File(outDir, finalName)

                    withContext(Dispatchers.Main) {
                        progressText.text = "Processing (${index + 1}/${paths.size}):\n${file.name}"
                    }
                    
                    var originalDuration = 0L
                    try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(file.absolutePath)
                        originalDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                        retriever.release()
                    } catch (e: Exception) {}

                    if (file.absolutePath != outFile.absolutePath) {
                        val success = convertSingleFile(file, outFile, originalDuration, isGhost) { _, bytesInThisFile ->
                            val totalProcessed = bytesProcessedSoFar + bytesInThisFile
                            val overallPercent = (totalProcessed * 100 / if (totalBytesToProcess > 0) totalBytesToProcess else 1).toInt().coerceAtMost(99)
                            
                            val currentTime = System.currentTimeMillis()
                            val elapsedSeconds = (currentTime - batchStartTime) / 1000.0
                            
                            lifecycleScope.launch(Dispatchers.Main) {
                                progressPercentage.text = "$overallPercent%"
                                linearProgress.progress = overallPercent
                                
                                if (elapsedSeconds > 1.5 && totalProcessed > 0) {
                                    val bytesPerSecond = totalProcessed / elapsedSeconds
                                    val remainingBytes = totalBytesToProcess - totalProcessed
                                    val remainingSeconds = (remainingBytes / bytesPerSecond).toLong()
                                    etaText.text = "ETA: ${formatEta(remainingSeconds)}"
                                }
                            }
                        }
                        if (success) {
                            successCount++
                            // Save original duration for our player, but use 0 if ghosting for display
                            durPrefs.edit().putLong(outFile.absolutePath, if (isGhost) 0L else originalDuration).apply()
                            MediaScannerConnection.scanFile(this@SettingsActivity, arrayOf(outFile.absolutePath), null, null)
                        }
                    }
                    bytesProcessedSoFar += file.length()
                }
            }

            withContext(Dispatchers.Main) {
                linearProgress.progress = 100
                progressPercentage.text = "100%"
                etaText.text = "Done!"
                progressCard.postDelayed({
                    progressCard.visibility = View.GONE
                    progressOverlay.visibility = View.GONE
                    val msg = if (isGhost) "Ghosted $successCount files. Duration gone!" else "Secured $successCount videos."
                    Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
                }, 500)
            }
        }
    }

    private fun formatEta(seconds: Long): String {
        if (seconds <= 2) return "Finishing..."
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format("%02d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    private suspend fun convertSingleFile(
        inputFile: File, 
        outFile: File, 
        originalDuration: Long, 
        useGhostMode: Boolean,
        onProgress: (Int, Long) -> Unit
    ): Boolean {
        return withContext(Dispatchers.IO) {
            var inputStream: FileInputStream? = null
            var outputStream: BufferedOutputStream? = null
            try {
                if (outFile.exists()) outFile.delete()
                inputStream = FileInputStream(inputFile)
                outputStream = BufferedOutputStream(FileOutputStream(outFile))
                
                val fileSize = inputFile.length()
                val buffer = ByteArray(1024 * 1024)
                var bytesRead: Int
                var totalProcessed = 0L
                var lastUpdateTime = 0L

                if (useGhostMode) {
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalProcessed += bytesRead
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime > 300) {
                            lastUpdateTime = now
                            onProgress((totalProcessed * 100 / if (fileSize > 0) fileSize else 1).toInt(), totalProcessed)
                        }
                    }
                    outputStream.flush()
                    outputStream.close()
                    outputStream = null
                    
                    // GHOST PATCHING: Target only Movie header, keep tracks intact.
                    patchFileDurationGhost(outFile)
                    return@withContext true
                } else {
                    // Encryption Logic
                    val retriever = MediaMetadataRetriever()
                    var width = 1920; var height = 1080; var rotation = 0
                    try {
                        retriever.setDataSource(inputFile.absolutePath)
                        width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1920
                        height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1080
                        rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                    } catch (e: Exception) {} finally { try { retriever.release() } catch (e: Exception) {} }

                    val isPortrait = if (rotation == 90 || rotation == 270) width > height else height > width
                    val prefs = getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
                    val signatureStr = prefs.getString("SECURITY_SIGNATURE", "HAMZA") ?: "HAMZA"
                    val signatureBytes = signatureStr.toByteArray()
                    val iv = ByteArray(16); SecureRandom().nextBytes(iv)
                    val keyBytes = MessageDigest.getInstance("SHA-256").digest(signatureBytes)
                    val secretKey = SecretKeySpec(keyBytes, "AES")
                    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey, IvParameterSpec(iv))

                    val decoyName = if (isPortrait) "error_msg_v.mp4" else "error_msg.mp4"
                    val rawDecoy = try { assets.open(decoyName).use { it.readBytes() } } catch (e: Exception) { assets.open("error_msg.mp4").use { it.readBytes() } }
                    outputStream.write(patchMp4Duration(rawDecoy, originalDuration))
                    outputStream.write("_HAMZA_SECURED_VIDEO_".toByteArray())
                    outputStream.write(signatureBytes)
                    outputStream.write(iv)

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        val encrypted = cipher.update(buffer, 0, bytesRead)
                        if (encrypted != null) outputStream.write(encrypted)
                        totalProcessed += bytesRead
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime > 300) {
                            lastUpdateTime = now
                            onProgress((totalProcessed * 100 / if (fileSize > 0) fileSize else 1).toInt(), totalProcessed)
                        }
                    }
                    val finalBytes = cipher.doFinal()
                    if (finalBytes != null) outputStream.write(finalBytes)
                    outputStream.flush()
                    return@withContext true
                }
            } catch (e: Exception) {
                return@withContext false
            } finally {
                try { inputStream?.close() } catch (e: Exception) {}
                try { outputStream?.close() } catch (e: Exception) {}
            }
        }
    }

    private fun patchFileDurationGhost(file: File) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                val size = raf.length()
                val bufferSize = 1024 * 1024
                val buffer = ByteArray(bufferSize + 1024)
                
                // Scan first 40MB and last 20MB for headers (mvhd)
                scanAndPatchGhost(raf, buffer, 0L, 40 * 1024 * 1024L.coerceAtMost(size))
                if (size > 40 * 1024 * 1024L) {
                    val endStart = (size - 20 * 1024 * 1024L).coerceAtLeast(0L)
                    scanAndPatchGhost(raf, buffer, endStart, size)
                }
            }
        } catch (e: Exception) { }
    }

    private fun scanAndPatchGhost(raf: RandomAccessFile, buffer: ByteArray, start: Long, end: Long) {
        var offset = start
        while (offset < end) {
            raf.seek(offset)
            val readCount = raf.read(buffer)
            if (readCount < 64) break

            var i = 0
            while (i < readCount - 60) {
                val b4 = buffer[i+4]; val b5 = buffer[i+5]; val b6 = buffer[i+6]; val b7 = buffer[i+7]
                
                // GHOST FIX: Only patch the main Movie Header (mvhd) to 0.
                // We DO NOT patch tkhd (Track Header) or mdhd (Media Header).
                // Keeping tracks original ensures the video plays fully on Snapchat Story.
                val isMvhd = b4 == 'm'.toByte() && b5 == 'v'.toByte() && b6 == 'h'.toByte() && b7 == 'd'.toByte()

                if (isMvhd) {
                    val version = buffer[i+8].toInt() and 0xFF
                    val durOff = if (version == 0) 24 else 32
                    writeZeroAtPos(raf, offset + i + durOff, if (version == 0) 4 else 8)
                }
                i++
            }
            offset += (readCount - 512).coerceAtLeast(1)
        }
    }

    private fun writeZeroAtPos(raf: RandomAccessFile, pos: Long, byteCount: Int) {
        try {
            val currentPos = raf.filePointer
            raf.seek(pos)
            if (byteCount == 4) raf.writeInt(0) else raf.writeLong(0L)
            raf.seek(currentPos)
        } catch (e: Exception) {}
    }

    private fun patchMp4Duration(decoyBytes: ByteArray, durationMs: Long): ByteArray {
        val data = decoyBytes.copyOf()
        try {
            var i = 0
            while (i < data.size - 48) {
                val b4 = data[i+4]; val b5 = data[i+5]; val b6 = data[i+6]; val b7 = data[i+7]
                if ((b4 == 'm'.toByte() && b5 == 'v'.toByte() && b6 == 'h'.toByte() && b7 == 'd'.toByte()) ||
                    (b4 == 'm'.toByte() && b5 == 'd'.toByte() && b6 == 'h'.toByte() && b7 == 'd'.toByte())) {
                    val version = data[i+8].toInt() and 0xFF
                    val timescaleOffset = if (version == 0) i + 20 else i + 28
                    val durationOffset = if (version == 0) i + 24 else i + 32
                    val timescale = ((data[timescaleOffset].toInt() and 0xFF) shl 24) or 
                                    ((data[timescaleOffset+1].toInt() and 0xFF) shl 16) or
                                    ((data[timescaleOffset+2].toInt() and 0xFF) shl 8) or
                                    ((data[timescaleOffset+3].toInt() and 0xFF))
                    if (timescale > 0) writeLongAt(data, durationOffset, (durationMs * timescale) / 1000, if (version == 0) 4 else 8)
                }
                i++
            }
        } catch (e: Exception) {}
        return data
    }

    private fun writeLongAt(data: ByteArray, offset: Int, value: Long, size: Int) {
        if (size == 4) {
            data[offset] = (value shr 24).toByte(); data[offset+1] = (value shr 16).toByte()
            data[offset+2] = (value shr 8).toByte(); data[offset+3] = value.toByte()
        } else {
            data[offset] = (value shr 56).toByte(); data[offset+1] = (value shr 48).toByte()
            data[offset+2] = (value shr 40).toByte(); data[offset+3] = (value shr 32).toByte()
            data[offset+4] = (value shr 24).toByte(); data[offset+5] = (value shr 16).toByte()
            data[offset+6] = (value shr 8).toByte(); data[offset+7] = value.toByte()
        }
    }
}
