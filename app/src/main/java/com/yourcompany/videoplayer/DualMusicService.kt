package com.yourcompany.videoplayer

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.KeyEvent
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import java.util.Locale

class DualMusicService : Service() {

    private var unplayedLeftList = mutableListOf<MusicFile>()
    private var unplayedRightList = mutableListOf<MusicFile>()
    private var playedLeftList = mutableListOf<MusicFile>()
    private var playedRightList = mutableListOf<MusicFile>()
    
    private var currentLeftMusic: MusicFile? = null
    private var currentRightMusic: MusicFile? = null
    private var playerLeft: MediaPlayer? = null
    private var playerRight: MediaPlayer? = null
    private var titleLeft: String = ""
    private var titleRight: String = ""

    data class MusicFile(val uri: Uri, val title: String, val path: String)

    fun setMusicQueue(list: List<MusicFile>) {
        unplayedLeftList.clear()
        unplayedRightList.clear()
        playedLeftList.clear()
        playedRightList.clear()
        
        val shuffled = list.shuffled()
        unplayedLeftList.addAll(shuffled)
        unplayedRightList.addAll(list.shuffled())
    }

    fun playNextFromQueue(isLeft: Boolean) {
        val unplayed = if (isLeft) unplayedLeftList else unplayedRightList
        val played = if (isLeft) playedLeftList else playedRightList

        if (unplayed.isEmpty()) {
            // Refill from played if all played
            unplayed.addAll(played)
            played.clear()
            unplayed.shuffle()
        }

        if (unplayed.isNotEmpty()) {
            val next = unplayed.removeAt(0)
            played.add(next)
            if (isLeft) {
                currentLeftMusic = next
                playLeft(next.uri, next.title, false)
            } else {
                currentRightMusic = next
                playRight(next.uri, next.title, false)
            }
        }
    }
    
    private var isMergedMode = false
    private var isAudioFocusGranted = false
    
    private val CHANNEL_ID = "dual_music_channel"
    private val NOTIFICATION_ID = 1001
    private val handler = Handler(Looper.getMainLooper())

