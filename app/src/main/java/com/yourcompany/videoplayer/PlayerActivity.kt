package com.yourcompany.videoplayer

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.util.Log
import android.util.Rational
import android.view.*
import android.view.accessibility.CaptioningManager
import android.view.animation.AlphaAnimation
import android.widget.*
import androidx.annotation.OptIn
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.*
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.CaptionStyleCompat
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File
import java.util.*
import kotlin.math.abs
import kotlin.math.max

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {

    private lateinit var playerView: androidx.media3.ui.PlayerView
    private lateinit var subtitleView: SubtitleView
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val controller: MediaController?
        get() = if (controllerFuture?.isDone == true) controllerFuture?.get() else null

    private var libVLC: LibVLC? = null
    private var vlcMediaPlayer: MediaPlayer? = null
    private lateinit var vlcVideoLayout: VLCVideoLayout
    private var isUsingVlc = false
    private var vlcResizeMode = 0
    private var fakeDuration = 0L

    private lateinit var audioManager: AudioManager
    private lateinit var volumeBar: ProgressBar
    private lateinit var brightnessBar: ProgressBar
    private lateinit var gestureContainer: LinearLayout
    private lateinit var gestureIcon: ImageView
    private lateinit var gestureText: TextView
    private lateinit var lockBtn: ImageButton
    private lateinit var unlockBtnTop: ImageButton
    private lateinit var controlsWrapper: View
    private lateinit var seekBar: SeekBar
    private lateinit var tvCurrentTime: TextView
    private lateinit var tvTotalDuration: TextView
    private lateinit var playPauseBtn: ImageButton
    private lateinit var videoTitleTop: TextView
    private lateinit var speedBoostText: TextView
    private lateinit var audioOverlayText: TextView
    private lateinit var centerFeedbackIcon: ImageView
    private lateinit var errorOverlay: View
    private lateinit var gestureOverlay: View

    private lateinit var rewOverlay: View
    private lateinit var ffwdOverlay: View

    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideRunnable = Runnable { hideControls() }
    private val updateHandler = Handler(Looper.getMainLooper())

    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    private var isUserSeeking = false
    private var activeGesture = 0 

    private val updateProgressAction = object : Runnable {
        override fun run() {
            if (!isUserSeeking) {
                if (isUsingVlc) {
                    vlcMediaPlayer?.let {
                        if (it.isPlaying) {
                            val currentPos = it.time
                            seekBar.progress = currentPos.toInt()
                            tvCurrentTime.text = formatTime(currentPos)
                            saveResumePosition(currentPos)
                        }
                    }
                } else {
                    controller?.let {
                        if (it.isPlaying || it.playbackState == Player.STATE_READY) {
                            val currentPos = it.currentPosition
                            if (fakeDuration > 0) {
                                seekBar.max = fakeDuration.toInt()
                                tvTotalDuration.text = formatTime(fakeDuration)
                                seekBar.progress = (currentPos % fakeDuration).toInt()
                                tvCurrentTime.text = formatTime(currentPos % fakeDuration)
                                saveResumePosition(currentPos)
                            } else {
                                seekBar.progress = currentPos.toInt()
                                tvCurrentTime.text = formatTime(currentPos)
                                saveResumePosition(currentPos)
                            }
                        }
                    }
                }
            }
            updateHandler.postDelayed(this, 1000)
        }
    }

    private var isLocked = false
    private var videoList = ArrayList<VideoFile>()
    private var position = 0
    private var rotationMode = 0
    private var volumeVisualPercent = 0f

    private var wasPlayingBeforeScroll = false
    private var tempScrubPos = 0L
    private var initialSeekPos = 0L
    private var gestureStartX = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            setContentView(R.layout.activity_player)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            syncVolumePercent()

            if (initViews()) {
                initializeController()
                handleIntent(intent)
                setupGestures()
                showControls()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Player Error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun syncVolumePercent() {
        val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val systemPct = (currentVol.toFloat() / (if(maxVol > 0) maxVol.toFloat() else 1f)) * 100f
        if (volumeVisualPercent <= 100f || systemPct < 100f) volumeVisualPercent = systemPct
    }

    private fun initializeController() {
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            val controller = controller ?: return@addListener
            playerView.player = controller

            controller.addListener(object : Player.Listener {
                override fun onCues(cues: List<Cue>) { subtitleView.setCues(cues) }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (!isUsingVlc) {
                        playPauseBtn.setImageResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow)
                        if (isPlaying) resetTimer()
                    }
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_READY) {
                        errorOverlay.visibility = View.GONE
                        if (fakeDuration > 0) {
                            seekBar.max = fakeDuration.toInt()
                            tvTotalDuration.text = formatTime(fakeDuration)
                        } else {
                            seekBar.max = controller.duration.toInt()
                            tvTotalDuration.text = formatTime(controller.duration)
                        }
                        updateHandler.post(updateProgressAction)
                    }
                    if (state == Player.STATE_ENDED) {
                        saveResumePosition(0)
                        playNext()
                    }
                }
                override fun onPlayerError(error: PlaybackException) { errorOverlay.visibility = View.VISIBLE }
            })
            if (videoList.isNotEmpty()) playCurrentVideo()
        }, MoreExecutors.directExecutor())
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val type = intent.type
        if ((Intent.ACTION_VIEW == action || Intent.ACTION_SEND == action) && type?.startsWith("video/") == true) {
            val videoUri = if (Intent.ACTION_SEND == action) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else intent.data
            if (videoUri != null) {
                val title = getFileName(videoUri) ?: "External Video"
                videoList = arrayListOf(VideoFile(id = -1L, title = title, path = videoUri.toString(), folderName = "External", duration = 0, size = 0))
                position = 0
                videoTitleTop.text = title
                playCurrentVideo()
                return
            }
        }
        val list = intent.getSerializableExtra("VIDEO_LIST") as? ArrayList<VideoFile>
        if (list != null) videoList = list
        position = intent.getIntExtra("POSITION", 0)
        if (videoList.isNotEmpty()) videoTitleTop.text = videoList[position].title
        playCurrentVideo()
    }

    private fun checkSignature(path: String): Boolean = VideoFile.isFileEncrypted(this, path)

    private fun playCurrentVideo() {
        if (videoList.isEmpty()) return
        val path = videoList[position].path
        val prefs = getSharedPreferences("SETTINGS", Context.MODE_PRIVATE)
        val alwaysVlc = prefs.getBoolean("ALWAYS_VLC", false)
        val durPrefs = getSharedPreferences("SECURE_DUR", Context.MODE_PRIVATE)
        val isEncrypted = checkSignature(path)
        fakeDuration = if (isEncrypted) durPrefs.getLong(path, 0L) else 0L
        if (alwaysVlc) switchToVlcDecoder(isEncrypted)
        else if (isEncrypted) playWithExoCamouflage(path)
        else playWithExo(path)
    }

    private fun playWithExoCamouflage(path: String) {
        if (isUsingVlc) { vlcMediaPlayer?.stop(); isUsingVlc = false }
        vlcVideoLayout.visibility = View.GONE; errorOverlay.visibility = View.GONE; playerView.visibility = View.VISIBLE
        subtitleView.visibility = View.VISIBLE
        controller?.let {
            it.repeatMode = Player.REPEAT_MODE_ONE
            it.setMediaItem(MediaItem.fromUri(path)); it.prepare(); it.play()
            if (fakeDuration > 0) { tvTotalDuration.text = formatTime(fakeDuration); seekBar.max = fakeDuration.toInt() }
        }
    }

    private fun getFileName(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use { if (it.moveToFirst()) { val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (index != -1) result = it.getString(index) } }
        }
        if (result == null) { result = uri.path; val cut = result?.lastIndexOf('/') ?: -1; if (cut != -1) result = result?.substring(cut + 1) }
        return result
    }

    private fun initViews(): Boolean {
        return try {
            playerView = findViewById(R.id.player_view); subtitleView = findViewById(R.id.subtitle_view)
            subtitleView.setViewType(SubtitleView.VIEW_TYPE_CANVAS)
            val captioningManager = getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
            if (captioningManager != null) {
                subtitleView.setStyle(CaptionStyleCompat.createFromCaptionStyle(captioningManager.userStyle))
                subtitleView.setFractionalTextSize(captioningManager.fontScale * SubtitleView.DEFAULT_TEXT_SIZE_FRACTION)
            }
            vlcVideoLayout = findViewById(R.id.vlc_video_layout); errorOverlay = findViewById(R.id.error_overlay)
            gestureOverlay = findViewById(R.id.gesture_overlay)
            volumeBar = findViewById(R.id.volume_progress); brightnessBar = findViewById(R.id.brightness_progress)
            gestureContainer = findViewById(R.id.gesture_container); gestureIcon = findViewById(R.id.gesture_icon); gestureText = findViewById(R.id.gesture_text)
            lockBtn = findViewById(R.id.btn_lock); unlockBtnTop = findViewById(R.id.btn_unlock_top)
            controlsWrapper = findViewById(R.id.controls_wrapper); seekBar = findViewById(R.id.video_seekbar)
            tvCurrentTime = findViewById(R.id.tv_current_time); tvTotalDuration = findViewById(R.id.tv_total_duration)
            playPauseBtn = findViewById(R.id.btn_play_pause); videoTitleTop = findViewById(R.id.video_title_top)
            speedBoostText = findViewById(R.id.speed_boost_text); audioOverlayText = findViewById(R.id.audio_overlay_text)
            centerFeedbackIcon = findViewById(R.id.click_feedback_icon); rewOverlay = findViewById(R.id.rew_overlay); ffwdOverlay = findViewById(R.id.ffwd_overlay)

            playPauseBtn.setOnClickListener { togglePlayPause(); resetTimer() }
            findViewById<ImageButton>(R.id.btn_back)?.setOnClickListener { finish() }
            findViewById<ImageButton>(R.id.btn_list)?.setOnClickListener { finish() }
            findViewById<ImageButton>(R.id.btn_prev)?.setOnClickListener { playPrevious(); showCenterFeedback(android.R.drawable.ic_media_previous); resetTimer() }
            findViewById<ImageButton>(R.id.btn_next)?.setOnClickListener { playNext(); showCenterFeedback(android.R.drawable.ic_media_next); resetTimer() }
            findViewById<ImageButton>(R.id.btn_audio_track)?.setOnClickListener { showAudioDialog(); resetTimer() }
            findViewById<ImageButton>(R.id.btn_subtitle)?.setOnClickListener { showSubtitleDialog(); resetTimer() }
            findViewById<ImageButton>(R.id.btn_resize)?.setOnClickListener { changeResizeMode(); resetTimer() }
            findViewById<ImageButton>(R.id.btn_speed)?.setOnClickListener { showSpeedDialog(); resetTimer() }
            findViewById<ImageButton>(R.id.btn_pip)?.setOnClickListener { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
            findViewById<ImageButton>(R.id.btn_rotate)?.setOnClickListener { cycleRotationMode(); resetTimer() }
            lockBtn.setOnClickListener { isLocked = true; updateLockUI(); showCenterFeedback(R.drawable.ic_lock) }
            unlockBtnTop.setOnClickListener { isLocked = false; updateLockUI(); showCenterFeedback(R.drawable.ic_unlock) }

            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) { tvCurrentTime.text = formatTime(p.toLong()); if (isUsingVlc) vlcMediaPlayer?.time = p.toLong() else if (fakeDuration <= 0) controller?.seekTo(p.toLong()) }
                }
                override fun onStartTrackingTouch(s: SeekBar?) { isUserSeeking = true; hideHandler.removeCallbacks(hideRunnable) }
                override fun onStopTrackingTouch(s: SeekBar?) { Handler(Looper.getMainLooper()).postDelayed({ isUserSeeking = false }, 1000); resetTimer() }
            })
            true
        } catch (e: Exception) { false }
    }

    private fun togglePlayPause() {
        val vlc = vlcMediaPlayer; val exo = controller
        val isPlaying = if (isUsingVlc && vlc != null) { if (vlc.isPlaying) { vlc.pause(); false } else { vlc.play(); true } }
        else if (!isUsingVlc && exo != null) { if (exo.isPlaying) { exo.pause(); false } else { exo.play(); true } } else false
        showCenterFeedback(if (isPlaying) R.drawable.ic_play_arrow else R.drawable.ic_pause)
    }

    private fun playWithExo(path: String) {
        if (isUsingVlc) { vlcMediaPlayer?.stop(); isUsingVlc = false }
        vlcVideoLayout.visibility = View.GONE; errorOverlay.visibility = View.GONE; playerView.visibility = View.VISIBLE
        subtitleView.visibility = View.VISIBLE
        saveLastWatched()
        controller?.let { it.repeatMode = Player.REPEAT_MODE_OFF; it.setMediaItem(MediaItem.fromUri(path)); it.prepare(); it.play(); val resumePos = getResumePosition(path); if (resumePos > 0) it.seekTo(resumePos) }
    }

    private fun switchToVlcDecoder(isEncrypted: Boolean) {
        if (!isUsingVlc) { controller?.stop(); isUsingVlc = true }
        playerView.visibility = View.GONE; errorOverlay.visibility = View.GONE; vlcVideoLayout.visibility = View.VISIBLE
        subtitleView.visibility = View.GONE
        val path = videoList[position].path
        saveLastWatched()
        if (libVLC == null) { val options = arrayListOf("-vvv", "--no-drop-late-frames", "--no-skip-frames", "--rtsp-tcp", "--no-stats"); libVLC = LibVLC(this, options) }
        if (vlcMediaPlayer == null) vlcMediaPlayer = MediaPlayer(libVLC) else vlcMediaPlayer?.stop()
        vlcMediaPlayer?.detachViews(); vlcMediaPlayer?.attachViews(vlcVideoLayout, null, true, true)
        val media = if (isEncrypted) { val decryptionUri = Uri.parse("content://com.yourcompany.videoplayer.decryption$path"); try { val pfd = contentResolver.openFileDescriptor(decryptionUri, "r"); if (pfd != null) Media(libVLC!!, pfd.fileDescriptor) else Media(libVLC!!, decryptionUri) } catch (e: Exception) { Media(libVLC!!, decryptionUri) } }
        else { if (path.startsWith("/") || path.startsWith("file://")) { val cleanPath = if (path.startsWith("file://")) path.substring(7) else path; Media(libVLC!!, cleanPath) } else Media(libVLC!!, Uri.parse(path)) }
        vlcMediaPlayer?.media = media; media.release()
        vlcMediaPlayer?.setEventListener { event -> runOnUiThread { when (event.type) {
                    MediaPlayer.Event.Playing -> { playPauseBtn.setImageResource(R.drawable.ic_pause); seekBar.max = vlcMediaPlayer?.length?.toInt() ?: 0; tvTotalDuration.text = formatTime(vlcMediaPlayer?.length ?: 0); updateHandler.post(updateProgressAction); val resumePos = getResumePosition(path); if (resumePos > 0) vlcMediaPlayer?.time = resumePos; vlcMediaPlayer?.volume = if (volumeVisualPercent > 100) volumeVisualPercent.toInt() else 100; resetTimer() }
                    MediaPlayer.Event.Paused -> { playPauseBtn.setImageResource(R.drawable.ic_play_arrow); resetTimer() }
                    MediaPlayer.Event.EndReached -> { saveResumePosition(0); playNext() }
                    MediaPlayer.Event.EncounteredError -> { errorOverlay.visibility = View.VISIBLE }
        } } }
        vlcMediaPlayer?.play()
    }

    private fun playNext() { if (position < videoList.size - 1) { position++; videoTitleTop.text = videoList[position].title; playCurrentVideo() } }
    private fun playPrevious() { if (position > 0) { position--; videoTitleTop.text = videoList[position].title; playCurrentVideo() } }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                if (isLocked) return false
                activeGesture = 4
                val target = if (isUsingVlc) vlcVideoLayout else playerView
                target.pivotX = target.width / 2f
                target.pivotY = target.height / 2f
                return true
            }
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isLocked || activeGesture != 4) return false
                val prevScale = scaleFactor
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.25f, 10.0f)
                
                // Only update if scale actually changed to reduce vibration
                if (prevScale != scaleFactor) {
                    val target = if (isUsingVlc) vlcVideoLayout else playerView
                    target.scaleX = scaleFactor
                    target.scaleY = scaleFactor
                    if (scaleFactor <= 1.0f) {
                        translateX = 0f; translateY = 0f; target.translationX = 0f; target.translationY = 0f
                    }
                    gestureContainer.visibility = View.VISIBLE
                    gestureIcon.setImageResource(R.drawable.ic_resize)
                    gestureText.text = "${(scaleFactor * 100).toInt()}%"
                }
                return true
            }
        })
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (isLocked) { unlockBtnTop.visibility = if (unlockBtnTop.visibility == View.VISIBLE) View.GONE else View.VISIBLE; hideHandler.removeCallbacks(hideRunnable); if (unlockBtnTop.visibility == View.VISIBLE) hideHandler.postDelayed({ unlockBtnTop.visibility = View.GONE }, 3000) }
                else { if (controlsWrapper.visibility == View.VISIBLE) hideControls() else showControls() }; return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (isLocked || activeGesture != 0) return false; val q = resources.displayMetrics.widthPixels / 4
                if (e.x < q) { if (isUsingVlc) vlcMediaPlayer?.let { it.time -= 10000 } else controller?.seekBack(); showSeekOverlay(true) }
                else if (e.x > 3 * q) { if (isUsingVlc) vlcMediaPlayer?.let { it.time += 10000 } else controller?.seekForward(); showSeekOverlay(false) }
                else togglePlayPause(); resetTimer(); return true
            }
            override fun onLongPress(e: MotionEvent) { if (isLocked || activeGesture != 0) return; val isP = if(isUsingVlc) vlcMediaPlayer?.isPlaying == true else controller?.isPlaying == true; if (!isP) return; activeGesture = 3; if (isUsingVlc) vlcMediaPlayer?.rate = 2.0f else controller?.setPlaybackSpeed(2.0f); speedBoostText.visibility = View.VISIBLE }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dX: Float, dY: Float): Boolean {
                if (isLocked || e1 == null) return false; if (e2.pointerCount > 1) activeGesture = 4
                if (activeGesture == 4) {
                    if (scaleFactor > 1.0f) {
                        val target = if (isUsingVlc) vlcVideoLayout else playerView
                        translateX = (translateX - dX).coerceIn(-max(0f, (target.width * scaleFactor - target.width) / 2f), max(0f, (target.width * scaleFactor - target.width) / 2f))
                        translateY = (translateY - dY).coerceIn(-max(0f, (target.height * scaleFactor - target.height) / 2f), max(0f, (target.height * scaleFactor - target.height) / 2f))
                        target.translationX = translateX
                        target.translationY = translateY
                    }
                    return true
                }
                if (activeGesture == 3) return false
                if (activeGesture == 0) {
                    if (abs(dX) > abs(dY)) { activeGesture = 1; gestureStartX = e2.x; wasPlayingBeforeScroll = if(isUsingVlc) vlcMediaPlayer?.isPlaying == true else controller?.isPlaying == true; initialSeekPos = if(isUsingVlc) vlcMediaPlayer?.time ?: 0L else controller?.currentPosition ?: 0L; tempScrubPos = initialSeekPos; isUserSeeking = true; showControls() }
                    else { activeGesture = 2; syncVolumePercent() }
                }
                if (activeGesture == 1) {
                    val dur = if(isUsingVlc) vlcMediaPlayer?.length ?: 0L else controller?.duration ?: 0L; if (dur <= 0) return false
                    tempScrubPos = (initialSeekPos + ((e2.x - gestureStartX) * (300000f / resources.displayMetrics.widthPixels)).toLong()).coerceIn(0L, dur)
                    seekBar.progress = tempScrubPos.toInt(); tvCurrentTime.text = formatTime(tempScrubPos); gestureContainer.visibility = View.VISIBLE; gestureIcon.setImageResource(R.drawable.ic_play_arrow); gestureText.text = formatTime(tempScrubPos)
                } else if (activeGesture == 2) { if (e1.x < resources.displayMetrics.widthPixels / 2) adjustBrightness(dY / resources.displayMetrics.heightPixels) else adjustVolume(dY / resources.displayMetrics.heightPixels) }
                resetTimer(); return true
            }
        })
        val touchListener = View.OnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                gestureContainer.visibility = View.GONE
                if (activeGesture == 1) { if (isUsingVlc) vlcMediaPlayer?.time = tempScrubPos else controller?.seekTo(tempScrubPos); if (wasPlayingBeforeScroll) { if (isUsingVlc) vlcMediaPlayer?.play() else controller?.play() }; Handler(Looper.getMainLooper()).postDelayed({ isUserSeeking = false }, 500) }
                else if (activeGesture == 3) { if (isUsingVlc) vlcMediaPlayer?.rate = 1.0f else controller?.setPlaybackSpeed(1.0f); speedBoostText.visibility = View.GONE }
                activeGesture = 0; if (controlsWrapper.visibility == View.VISIBLE) resetTimer()
            }
            true
        }
        // 🔥 Attaching to gestureOverlay so it works on the entire screen even if video is small
        gestureOverlay.setOnTouchListener(touchListener)
    }

    private fun showSeekOverlay(isR: Boolean) { val o = if (isR) rewOverlay else ffwdOverlay; o.visibility = View.VISIBLE; o.alpha = 1f; val f = AlphaAnimation(1f, 0f); f.duration = 500; f.startOffset = 500; o.startAnimation(f); Handler(Looper.getMainLooper()).postDelayed({ o.visibility = View.INVISIBLE }, 1000) }
    private fun adjustVolume(deltaP: Float) {
        gestureContainer.visibility = View.VISIBLE; gestureIcon.setImageResource(R.drawable.ic_volume); volumeBar.visibility = View.VISIBLE; brightnessBar.visibility = View.GONE; volumeBar.max = 300
        val maxH = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC); volumeVisualPercent = (volumeVisualPercent + (deltaP * 200f)).coerceIn(0f, 300f)
        if (volumeVisualPercent <= 100f) { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (volumeVisualPercent / 100 * maxH).toInt(), 0); if (isUsingVlc) vlcMediaPlayer?.volume = 100; gestureText.text = "${volumeVisualPercent.toInt()}%" }
        else { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, maxH, 0); if (isUsingVlc) { vlcMediaPlayer?.volume = volumeVisualPercent.toInt(); gestureText.text = "Boost: ${volumeVisualPercent.toInt()}%" } else gestureText.text = "100% (Max) - Boost only in VLC" }
        volumeBar.progress = volumeVisualPercent.toInt()
    }
    private fun adjustBrightness(deltaP: Float) { gestureContainer.visibility = View.VISIBLE; gestureIcon.setImageResource(R.drawable.ic_brightness); brightnessBar.visibility = View.VISIBLE; volumeBar.visibility = View.GONE; val lp = window.attributes; if (lp.screenBrightness < 0) lp.screenBrightness = 0.5f; lp.screenBrightness = (lp.screenBrightness + deltaP).coerceIn(0.01f, 1.0f); window.attributes = lp; brightnessBar.progress = (lp.screenBrightness * 100).toInt(); gestureText.text = "${brightnessBar.progress}%" }
    private fun cycleRotationMode() {
        rotationMode = (rotationMode + 1) % 3
        when (rotationMode) {
            0 -> { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR; showToast("Rotation: Auto") }
            1 -> { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; showToast("Rotation: Landscape") }
            2 -> { requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT; showToast("Rotation: Portrait") }
        }
    }
    private fun saveResumePosition(pos: Long) {
        if (videoList.isEmpty() || position >= videoList.size) return
        val path = videoList[position].path
        if (fakeDuration > 0) {
            val relativePos = pos % fakeDuration
            getSharedPreferences("RESUME_INFO", Context.MODE_PRIVATE).edit().putLong(path, relativePos).apply()
            return
        }
        val totalDur = if (isUsingVlc) vlcMediaPlayer?.length ?: 0L else controller?.duration ?: 0L
        val finalPos = if (totalDur > 10000 && (pos >= totalDur - 5000 || pos >= totalDur * 0.98)) 0L else pos
        getSharedPreferences("RESUME_INFO", Context.MODE_PRIVATE).edit().putLong(path, finalPos).apply()
    }
    private fun saveLastWatched() { if (videoList.isEmpty() || position >= videoList.size) return; val v = videoList[position]; getSharedPreferences("PLAY_INFO", Context.MODE_PRIVATE).edit().putLong("LAST_ID", v.id).putString("LAST_PATH", v.path).apply() }
    private fun getResumePosition(p: String): Long = getSharedPreferences("RESUME_INFO", Context.MODE_PRIVATE).getLong(p, 0L)
    private fun showSpeedDialog() { val s = arrayOf("0.25x", "0.5x", "0.75x", "Normal", "1.25x", "1.5x", "1.75x", "2.0x"); val v = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f); AlertDialog.Builder(this, R.style.WhiteDialog).setTitle("Playback Speed").setItems(s) { _, w -> if (isUsingVlc) vlcMediaPlayer?.rate = v[w] else controller?.setPlaybackSpeed(v[w]); resetTimer() }.show() }
    private fun showAudioDialog() {
        if (isUsingVlc) { val vlc = vlcMediaPlayer ?: return; val tracks = vlc.audioTracks ?: return; val aL = mutableListOf<String>(); val aI = mutableListOf<Int>(); for (t in tracks) if (t.id != -1) { aL.add(t.name ?: "Audio ${aL.size + 1}"); aI.add(t.id) }; if (aL.isEmpty()) return; AlertDialog.Builder(this, R.style.WhiteDialog).setTitle("Select Audio").setItems(aL.toTypedArray()) { _, w -> vlc.setAudioTrack(aI[w]); resetTimer() }.show() }
        else { val tracks = controller?.currentTracks ?: return; val aL = mutableListOf<String>(); val tG = mutableListOf<Pair<Tracks.Group, Int>>(); for (g in tracks.groups) if (g.type == C.TRACK_TYPE_AUDIO) for (i in 0 until g.length) { val f = g.getTrackFormat(i); aL.add(f.label ?: f.language?.uppercase(Locale.getDefault()) ?: "Audio ${aL.size + 1}"); tG.add(g to i) }; if (aL.isEmpty()) return; AlertDialog.Builder(this, R.style.WhiteDialog).setTitle("Select Audio").setItems(aL.toTypedArray()) { _, w -> val (g, i) = tG[w]; controller?.trackSelectionParameters = controller?.trackSelectionParameters?.buildUpon()?.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))?.build()!!; resetTimer() }.show() }
    }
    private fun showSubtitleDialog() {
        if (isUsingVlc) { val vlc = vlcMediaPlayer ?: return; val tracks = vlc.spuTracks ?: return; val sL = mutableListOf("Off"); val sI = mutableListOf(-1); for (t in tracks) if (t.id != -1) { sL.add(t.name ?: "Subtitle ${sL.size}"); sI.add(t.id) }; AlertDialog.Builder(this, R.style.WhiteDialog).setTitle("Subtitles").setItems(sL.toTypedArray()) { _, w -> vlc.setSpuTrack(sI[w]); resetTimer() }.show() }
        else { val tracks = controller?.currentTracks ?: return; val sL = mutableListOf("Disable Subtitles"); val tG = mutableListOf<Pair<Tracks.Group, Int>?>(null); for (g in tracks.groups) if (g.type == C.TRACK_TYPE_TEXT) for (i in 0 until g.length) { val f = g.getTrackFormat(i); sL.add(f.label ?: f.language?.uppercase(Locale.getDefault()) ?: "Subtitle ${sL.size}"); tG.add(g to i) }; AlertDialog.Builder(this, R.style.WhiteDialog).setTitle("Subtitles").setItems(sL.toTypedArray()) { _, w -> if (w == 0) controller?.trackSelectionParameters = controller?.trackSelectionParameters?.buildUpon()?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)?.build()!! else { val p = tG[w]!!; controller?.trackSelectionParameters = controller?.trackSelectionParameters?.buildUpon()?.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)?.setOverrideForType(TrackSelectionOverride(p.first.mediaTrackGroup, p.second))?.build()!! }; resetTimer() }.show() }
    }
    private fun showCenterFeedback(i: Int) { centerFeedbackIcon.setImageResource(i); centerFeedbackIcon.visibility = View.VISIBLE; centerFeedbackIcon.alpha = 0f; centerFeedbackIcon.scaleX = 0.5f; centerFeedbackIcon.scaleY = 0.5f; centerFeedbackIcon.animate().alpha(1f).scaleX(1.2f).scaleY(1.2f).setDuration(250).withEndAction { centerFeedbackIcon.animate().alpha(0f).scaleX(1.5f).scaleY(1.5f).setDuration(250).withEndAction { centerFeedbackIcon.visibility = View.GONE }.start() }.start() }
    private fun changeResizeMode() {
        val t = if (isUsingVlc) vlcVideoLayout else playerView; scaleFactor = 1.0f; translateX = 0f; translateY = 0f; t.scaleX = 1.0f; t.scaleY = 1.0f; t.translationX = 0f; t.translationY = 0f
        if (isUsingVlc) { vlcMediaPlayer?.let { vlcResizeMode = (vlcResizeMode + 1) % 3; when (vlcResizeMode) { 0 -> { it.videoScale = MediaPlayer.ScaleType.SURFACE_FIT_SCREEN; it.aspectRatio = null; showToast("VLC: Fit") }; 1 -> { it.videoScale = MediaPlayer.ScaleType.SURFACE_FILL; it.aspectRatio = null; showToast("VLC: Stretch") }; 2 -> { val m = resources.displayMetrics; it.aspectRatio = "${m.widthPixels}:${m.heightPixels}"; it.videoScale = MediaPlayer.ScaleType.SURFACE_FILL; showToast("VLC: Zoom/Crop") } } } }
        else { playerView.resizeMode = when (playerView.resizeMode) { AspectRatioFrameLayout.RESIZE_MODE_FIT -> { showToast("Exo: Fill"); AspectRatioFrameLayout.RESIZE_MODE_FILL }; AspectRatioFrameLayout.RESIZE_MODE_FILL -> { showToast("Exo: Zoom"); AspectRatioFrameLayout.RESIZE_MODE_ZOOM }; else -> { showToast("Exo: Fit"); AspectRatioFrameLayout.RESIZE_MODE_FIT } } }
        resetTimer()
    }
    private fun formatTime(ms: Long): String { val s = ms / 1000; val sec = s % 60; val min = (s / 60) % 60; val hr = s / 3600; return if (hr > 0) String.format("%02d:%02d:%02d", hr, min, sec) else String.format("%02d:%02d", min, sec) }
    private fun updateLockUI() { if (isLocked) { hideControls(); unlockBtnTop.visibility = View.VISIBLE } else { unlockBtnTop.visibility = View.GONE; showControls() } }
    private fun showControls() { if (isLocked) controlsWrapper.visibility = View.GONE else controlsWrapper.visibility = View.VISIBLE; resetTimer() }
    private fun hideControls() { controlsWrapper.visibility = View.GONE; hideHandler.removeCallbacks(hideRunnable) }
    private fun resetTimer() { hideHandler.removeCallbacks(hideRunnable); if (if(isUsingVlc) vlcMediaPlayer?.isPlaying == true else controller?.isPlaying == true) hideHandler.postDelayed(hideRunnable, 4000) }
    private fun showToast(m: String) { Toast.makeText(this, m, Toast.LENGTH_SHORT).apply { setGravity(Gravity.CENTER, 0, 0); show() } }
    override fun onStart() { super.onStart(); if (isUsingVlc) { vlcMediaPlayer?.detachViews(); vlcMediaPlayer?.attachViews(vlcVideoLayout, null, true, true) } else controller?.let { playerView.player = it } }
    override fun onStop() { super.onStop(); if (isUsingVlc) vlcMediaPlayer?.detachViews() else playerView.player = null }
    override fun onDestroy() { super.onDestroy(); updateHandler.removeCallbacks(updateProgressAction); controllerFuture?.let { MediaController.releaseFuture(it) }; vlcMediaPlayer?.release(); libVLC?.release() }
}
