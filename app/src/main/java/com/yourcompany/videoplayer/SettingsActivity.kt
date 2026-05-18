package com.yourcompany.videoplayer

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.util.Log
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

class SettingsActivity : AppCompatActivity() {

    private lateinit var progressCard: MaterialCardView
    private lateinit var progressOverlay: View
    private lateinit var progressPercentage: TextView
    private lateinit var progressText: TextView
    private lateinit var linearProgress: ProgressBar
    private lateinit var etaText: TextView
    private lateinit var etSignature: TextInputEditText

    private val videoPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val paths = result.data?.getStringArrayListExtra("PATHS")
            if (!paths.isNullOrEmpty()) {
                startBatchConversion(paths)
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
        linearProgress = findViewById(R.id.linear_progress)
        etaText = findViewById(R.id.eta_text)
        etSignature = findViewById(R.id.et_custom_signature)

        val prefs = getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
        val currentSig = prefs.getString("SECURITY_SIGNATURE", "HAMZA")
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

        val btnConverter = findViewById<MaterialCardView>(R.id.btn_video_converter)
        btnConverter.setOnClickListener {
            val intent = Intent(this, VideoPickerActivity::class.java)
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

    private fun startBatchConversion(paths: List<String>) {
        progressCard.visibility = View.VISIBLE
        progressOverlay.visibility = View.VISIBLE
        
        lifecycleScope.launch(Dispatchers.IO) {
            var successCount = 0
            val durPrefs = getSharedPreferences("SECURE_DUR", Context.MODE_PRIVATE)
            
            paths.forEachIndexed { index, path ->
                val stdPath = standardizePath(path)
                val file = File(stdPath)
                if (file.exists()) {
                    val volumeRoot = getVolumeRoot(stdPath)
                    val outDir = File(volumeRoot, "Convert").apply { if (!exists()) mkdirs() }
                    val finalName = "${file.nameWithoutExtension}_conv.mp4"
                    val outFile = File(outDir, finalName)

                    withContext(Dispatchers.Main) {
                        progressText.text = "Securing (${index + 1}/${paths.size}):\n${file.name}"
                        progressPercentage.text = "0%"
                        linearProgress.progress = 0
                    }
                    
                    var originalDuration = 0L
                    try {
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(file.absolutePath)
                        originalDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L
                        retriever.release()
                    } catch (e: Exception) {}

                    val success = convertSingleFile(file, outFile, originalDuration)
                    if (success) {
                        successCount++
                        durPrefs.edit().putLong(outFile.absolutePath, originalDuration).apply()
                        MediaScannerConnection.scanFile(this@SettingsActivity, arrayOf(outFile.absolutePath), null, null)
                    }
                }
            }

            withContext(Dispatchers.Main) {
                progressCard.visibility = View.GONE
                progressOverlay.visibility = View.GONE
                Toast.makeText(this@SettingsActivity, "Successfully secured $successCount videos.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun convertSingleFile(inputFile: File, outFile: File, originalDuration: Long): Boolean {
        return withContext(Dispatchers.IO) {
            var inputStream: FileInputStream? = null
            var outputStream: BufferedOutputStream? = null
            try {
                if (outFile.exists()) outFile.delete()

                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(inputFile.absolutePath)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 1920
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 1080
                val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
                retriever.release()

                val isPortrait = if (rotation == 90 || rotation == 270) width > height else height > width

                inputStream = FileInputStream(inputFile)
                outputStream = BufferedOutputStream(FileOutputStream(outFile))
                val fileSize = inputFile.length()
                val key = 0x69.toByte()
                
                val prefs = getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
                val signatureStr = prefs.getString("SECURITY_SIGNATURE", "HAMZA") ?: "HAMZA"
                val signatureBytes = signatureStr.toByteArray()

                // 🔥 1. Load, Patch Metadata, and Write Decoy
                try {
                    val decoyName = if (isPortrait) "error_msg_v.mp4" else "error_msg.mp4"
                    val rawDecoy = try {
                        assets.open(decoyName).use { it.readBytes() }
                    } catch (e: Exception) {
                        assets.open("error_msg.mp4").use { it.readBytes() }
                    }

                    // Yeh logic doosre players ko trick karega timing badal kar
                    val patchedDecoy = patchMp4Duration(rawDecoy, originalDuration)
                    outputStream?.write(patchedDecoy)
                    outputStream?.flush() 
                } catch (e: Exception) {
                    outputStream?.write("SECURE_MEDIA_CONTENT_HEADER".toByteArray())
                }

                // 2. Write Dynamic Signature
                outputStream?.write(signatureBytes)
                outputStream?.flush()

                // 3. Encrypt Loop
                val buffer = ByteArray(1024 * 256) 
                var bytesRead: Int
                var totalProcessed = 0L
                var lastUpdateTime = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    for (i in 0 until bytesRead) {
                        buffer[i] = (buffer[i].toInt() xor key.toInt()).toByte()
                    }
                    outputStream?.write(buffer, 0, bytesRead)
                    totalProcessed += bytesRead
                    
                    val now = System.currentTimeMillis()
                    if (now - lastUpdateTime > 500) {
                        lastUpdateTime = now
                        val progress = (totalProcessed * 100 / if (fileSize > 0) fileSize else 1).toInt()
                        withContext(Dispatchers.Main) {
                            progressPercentage.text = "$progress%"
                            linearProgress.progress = progress
                        }
                    }
                }
                outputStream?.flush()
                true
            } catch (e: Exception) {
                false
            } finally {
                try { inputStream?.close() } catch (e: Exception) {}
                try { outputStream?.close() } catch (e: Exception) {}
            }
        }
    }

    /**
     * 🔥 Yeh function MP4 ke atoms (headers) mein jaakar duration badal deta hai.
     * Isse MX Player, VLC aur Gallery ko lagta hai ki 3 sec ki clip asli video jitni lambi hai.
     */
    private fun patchMp4Duration(decoyBytes: ByteArray, durationMs: Long): ByteArray {
        if (durationMs <= 0) return decoyBytes
        val data = decoyBytes.copyOf()
        try {
            var i = 0
            while (i < data.size - 40) {
                // 1. Find 'mvhd' (Movie Header) - Sabse important timing
                if (data[i+4] == 'm'.toByte() && data[i+5] == 'v'.toByte() && 
                    data[i+6] == 'h'.toByte() && data[i+7] == 'd'.toByte()) {
                    
                    val version = data[i+8].toInt()
                    val timescaleOffset = if (version == 0) i + 20 else i + 28
                    val durationOffset = if (version == 0) i + 24 else i + 32
                    
                    val timescale = ((data[timescaleOffset].toInt() and 0xFF) shl 24) or
                                    ((data[timescaleOffset+1].toInt() and 0xFF) shl 16) or
                                    ((data[timescaleOffset+2].toInt() and 0xFF) shl 8) or
                                    (data[timescaleOffset+3].toInt() and 0xFF)
                    
                    if (timescale > 0) {
                        val newDur = (durationMs * timescale) / 1000
                        writeLongAt(data, durationOffset, newDur, if (version == 0) 4 else 8)
                    }
                }
                
                // 2. Find 'tkhd' (Track Header) - Video track ki timing
                if (data[i+4] == 't'.toByte() && data[i+5] == 'k'.toByte() && 
                    data[i+6] == 'h'.toByte() && data[i+7] == 'd'.toByte()) {
                    val version = data[i+8].toInt()
                    val durationOffset = if (version == 0) i + 28 else i + 36
                    writeLongAt(data, durationOffset, durationMs, if (version == 0) 4 else 8) // Simplified patch
                }

                // 3. Find 'mdhd' (Media Header) - Deep level precision
                if (data[i+4] == 'm'.toByte() && data[i+5] == 'd'.toByte() && 
                    data[i+6] == 'h'.toByte() && data[i+7] == 'd'.toByte()) {
                    val version = data[i+8].toInt()
                    val tsOff = if (version == 0) i + 20 else i + 28
                    val durOff = if (version == 0) i + 24 else i + 32
                    val ts = ((data[tsOff].toInt() and 0xFF) shl 24) or ((data[tsOff+1].toInt() and 0xFF) shl 16) or
                             ((data[tsOff+2].toInt() and 0xFF) shl 8) or (data[tsOff+3].toInt() and 0xFF)
                    if (ts > 0) writeLongAt(data, durOff, (durationMs * ts) / 1000, if (version == 0) 4 else 8)
                }
                i++
            }
        } catch (e: Exception) { }
        return data
    }

    private fun writeLongAt(data: ByteArray, offset: Int, value: Long, size: Int) {
        if (size == 4) {
            data[offset] = (value shr 24).toByte()
            data[offset+1] = (value shr 16).toByte()
            data[offset+2] = (value shr 8).toByte()
            data[offset+3] = value.toByte()
        } else {
            // 8 bytes version (Version 1)
            for (j in 0..3) data[offset+j] = 0 // Clear upper 32 bits for safety
            data[offset+4] = (value shr 24).toByte()
            data[offset+5] = (value shr 16).toByte()
            data[offset+6] = (value shr 8).toByte()
            data[offset+7] = value.toByte()
        }
    }
}
