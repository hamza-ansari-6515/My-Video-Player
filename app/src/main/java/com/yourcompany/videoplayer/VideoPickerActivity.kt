package com.yourcompany.videoplayer

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.*
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class VideoPickerActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: PickerUnifiedAdapter
    private val itemList = ArrayList<Any>()
    private val selectedPaths = HashSet<String>()
    
    private var currentPath: String? = "STORAGE_ROOT"
    private val rootPath = "STORAGE_ROOT"
    private var convertMenuItem: MenuItem? = null
    private var isAudioMode = false
    private var isGhostRequest = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_video_picker)

        isAudioMode = intent.getBooleanExtra("IS_AUDIO", false)
        isGhostRequest = intent.getBooleanExtra("IS_GHOST_REQUEST", false)

        val toolbar = findViewById<Toolbar>(R.id.toolbar_picker)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { 
            if (currentPath == rootPath) finish() else onBackPressed()
        }

        recyclerView = findViewById(R.id.rv_picker)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        loadContent()
    }

    private fun loadContent() {
        val repo = VideoRepository(this)
        val stdCurrentPath = standardizePath(currentPath ?: rootPath)
        
        val allFiles = ArrayList<VideoFile>()
        if (isGhostRequest) {
            allFiles.addAll(repo.getVideosFromFolder(if (stdCurrentPath == rootPath) "" else stdCurrentPath))
            allFiles.addAll(getAudioFiles(stdCurrentPath))
        } else if (isAudioMode) {
            allFiles.addAll(getAudioFiles(stdCurrentPath))
        } else {
            allFiles.addAll(repo.getVideosFromFolder(if (stdCurrentPath == rootPath) "" else stdCurrentPath))
        }

        val foldersInCurrentDir = ArrayList<FolderModel>()
        val filesInCurrentDir = ArrayList<VideoFile>()
        val processedSubFolders = mutableSetOf<String>()

        if (stdCurrentPath == "STORAGE_ROOT") {
            val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (volume in storageManager.storageVolumes) {
                    val path = getVolumePath(volume) ?: continue
                    val stdVolPath = standardizePath(path)
                    if (stdVolPath.contains("self") || stdVolPath.contains("knox") || stdVolPath == "/storage/emulated") continue
                    
                    val name = if (volume.isPrimary) "Internal Storage" else volume.getDescription(this) ?: "SD Card"
                    val count = allFiles.count { standardizePath(it.path).startsWith(stdVolPath) }
                    foldersInCurrentDir.add(FolderModel(name, stdVolPath, count.toString(), ""))
                }
            }
        } else {
            for (file in allFiles) {
                val relPath = getRelativePath(file.path, stdCurrentPath)
                if (relPath != null && relPath.isNotEmpty()) {
                    val parts = relPath.split('/')
                    if (parts.size > 1) {
                        val subFolderName = parts[0]
                        val subFolderPath = stdCurrentPath + "/" + subFolderName
                        if (!processedSubFolders.contains(subFolderName.lowercase())) {
                            val count = allFiles.count { getRelativePath(it.path, subFolderPath) != null }
                            foldersInCurrentDir.add(FolderModel(subFolderName, subFolderPath, count.toString(), file.path))
                            processedSubFolders.add(subFolderName.lowercase())
                        }
                    } else if (parts.size == 1) {
                        filesInCurrentDir.add(file)
                    }
                }
            }
        }

        foldersInCurrentDir.sortBy { it.folderName.lowercase() }
        filesInCurrentDir.sortBy { it.title.lowercase() }

        itemList.clear()
        itemList.addAll(foldersInCurrentDir)
        itemList.addAll(filesInCurrentDir)

        adapter = PickerUnifiedAdapter(this, itemList, selectedPaths, isAudioMode || isGhostRequest) { item ->
            if (item is FolderModel) {
                currentPath = item.path
                selectedPaths.clear() // Bug Fix: Clear selection when entering a new folder
                loadContent()
            } else if (item is VideoFile) {
                if (isAudioMode && !isGhostRequest) {
                    // Bug Fix: Single select for music mode (Dual Player)
                    val intent = Intent()
                    intent.putStringArrayListExtra("PATHS", arrayListOf(item.path))
                    setResult(Activity.RESULT_OK, intent)
                    finish()
                } else {
                    // Multi-select for Video Converter or Ghost Mode
                    toggleSelection(item.path)
                }
            }
        }
        recyclerView.adapter = adapter
        updateUI()
    }

    private fun getAudioFiles(folderPath: String): ArrayList<VideoFile> {
        val list = ArrayList<VideoFile>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION
        )
        
        val uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        try {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val idIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataIdx) ?: continue
                    val stdPath = standardizePath(path)
                    
                    if (folderPath != "STORAGE_ROOT") {
                        if (!stdPath.lowercase().startsWith(folderPath.lowercase() + "/")) continue
                    }

                    list.add(VideoFile(
                        id = cursor.getLong(idIdx),
                        path = stdPath,
                        title = cursor.getString(nameIdx) ?: File(path).name,
                        folderName = File(path).parentFile?.name ?: "Unknown",
                        size = cursor.getLong(sizeIdx).let { if (it > 0) it else File(path).length() },
                        duration = cursor.getLong(durationIdx)
                    ))
                }
            }
        } catch (e: Exception) {}
        return list
    }

    private fun toggleSelection(path: String) {
        if (selectedPaths.contains(path)) {
            selectedPaths.remove(path)
        } else {
            selectedPaths.add(path)
        }
        adapter.notifyDataSetChanged()
        updateUI()
    }

    private fun updateUI() {
        val count = selectedPaths.size
        supportActionBar?.title = when {
            isGhostRequest -> if (count > 0) "$count Selected (Ghost)" else "Select Media to Ghost"
            isAudioMode -> "Select Music"
            else -> if (count > 0) "$count Selected" else "Select Video"
        }

        convertMenuItem?.isVisible = (isGhostRequest && count > 0) || (!isAudioMode && count > 0)

        if (isGhostRequest) {
             convertMenuItem?.setIcon(R.drawable.ic_edit)
             convertMenuItem?.title = "Ghost"
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_video_picker, menu)
        convertMenuItem = menu.findItem(R.id.action_convert)
        updateUI()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_convert) {
            val intent = Intent()
            intent.putStringArrayListExtra("PATHS", ArrayList(selectedPaths.toList()))
            setResult(Activity.RESULT_OK, intent)
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        val stdCurrentPath = standardizePath(currentPath ?: rootPath)
        if (stdCurrentPath != rootPath) {
            val parent = File(stdCurrentPath).parentFile
            if (parent != null && parent.absolutePath != "/" && parent.absolutePath != "/storage") {
                currentPath = parent.absolutePath
            } else {
                currentPath = rootPath
            }
            selectedPaths.clear() // Bug Fix: Clear selection when going back to parent folder
            loadContent()
        } else {
            super.onBackPressed()
        }
    }

    private fun standardizePath(path: String): String {
        if (path == "STORAGE_ROOT") return path
        var p = path.replace("/mnt/media_rw/", "/storage/").replace("//", "/")
        if (p.length > 1 && p.endsWith("/")) p = p.substring(0, p.length - 1)
        return p
    }

    private fun getRelativePath(videoPath: String, folderPath: String): String? {
        val v = standardizePath(videoPath).lowercase()
        val f = standardizePath(folderPath).lowercase()
        return if (v.startsWith("$f/")) standardizePath(videoPath).substring(f.length + 1) else null
    }

    private fun getVolumePath(volume: Any): String? {
        return try {
            val method = volume.javaClass.getMethod(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "getDirectory" else "getPath")
            val result = method.invoke(volume)
            if (result is File) result.absolutePath else result as String
        } catch (e: Exception) { null }
    }

    private class PickerUnifiedAdapter(
        private val context: Context,
        private val items: List<Any>,
        private val selectedPaths: HashSet<String>,
        private val isMixedMode: Boolean,
        private val onItemClick: (Any) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val adapterScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        private val encryptionCache = ConcurrentHashMap<String, Boolean>()

        override fun getItemViewType(position: Int) = if (items[position] is FolderModel) 1 else 2

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(context)
            return if (viewType == 1) FolderViewHolder(inflater.inflate(R.layout.folder_item, parent, false))
            else VideoViewHolder(inflater.inflate(R.layout.video_item, parent, false))
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val settingsPrefs = context.getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
            val currentSig = settingsPrefs.getString("SECURITY_SIGNATURE", "HAMZA") ?: "HAMZA"

            if (holder is FolderViewHolder && item is FolderModel) {
                holder.name.text = item.folderName
                holder.count.text = item.videoCount
                holder.icon.setImageResource(R.drawable.ios_folder)
                holder.itemView.setOnClickListener { onItemClick(item) }
            } else if (holder is VideoViewHolder && item is VideoFile) {
                holder.title.text = item.title
                holder.size.text = formatSize(item.size)
                holder.duration.text = formatDuration(item.duration)
                holder.menu.visibility = View.GONE

                val isSelected = selectedPaths.contains(item.path)
                holder.overlay.visibility = if (isSelected) View.VISIBLE else View.GONE
                holder.tick.visibility = if (isSelected) View.VISIBLE else View.GONE
                holder.card.setCardBackgroundColor(if (isSelected) Color.parseColor("#332196F3") else Color.parseColor("#1E1E1E"))
                holder.card.strokeColor = if (isSelected) Color.parseColor("#2196F3") else Color.parseColor("#1AFFFFFF")
                holder.card.strokeWidth = if (isSelected) 4 else 1

                val isAudio = item.path.endsWith(".mp3", true) || item.path.endsWith(".m4a", true) ||
                              item.path.endsWith(".wav", true) || item.path.endsWith(".aac", true) ||
                              item.path.endsWith(".flac", true) || item.path.endsWith(".ogg", true) ||
                              item.path.endsWith(".wma", true) || item.path.endsWith(".amr", true)

                if (isAudio) {
                    holder.thumbnail.setImageResource(R.drawable.ic_audio)
                    holder.thumbnail.scaleType = ImageView.ScaleType.CENTER_INSIDE
                    holder.thumbnail.setPadding(30, 30, 30, 30)
                } else {
                    holder.thumbnail.scaleType = ImageView.ScaleType.CENTER_CROP
                    holder.thumbnail.setPadding(0, 0, 0, 0)
                    holder.thumbJob?.cancel()
                    holder.thumbJob = adapterScope.launch {
                        val isEncrypted = encryptionCache[item.path] ?: withContext(Dispatchers.IO) {
                            VideoFile.isFileEncrypted(context, item.path)
                        }
                        encryptionCache[item.path] = isEncrypted

                        val thumbUri: Uri = if (isEncrypted) {
                            Uri.parse("content://com.yourcompany.videoplayer.decryption${item.path}")
                        } else {
                            ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, item.id)
                        }

                        Glide.with(holder.itemView)
                            .asBitmap()
                            .load(thumbUri)
                            .signature(ObjectKey("${item.path}_${currentSig}_$isEncrypted"))
                            .apply(
                                RequestOptions()
                                    .placeholder(R.drawable.ic_video)
                                    .error(R.drawable.ic_video)
                                    .centerCrop()
                                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                                    .override(250, 250)
                            )
                            .into(holder.thumbnail)
                    }
                }
                
                holder.itemView.setOnClickListener { onItemClick(item) }
            }
        }

        override fun getItemCount() = items.size
        
        override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
            super.onDetachedFromRecyclerView(recyclerView)
            adapterScope.cancel()
        }

        private fun formatSize(s: Long) = String.format("%.1f MB", s / (1024.0 * 1024.0))
        private fun formatDuration(ms: Long): String {
            val s = ms / 1000; val m = s / 60; val h = m / 60
            return if (h > 0) String.format("%02d:%02d:%02d", h, m % 60, s % 60) else String.format("%02d:%02d", m % 60, s % 60)
        }

        class FolderViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val name: TextView = v.findViewById(R.id.folderName)
            val count: TextView = v.findViewById(R.id.videoCount)
            val icon: ImageView = v.findViewById(R.id.folderIcon)
        }
        class VideoViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val card: MaterialCardView = v.findViewById(R.id.videoCard)
            val thumbnail: ImageView = v.findViewById(R.id.videoThumbnail)
            val title: TextView = v.findViewById(R.id.videoTitle)
            val size: TextView = v.findViewById(R.id.videoSize)
            val duration: TextView = v.findViewById(R.id.videoDuration)
            val overlay: View = v.findViewById(R.id.selectionOverlay)
            val tick: ImageView = v.findViewById(R.id.tickMark)
            val menu: View = v.findViewById(R.id.videoMenu)
            var thumbJob: Job? = null
        }
    }
}
