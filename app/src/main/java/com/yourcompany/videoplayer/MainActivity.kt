package com.yourcompany.videoplayer

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.MenuItem
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.widget.Toolbar
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.reflect.Method
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var unifiedAdapter: UnifiedAdapter
    private lateinit var toolbar: Toolbar
    private lateinit var toolbarTitle: TextView
    private lateinit var btnSettings: ImageView
    private lateinit var fabResume: ExtendedFloatingActionButton
    private val itemList = ArrayList<Any>()
    private val fullItemList = ArrayList<Any>()
    private var currentPath: String? = null
    private var rootPath: String? = "STORAGE_ROOT"
    private var isLoading = false

    // Search Views
    private lateinit var etSearch: EditText
    private lateinit var btnClearSearch: ImageView
    private lateinit var searchContainer: View

    // Music Player Views
    private lateinit var tabLayout: TabLayout
    private lateinit var layoutMusic: View
    private lateinit var switchMerge: SwitchCompat
    
    private lateinit var layoutDualContainer: View
    private lateinit var layoutMergedContainer: View
    
    private lateinit var btnSelectLeft: ImageButton
    private lateinit var btnPlayLeft: ImageButton
    private lateinit var btnPrevLeft: ImageButton
    private lateinit var btnNextLeft: ImageButton
    private lateinit var sbLeft: SeekBar
    private lateinit var tvTitleLeft: TextView
    
    private lateinit var btnSelectRight: ImageButton
    private lateinit var btnPlayRight: ImageButton
    private lateinit var btnPrevRight: ImageButton
    private lateinit var btnNextRight: ImageButton
    private lateinit var sbRight: SeekBar
    private lateinit var tvTitleRight: TextView

    // Merged controls
    private lateinit var btnSelectMerged: ImageButton
    private lateinit var btnPlayMerged: ImageButton
    private lateinit var btnPrevMerged: ImageButton
    private lateinit var btnNextMerged: ImageButton
    private lateinit var sbMerged: SeekBar
    private lateinit var tvTitleMerged: TextView

    private var musicService: DualMusicService? = null
    private var isBound = false
    private val handler = Handler(Looper.getMainLooper())
    private val REQUEST_LEFT = 1001
    private val REQUEST_RIGHT = 1002
    private val REQUEST_MERGED = 1003

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as DualMusicService.MusicBinder
            musicService = binder.getService()
            isBound = true
            updateMusicUI()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            musicService = null
        }
    }

    private fun standardizePath(path: String): String {
        if (path == "STORAGE_ROOT") return path
        var fixedPath = path.replace("/mnt/media_rw/", "/storage/")
            .replace("//", "/")
        if (fixedPath.length > 1 && fixedPath.endsWith("/")) {
            fixedPath = fixedPath.substring(0, fixedPath.length - 1)
        }
        return fixedPath
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Bind Service
        val intent = Intent(this, DualMusicService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        toolbar = findViewById(R.id.toolbar_main)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        toolbarTitle = findViewById(R.id.toolbar_title)
        btnSettings = findViewById(R.id.btn_settings)
        recyclerView = findViewById(R.id.folderRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        val resId = R.anim.item_animation_fall_down
        val animation = AnimationUtils.loadLayoutAnimation(this, resId)
        recyclerView.layoutAnimation = animation

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        tabLayout = findViewById(R.id.tab_layout)
        layoutMusic = findViewById(R.id.layout_music)
        
        setupSearch()
        setupTabs()
        setupMusicControls()

        currentPath = rootPath
        checkPermissions()
        
        fabResume = findViewById(R.id.fab_last_played)
        fabResume.setOnClickListener { playLastWatched() }
    }

    private fun setupSearch() {
        searchContainer = findViewById(R.id.searchContainer)
        etSearch = findViewById(R.id.etSearch)
        btnClearSearch = findViewById(R.id.btnClearSearch)

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().lowercase(Locale.ROOT)
                filter(query)
                btnClearSearch.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        btnClearSearch.setOnClickListener {
            etSearch.text.clear()
        }
    }

    private fun filter(query: String) {
        itemList.clear()
        if (query.isEmpty()) {
            itemList.addAll(fullItemList)
        } else {
            for (item in fullItemList) {
                if (item is FolderModel) {
                    if (item.folderName.lowercase(Locale.ROOT).contains(query)) {
                        itemList.add(item)
                    }
                } else if (item is VideoFile) {
                    if (item.title.lowercase(Locale.ROOT).contains(query)) {
                        itemList.add(item)
                    }
                }
            }
        }
        if (::unifiedAdapter.isInitialized) {
            unifiedAdapter.notifyDataSetChanged()
        }
    }

    private fun setupTabs() {
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                updateToolbar()
                if (tab?.position == 0) {
                    recyclerView.visibility = View.VISIBLE
                    layoutMusic.visibility = View.GONE
                    searchContainer.visibility = View.VISIBLE
                    fabResume.show()
                } else {
                    recyclerView.visibility = View.GONE
                    layoutMusic.visibility = View.VISIBLE
                    searchContainer.visibility = View.GONE
                    fabResume.hide()
                    updateMusicUI()
                }
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupMusicControls() {
        layoutDualContainer = findViewById(R.id.layout_dual_container)
        layoutMergedContainer = findViewById(R.id.layout_merged_container)
        
        switchMerge = findViewById(R.id.switch_merge)
        switchMerge.setOnCheckedChangeListener { _, isChecked ->
            musicService?.setMergeMode(isChecked)
            updateMusicUI()
        }

        // Left Controls
        btnSelectLeft = findViewById(R.id.btn_select_left)
        btnPlayLeft = findViewById(R.id.btn_play_left)
        btnPrevLeft = findViewById(R.id.btn_prev_left)
        btnNextLeft = findViewById(R.id.btn_next_left)
        sbLeft = findViewById(R.id.sb_left)
        tvTitleLeft = findViewById(R.id.tv_title_left)

        // Right Controls
        btnSelectRight = findViewById(R.id.btn_select_right)
        btnPlayRight = findViewById(R.id.btn_play_right)
        btnPrevRight = findViewById(R.id.btn_prev_right)
        btnNextRight = findViewById(R.id.btn_next_right)
        sbRight = findViewById(R.id.sb_right)
        tvTitleRight = findViewById(R.id.tv_title_right)

        // Merged Controls
        btnSelectMerged = findViewById(R.id.btn_select_merged)
        btnPlayMerged = findViewById(R.id.btn_play_merged)
        btnPrevMerged = findViewById(R.id.btn_prev_merged)
        btnNextMerged = findViewById(R.id.btn_next_merged)
        sbMerged = findViewById(R.id.sb_merged)
        tvTitleMerged = findViewById(R.id.tv_title_merged)

        btnSelectLeft.setOnClickListener { openMusicPicker(REQUEST_LEFT) }
        btnSelectRight.setOnClickListener { openMusicPicker(REQUEST_RIGHT) }
        btnSelectMerged.setOnClickListener { openMusicPicker(REQUEST_MERGED) }

        btnPlayLeft.setOnClickListener { 
            musicService?.toggleLeft()
            updateMusicUI()
        }
        btnPlayRight.setOnClickListener { 
            musicService?.toggleRight()
            updateMusicUI()
        }
        btnPlayMerged.setOnClickListener {
            musicService?.toggleBoth()
            updateMusicUI()
        }

        btnPrevLeft.setOnClickListener { musicService?.skipLeft(false); updateMusicUI() }
        btnNextLeft.setOnClickListener { musicService?.skipLeft(true); updateMusicUI() }
        btnPrevRight.setOnClickListener { musicService?.skipRight(false); updateMusicUI() }
        btnNextRight.setOnClickListener { musicService?.skipRight(true); updateMusicUI() }
        btnPrevMerged.setOnClickListener { musicService?.skipLeft(false); musicService?.skipRight(false); updateMusicUI() }
        btnNextMerged.setOnClickListener { musicService?.skipLeft(true); musicService?.skipRight(true); updateMusicUI() }

        setupSeekBar(sbLeft) { pos -> musicService?.seekLeft(pos) }
        setupSeekBar(sbRight) { pos -> musicService?.seekRight(pos) }
        setupSeekBar(sbMerged) { pos -> musicService?.seekLeft(pos); musicService?.seekRight(pos) }

        updateProgress()
    }

    private fun openMusicPicker(requestCode: Int) {
        val intent = Intent(this, VideoPickerActivity::class.java)
        intent.putExtra("IS_AUDIO", true)
        startActivityForResult(intent, requestCode)
    }

    private fun updateMusicUI() {
        musicService?.let {
            val merged = it.isMerged()
            layoutDualContainer.visibility = if (merged) View.GONE else View.VISIBLE
            layoutMergedContainer.visibility = if (merged) View.VISIBLE else View.GONE
            
            if (!merged) {
                tvTitleLeft.text = it.getTitleLeft()
                tvTitleRight.text = it.getTitleRight()
                btnPlayLeft.setImageResource(if (it.isLeftPlaying()) R.drawable.ic_pause else R.drawable.ic_play_arrow)
                btnPlayRight.setImageResource(if (it.isRightPlaying()) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            } else {
                tvTitleMerged.text = it.getTitleLeft() // Use left title for both if merged
                btnPlayMerged.setImageResource(if (it.isLeftPlaying() || it.isRightPlaying()) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            }
            
            if (switchMerge.isChecked != merged) {
                switchMerge.isChecked = merged
            }
        }
    }

    private fun setupSeekBar(seekBar: SeekBar, onSeek: (Int) -> Unit) {
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onSeek(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun updateProgress() {
        musicService?.let {
            // Left progress
            val leftDur = it.getLeftDuration()
            val leftPos = it.getLeftProgress()
            if (leftDur > 0) {
                sbLeft.max = leftDur
                sbLeft.progress = leftPos
            }
            
            // Right progress
            val rightDur = it.getRightDuration()
            val rightPos = it.getRightProgress()
            if (rightDur > 0) {
                sbRight.max = rightDur
                sbRight.progress = rightPos
            }
            
            // Merged progress (using left as reference)
            if (it.isMerged() && leftDur > 0) {
                sbMerged.max = leftDur
                sbMerged.progress = leftPos
            }
            
            // Update play/pause buttons icons periodically to keep in sync
            if (!it.isMerged()) {
                btnPlayLeft.setImageResource(if (it.isLeftPlaying()) R.drawable.ic_pause else R.drawable.ic_play_arrow)
                btnPlayRight.setImageResource(if (it.isRightPlaying()) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            } else {
                btnPlayMerged.setImageResource(if (it.isLeftPlaying() || it.isRightPlaying()) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            }
        }
        handler.postDelayed({ updateProgress() }, 1000)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == RESULT_OK && data != null) {
            val paths = data.getStringArrayListExtra("PATHS")
            if (paths != null && paths.isNotEmpty()) {
                val path = paths[0]
                val uri = Uri.fromFile(File(path))
                val title = File(path).name
                when (requestCode) {
                    REQUEST_LEFT -> {
                        musicService?.playLeft(uri, title)
                        updateMusicUI()
                    }
                    REQUEST_RIGHT -> {
                        musicService?.playRight(uri, title)
                        updateMusicUI()
                    }
                    REQUEST_MERGED -> {
                        musicService?.playLeft(uri, title)
                        musicService?.playRight(uri, title)
                        updateMusicUI()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (hasPermission()) {
            loadContent()
            updateResumeFab()
        }
        updateMusicUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        handler.removeCallbacksAndMessages(null)
    }

    private fun updateResumeFab() {
        val prefs = getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE)
        val lastPath = prefs.getString("LAST_PATH", "")
        if (!lastPath.isNullOrEmpty() && File(lastPath).exists()) {
            fabResume.visibility = if (tabLayout.selectedTabPosition == 0) View.VISIBLE else View.GONE
        } else {
            fabResume.visibility = View.GONE
        }
    }

    private fun playLastWatched() {
        // Pause music if playing
        musicService?.pauseAll()

        val prefs = getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE)
        val lastPath = prefs.getString("LAST_PATH", "") ?: return
        val lastFile = File(lastPath)
        if (lastFile.exists()) {
            val video = VideoFile(
                id = prefs.getLong("LAST_ID", -1L),
                path = lastPath,
                title = lastFile.name,
                folderName = lastFile.parentFile?.name ?: "Unknown",
                size = lastFile.length(),
                duration = 0
            )
            val intent = Intent(this, PlayerActivity::class.java).apply {
                putExtra("VIDEO_LIST", arrayListOf(video))
                putExtra("POSITION", 0)
            }
            startActivity(intent)
        }
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivityForResult(intent, 102)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivityForResult(intent, 102)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val permissions = arrayOf(
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
                )
                val toRequest = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                if (toRequest.isNotEmpty()) {
                    ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 103)
                }
            }
        } else {
            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) 
                arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            
            val toRequest = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
            if (toRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 101)
            }
        }
    }

    fun loadContent() {
        if (isLoading) return
        isLoading = true
        
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val repo = VideoRepository(this@MainActivity)
                val stdCurrentPath = standardizePath(currentPath ?: rootPath!!)
                
                val allVideos = if (stdCurrentPath == rootPath) repo.getVideosFromFolder("") else repo.getVideosFromFolder(stdCurrentPath)

                val foldersInCurrentDir = ArrayList<FolderModel>()
                val videosInCurrentDir = ArrayList<VideoFile>()
                val processedSubFolders = mutableSetOf<String>()

                if (stdCurrentPath == "STORAGE_ROOT") {
                    val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        val storageVolumes = storageManager.storageVolumes
                        for (volume in storageVolumes) {
                            val path = getVolumePath(volume) ?: continue
                            val stdVolPath = standardizePath(path)
                            if (stdVolPath.contains("self") || stdVolPath.contains("knox") || stdVolPath == "/storage/emulated") continue
                            
                            val name = if (volume.isPrimary) "Internal Storage" else volume.getDescription(this@MainActivity) ?: "SD Card"
                            val count = allVideos.count { standardizePath(it.path).startsWith(stdVolPath) }
                            val firstVideo = allVideos.find { standardizePath(it.path).startsWith(stdVolPath) }?.path ?: ""
                            
                            foldersInCurrentDir.add(FolderModel(name, stdVolPath, count.toString(), firstVideo))
                        }
                    }
                } else {
                    for (video in allVideos) {
                        val relPath = getRelativePath(video.path, stdCurrentPath)
                        if (relPath != null && relPath.isNotEmpty()) {
                            val parts = relPath.split('/')
                            if (parts.size > 1) {
                                val subFolderName = parts[0]
                                val subFolderPath = stdCurrentPath + "/" + subFolderName
                                val subFolderNameLower = subFolderName.lowercase(Locale.ROOT)
                                if (!processedSubFolders.contains(subFolderNameLower)) {
                                    val count = allVideos.count { getRelativePath(it.path, subFolderPath) != null }
                                    foldersInCurrentDir.add(FolderModel(subFolderName, subFolderPath, count.toString(), video.path))
                                    processedSubFolders.add(subFolderNameLower)
                                }
                            } else if (parts.size == 1) {
                                videosInCurrentDir.add(video)
                            }
                        }
                    }

                    if (foldersInCurrentDir.isEmpty() && videosInCurrentDir.isEmpty()) {
                        val dir = File(stdCurrentPath)
                        dir.listFiles()?.forEach { file ->
                            if (file.isDirectory && !file.name.startsWith(".")) {
                                foldersInCurrentDir.add(FolderModel(file.name, standardizePath(file.absolutePath), "0", ""))
                            }
                        }
                    }
                }
                
                foldersInCurrentDir.sortWith { o1, o2 -> naturalCompare(o1.folderName.lowercase(), o2.folderName.lowercase()) }
                videosInCurrentDir.sortWith { o1, o2 -> naturalCompare(o1.title.lowercase(), o2.title.lowercase()) }

                withContext(Dispatchers.Main) {
                    updateToolbar()
                    
                    fullItemList.clear()
                    fullItemList.addAll(foldersInCurrentDir)
                    fullItemList.addAll(videosInCurrentDir)

                    val query = etSearch.text.toString().lowercase(Locale.ROOT)
                    if (query.isNotEmpty()) {
                        filter(query)
                    } else {
                        itemList.clear()
                        itemList.addAll(fullItemList)
                    }

                    if (::unifiedAdapter.isInitialized) {
                        unifiedAdapter.notifyDataSetChanged()
                    } else {
                        unifiedAdapter = UnifiedAdapter(this@MainActivity, itemList) { selectedFolder ->
                            if (!isLoading) {
                                currentPath = standardizePath(selectedFolder.path)
                                loadContent()
                            }
                        }
                        recyclerView.adapter = unifiedAdapter
                    }
                    isLoading = false
                }
            } catch (e: Exception) { 
                withContext(Dispatchers.Main) { isLoading = false }
                e.printStackTrace() 
            }
        }
    }

    private fun isVideoFile(file: File): Boolean {
        val exts = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp")
        return exts.contains(file.extension.lowercase())
    }

    private fun getRelativePath(videoPath: String, folderPath: String): String? {
        val stdVideoPath = standardizePath(videoPath)
        val stdFolderPath = standardizePath(folderPath)
        val videoLower = stdVideoPath.lowercase(Locale.ROOT)
        val folderLower = stdFolderPath.lowercase(Locale.ROOT)
        if (videoLower.startsWith(folderLower + "/")) {
            return stdVideoPath.substring(stdFolderPath.length + 1)
        }
        return null
    }

    private fun getVolumePath(volume: Any): String? {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val getDirectory: Method = volume.javaClass.getMethod("getDirectory")
                return (getDirectory.invoke(volume) as File).absolutePath
            }
            val getPath: Method = volume.javaClass.getMethod("getPath")
            return getPath.invoke(volume) as String
        } catch (e: Exception) { }
        return null
    }

    private fun updateToolbar() {
        val stdCurrentPath = standardizePath(currentPath ?: rootPath!!)
        if (stdCurrentPath == rootPath) {
            toolbarTitle.text = if (tabLayout.selectedTabPosition == 0) "Video Folders" else "Dual Music Player"
            toolbar.navigationIcon = null
        } else {
            var name = File(stdCurrentPath).name
            if (stdCurrentPath == standardizePath(Environment.getExternalStorageDirectory().absolutePath)) name = "Internal Storage"
            toolbarTitle.text = name
            toolbar.setNavigationIcon(R.drawable.ic_back)
            toolbar.navigationIcon?.setTint(ContextCompat.getColor(this, android.R.color.white))
            toolbar.setNavigationOnClickListener { onBackPressed() }
        }
    }

    private fun naturalCompare(s1: String, s2: String): Int {
        val n1 = s1.length; val n2 = s2.length
        var i1 = 0; var i2 = 0
        while (i1 < n1 && i2 < n2) {
            val c1 = s1[i1]; val c2 = s2[i2]
            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                var num1 = ""; while (i1 < n1 && Character.isDigit(s1[i1])) { num1 += s1[i1]; i1++ }
                var num2 = ""; while (i2 < n2 && Character.isDigit(s2[i2])) { num2 += s2[i2]; i2++ }
                val res = try { num1.toLong().compareTo(num2.toLong()) } catch (e: Exception) { 0 }
                if (res != 0) return res
            } else {
                if (c1 != c2) return c1.compareTo(c2)
                i1++; i2++
            }
        }
        return n1 - n2
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { onBackPressed(); return true }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        if (isLoading) return
        
        // Clear search first if active
        if (etSearch.text.isNotEmpty()) {
            etSearch.text.clear()
            return
        }

        if (tabLayout.selectedTabPosition == 1) {
            tabLayout.getTabAt(0)?.select()
            return
        }
        val stdCurrentPath = standardizePath(currentPath ?: rootPath!!)
        if (stdCurrentPath != rootPath) {
            val storageManager = getSystemService(Context.STORAGE_SERVICE) as StorageManager
            var isAtVolumeRoot = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                for (volume in storageManager.storageVolumes) {
                    if (stdCurrentPath == standardizePath(getVolumePath(volume) ?: "")) { isAtVolumeRoot = true; break }
                }
            }
            if (isAtVolumeRoot) { currentPath = rootPath; loadContent(); return }
            val parent = File(stdCurrentPath).parentFile
            if (parent != null && parent.absolutePath != "/" && parent.absolutePath != "/storage") {
                currentPath = standardizePath(parent.absolutePath)
                loadContent()
                return
            }
            currentPath = rootPath; loadContent(); return
        }
        super.onBackPressed()
    }
}