    private var mediaSession: MediaSessionCompat? = null
    private lateinit var audioManager: AudioManager
    private var focusRequest: AudioFocusRequest? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                isAudioFocusGranted = false
                pauseAll()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pauseAll()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                if (isMergedMode) {
                    playerLeft?.setVolume(0.2f, 0.2f)
                } else {
                    playerLeft?.setVolume(0.2f, 0.0f)
                    playerRight?.setVolume(0.0f, 0.2f)
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                isAudioFocusGranted = true
                updateVolume()
            }
        }
    }

    inner class MusicBinder : Binder() {
        fun getService(): DualMusicService = this@DualMusicService
    }

    private val binder = MusicBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        setupMediaSession()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        updateProgressTask.run()
    }

    private fun setupMediaSession() {
        mediaSession = MediaSessionCompat(this, "DualMusicService").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    val keyEvent = mediaButtonEvent?.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 
                            KeyEvent.KEYCODE_HEADSETHOOK -> {
                                smartToggle()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                playBoth()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                pauseAll()
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
                override fun onPlay() { playBoth() }
                override fun onPause() { pauseAll() }
                override fun onStop() { 
                    pauseAll()
                    stopForeground(true)
                    stopSelf()
                }
                override fun onSkipToNext() { skipLeft(true); skipRight(true) }
                override fun onSkipToPrevious() { skipLeft(false); skipRight(false) }
                override fun onSeekTo(pos: Long) {
                    seekLeft(pos.toInt())
                    if (!isMergedMode) seekRight(pos.toInt())
                }
            })
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            isActive = true
        }
        updatePlaybackState()
    }

    private fun requestAudioFocus(): Boolean {
        if (isAudioFocusGranted) return true
        
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioManager.requestAudioFocus(focusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(audioFocusChangeListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        
        isAudioFocusGranted = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return isAudioFocusGranted
    }

    private fun abandonAudioFocus() {
        if (!isAudioFocusGranted) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
        isAudioFocusGranted = false
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Dual Music Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controls for Left and Right earbud music"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private val updateProgressTask = object : Runnable {
        override fun run() {
            if (isLeftPlaying() || (isRightPlaying() && !isMergedMode)) {
                updateNotification()
                updatePlaybackState()
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun updatePlaybackState() {
        val leftPlaying = isLeftPlaying()
        val rightPlaying = isRightPlaying()
        
        // In Merge Mode, we only care if Left is playing
        val state = if (isMergedMode) {
            if (leftPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        } else {
            if (leftPlaying || rightPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        }
        
        val activePos = when {
            isMergedMode -> getLeftProgress().toLong()
            leftPlaying -> getLeftProgress().toLong()
            rightPlaying -> getRightProgress().toLong()
            else -> 0L
        }

        val actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or 
                     PlaybackStateCompat.ACTION_PLAY or 
                     PlaybackStateCompat.ACTION_PAUSE or
                     PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                     PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                     PlaybackStateCompat.ACTION_STOP or
                     PlaybackStateCompat.ACTION_SEEK_TO

        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, activePos, 1.0f)
                .setActions(actions)
                .build()
        )
        
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, if (isMergedMode) "[Merged] $titleLeft" else titleLeft)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, if (isMergedMode) "Left Priority" else titleRight)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, "Dual Music Player")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, (if (isMergedMode) getLeftDuration() else Math.max(getLeftDuration(), getRightDuration())).toLong())
            .build()
        mediaSession?.setMetadata(metadata)
    }

    fun setMergeMode(merged: Boolean) {
        isMergedMode = merged
        updateVolume()
        updateNotification()
        updatePlaybackState()
    }

    private fun updateVolume() {
        if (isMergedMode) {
            // Priority to Left: Left player plays in both ears, Right player is muted
            playerLeft?.setVolume(1.0f, 1.0f)
            playerRight?.setVolume(0.0f, 0.0f)
        } else {
            playerLeft?.setVolume(1.0f, 0.0f)
            playerRight?.setVolume(0.0f, 1.0f)
        }
    }

    fun playLeft(uri: Uri, title: String, isManual: Boolean = true) {
        if (isManual) {
            currentLeftMusic = MusicFile(uri, title, uri.toString())
        }
        if (requestAudioFocus()) {
            try {
                playerLeft?.stop()
                playerLeft?.release()
            } catch (e: Exception) {}
            playerLeft = null
            
            titleLeft = title
            try {
                val mp = MediaPlayer.create(this, uri)
                if (mp != null) {
                    playerLeft = mp
                    mp.setOnCompletionListener { 
                        if (unplayedLeftList.isNotEmpty() || playedLeftList.isNotEmpty()) {
                            playNextFromQueue(true)
                        } else {
                            if (!isRightPlaying() || isMergedMode) abandonAudioFocus()
                            updateNotification()
                            updatePlaybackState()
                        }
                    }
                    mp.setOnErrorListener { _, _, _ ->
                        playerLeft?.release()
                        playerLeft = null
                        if (!isRightPlaying() || isMergedMode) abandonAudioFocus()
                        updateNotification()
                        updatePlaybackState()
                        true
                    }
                    updateVolume()
                    mp.start()
                } else {
                    titleLeft = "Load Failed: $title"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            updateNotification()
            updatePlaybackState()
        }
    }

    fun playRight(uri: Uri, title: String, isManual: Boolean = true) {
        if (isManual) {
            currentRightMusic = MusicFile(uri, title, uri.toString())
        }
        if (requestAudioFocus()) {
            try {
                playerRight?.stop()
                playerRight?.release()
            } catch (e: Exception) {}
            playerRight = null
            
            titleRight = title
            try {
                val mp = MediaPlayer.create(this, uri)
                if (mp != null) {
                    playerRight = mp
                    mp.setOnCompletionListener { 
                        if (unplayedRightList.isNotEmpty() || playedRightList.isNotEmpty()) {
                            playNextFromQueue(false)
                        } else {
                            if (!isLeftPlaying()) abandonAudioFocus()
                            updateNotification()
                            updatePlaybackState()
                        }
                    }
                    mp.setOnErrorListener { _, _, _ ->
                        playerRight?.release()
                        playerRight = null
                        if (!isLeftPlaying()) abandonAudioFocus()
                        updateNotification()
                        updatePlaybackState()
                        true
                    }
                    updateVolume()
                    mp.start()
                } else {
                    titleRight = "Load Failed: $title"
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            updateNotification()
            updatePlaybackState()
        }
    }

    fun toggleLeft() {
        playerLeft?.let {
            try {
                if (it.isPlaying) {
                    it.pause()
                    if (!isRightPlaying() || isMergedMode) abandonAudioFocus()
                } else {
                    if (requestAudioFocus()) it.start()
                }
            } catch (e: Exception) {}
            updateNotification()
            updatePlaybackState()
        }
    }

    fun toggleRight() {
        playerRight?.let {
            try {
                if (it.isPlaying) {
                    it.pause()
                    if (!isLeftPlaying()) abandonAudioFocus()
                } else {
                    if (requestAudioFocus()) it.start()
                }
            } catch (e: Exception) {}
            updateNotification()
            updatePlaybackState()
        }
    }

    private fun smartToggle() {
        val leftPlaying = isLeftPlaying()
        val rightPlaying = isRightPlaying()
        
        if (isMergedMode) {
            if (leftPlaying) playerLeft?.pause() else if (requestAudioFocus()) playerLeft?.start()
        } else {
            if (leftPlaying || rightPlaying) {
                pauseAll()
            } else {
                playBoth()
            }
        }
        updateNotification()
        updatePlaybackState()
    }

    fun toggleBoth() = smartToggle()

    private fun playBoth() {
        if (requestAudioFocus()) {
            try {
                playerLeft?.let { if (!it.isPlaying) it.start() }
                if (!isMergedMode) {
                    playerRight?.let { if (!it.isPlaying) it.start() }
                }
            } catch (e: Exception) {}
            updateNotification()
            updatePlaybackState()
        }
    }

    fun pauseAll() {
        try {
            playerLeft?.let { if (it.isPlaying) it.pause() }
            playerRight?.let { if (it.isPlaying) it.pause() }
            abandonAudioFocus()
        } catch (e: Exception) {}
        updateNotification()
        updatePlaybackState()
    }

    fun skipLeft(forward: Boolean) {
        playerLeft?.let {
            try {
                val pos = if (forward) it.currentPosition + 10000 else it.currentPosition - 10000
                it.seekTo(pos.coerceIn(0, it.duration))
            } catch (e: Exception) {}
            updateNotification()
            updatePlaybackState()
        }
    }

    fun skipRight(forward: Boolean) {
        playerRight?.let {
            try {
                val pos = if (forward) it.currentPosition + 10000 else it.currentPosition - 10000
                it.seekTo(pos.coerceIn(0, it.duration))
            } catch (e: Exception) {}
            updateNotification()
            updatePlaybackState()
        }
    }

    fun isLeftPlaying() = try { playerLeft?.isPlaying ?: false } catch (e: Exception) { false }
    fun isRightPlaying() = try { playerRight?.isPlaying ?: false } catch (e: Exception) { false }
    fun getLeftProgress() = try { playerLeft?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    fun getLeftDuration() = try { playerLeft?.duration ?: 0 } catch (e: Exception) { 0 }
    fun getRightProgress() = try { playerRight?.currentPosition ?: 0 } catch (e: Exception) { 0 }
    fun getRightDuration() = try { playerRight?.duration ?: 0 } catch (e: Exception) { 0 }
    
    fun getTitleLeft() = titleLeft
    fun getTitleRight() = titleRight
    fun isMerged() = isMergedMode

    fun seekLeft(pos: Int) { 
        try { 
            playerLeft?.seekTo(pos) 
            updatePlaybackState()
        } catch (e: Exception) {} 
    }
    fun seekRight(pos: Int) { 
        try { 
            playerRight?.seekTo(pos) 
            updatePlaybackState()
        } catch (e: Exception) {} 
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
    }

    private fun buildNotification(): Notification {
        val notificationLayout = RemoteViews(packageName, R.layout.notification_dual_music)
        
        notificationLayout.setTextViewText(R.id.noti_title_left, titleLeft)
        notificationLayout.setTextViewText(R.id.noti_title_right, if (isMergedMode) "[MERGED]" else titleRight)

        notificationLayout.setImageViewResource(R.id.noti_btn_play_left, 
            if (isLeftPlaying()) R.drawable.ic_pause else R.drawable.ic_play_arrow)
        notificationLayout.setImageViewResource(R.id.noti_btn_play_right, 
            if (isRightPlaying()) R.drawable.ic_pause else R.drawable.ic_play_arrow)

        val leftDur = getLeftDuration()
        val leftPos = getLeftProgress()
        notificationLayout.setProgressBar(R.id.noti_progress_left, if (leftDur > 0) leftDur else 100, leftPos, false)
        notificationLayout.setTextViewText(R.id.noti_time_left, "${formatTime(leftPos)} / ${formatTime(leftDur)}")

        val rightDur = getRightDuration()
        val rightPos = getRightProgress()
        notificationLayout.setProgressBar(R.id.noti_progress_right, if (rightDur > 0) rightDur else 100, rightPos, false)
        notificationLayout.setTextViewText(R.id.noti_time_right, if (isMergedMode) "PRIORITY: LEFT" else "${formatTime(rightPos)} / ${formatTime(rightDur)}")

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        
        notificationLayout.setOnClickPendingIntent(R.id.noti_btn_play_left, 
            PendingIntent.getService(this, 1, Intent(this, DualMusicService::class.java).apply { action = "TOGGLE_LEFT" }, pendingIntentFlags))
        notificationLayout.setOnClickPendingIntent(R.id.noti_btn_play_right, 
            PendingIntent.getService(this, 2, Intent(this, DualMusicService::class.java).apply { action = "TOGGLE_RIGHT" }, pendingIntentFlags))
        
        notificationLayout.setOnClickPendingIntent(R.id.noti_btn_prev_left, 
            PendingIntent.getService(this, 3, Intent(this, DualMusicService::class.java).apply { action = "PREV_LEFT" }, pendingIntentFlags))
        notificationLayout.setOnClickPendingIntent(R.id.noti_btn_next_left, 
            PendingIntent.getService(this, 4, Intent(this, DualMusicService::class.java).apply { action = "NEXT_LEFT" }, pendingIntentFlags))
        
        notificationLayout.setOnClickPendingIntent(R.id.noti_btn_prev_right, 
            PendingIntent.getService(this, 5, Intent(this, DualMusicService::class.java).apply { action = "PREV_RIGHT" }, pendingIntentFlags))
        notificationLayout.setOnClickPendingIntent(R.id.noti_btn_next_right, 
            PendingIntent.getService(this, 6, Intent(this, DualMusicService::class.java).apply { action = "NEXT_RIGHT" }, pendingIntentFlags))

        val mainIntent = Intent(this, MainActivity::class.java).apply { setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val mainPendingIntent = PendingIntent.getActivity(this, 0, mainIntent, pendingIntentFlags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_audio)
            .setCustomContentView(notificationLayout)
            .setCustomBigContentView(notificationLayout)
            .setContentIntent(mainPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isLeftPlaying() || (isRightPlaying() && !isMergedMode))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        mediaSession?.let {
            builder.setStyle(androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(it.sessionToken)
                .setShowActionsInCompactView(0, 1))
        }

        return builder.build()
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (MediaButtonReceiver.handleIntent(mediaSession, intent) != null) {
            return START_STICKY
        }

        when (intent?.action) {
            "TOGGLE_LEFT" -> toggleLeft()
            "TOGGLE_RIGHT" -> toggleRight()
            "PREV_LEFT" -> skipLeft(false)
            "NEXT_LEFT" -> skipLeft(true)
            "PREV_RIGHT" -> skipRight(false)
            "NEXT_RIGHT" -> skipRight(true)
            "PAUSE_ALL" -> pauseAll()
            "STOP_SERVICE" -> {
                pauseAll()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(updateProgressTask)
        pauseAll()
        playerLeft?.release()
        playerRight?.release()
        mediaSession?.release()
        super.onDestroy()
    }
}
