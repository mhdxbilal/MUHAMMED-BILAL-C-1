package com.continental.player

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.continental.player.base.BaseActivity
import com.continental.player.data.OrientationMode
import com.continental.player.data.ResumeStore
import com.continental.player.data.ResizeMode
import com.continental.player.databinding.ActivityPlayerBinding
import com.continental.player.player.AudioEffectsManager
import com.continental.player.player.PlayerEngine
import com.continental.player.player.PlayerGestureHelper
import com.continental.player.player.TrackSelectionHelper
import com.continental.player.util.FormatUtils

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerActivity : BaseActivity(), PlayerGestureHelper.Listener {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var resumeStore: ResumeStore
    private lateinit var audioEffects: AudioEffectsManager

    private val player by lazy { PlayerEngine.createPlayer(this) }

    // Queue state
    private var queue: ArrayList<String> = arrayListOf()   // list of URI strings
    private var queueIndex: Int = 0
    private var currentTitle: String = ""
    private var mediaSession: androidx.media3.session.MediaSession? = null

    // UI state
    private var controlsLocked = false
    private var isResizeCycleMode = false
    private var isScrubbing = false
    private val gestureIndicatorHandler = Handler(Looper.getMainLooper())
    private val gestureIndicatorHide = Runnable {
        binding.tvGestureIndicator.animate().alpha(0f).setDuration(250).withEndAction {
            binding.tvGestureIndicator.isVisible = false
            binding.tvGestureIndicator.alpha = 1f
        }.start()
        binding.pbVolume.animate().alpha(0f).setDuration(250).withEndAction {
            binding.pbVolume.isVisible = false
            binding.pbVolume.alpha = 1f
        }.start()
        binding.pbBrightness.animate().alpha(0f).setDuration(250).withEndAction {
            binding.pbBrightness.isVisible = false
            binding.pbBrightness.alpha = 1f
        }.start()
    }
    private val progressUpdateRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            if (player.isPlaying) {
                gestureIndicatorHandler.postDelayed(this, 1000)
            }
        }
    }

    private val subtitlePickerLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            loadExternalSubtitle(uri)
        }
    }

    private val addFilesLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            for (uri in uris) {
                contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                queue.add(uri.toString())
            }
            if (queue.size == uris.size) {
                // Was empty, so start playing the first one
                queueIndex = 0
                loadCurrentQueueItem()
            }
            binding.rvPlaylist.adapter?.notifyDataSetChanged()
        }
    }

    private fun setupEqualizerOverlay() {
        binding.btnCloseEqualizer.setOnClickListener {
            binding.equalizerContainer.animate().alpha(0f).setDuration(300).withEndAction {
                binding.equalizerContainer.isVisible = false
            }.start()
        }
    }
    
    private fun showEqualizer() {
        val eq = audioEffects.getEqualizer() ?: return
        val bands = eq.numberOfBands
        val minEQLevel = eq.bandLevelRange[0]
        val maxEQLevel = eq.bandLevelRange[1]
        
        binding.equalizerBands.removeAllViews()
        for (i in 0 until bands) {
            val bandIndex = i.toShort()
            val freq = eq.getCenterFreq(bandIndex) / 1000 // mHz to Hz
            
            val layout = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            }
            
            val seekBar = android.widget.SeekBar(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 300
                )
                max = maxEQLevel - minEQLevel
                progress = eq.getBandLevel(bandIndex) - minEQLevel
                
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: android.widget.SeekBar, progress: Int, fromUser: Boolean) {
                        if (fromUser) {
                            eq.setBandLevel(bandIndex, (progress + minEQLevel).toShort())
                        }
                    }
                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar) {}
                })
            }
            // Vertical seekbar hack since native one doesn't exist
            seekBar.rotation = 270f
            
            val freqText = android.widget.TextView(this).apply {
                text = "${freq}Hz"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 12f
                gravity = android.view.Gravity.CENTER
                setPadding(0, 16, 0, 0)
            }
            
            val dummyContainer = android.widget.FrameLayout(this).apply {
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 300
                )
                addView(seekBar)
            }
            
            layout.addView(dummyContainer)
            layout.addView(freqText)
            binding.equalizerBands.addView(layout)
        }
        
        binding.equalizerContainer.apply {
            alpha = 0f
            isVisible = true
            animate().alpha(1f).setDuration(300).setListener(null).start()
        }
    }
    
    private fun setupPlaylistDrawer() {
        binding.rvPlaylist.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this)
        binding.rvPlaylist.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
                val tv = android.widget.TextView(parent.context).apply {
                    setPadding(32, 32, 32, 32)
                    setTextColor(android.graphics.Color.WHITE)
                    textSize = 16f
                    layoutParams = android.view.ViewGroup.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
                return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(tv) {}
            }

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val tv = holder.itemView as android.widget.TextView
                val uriString = queue[position]
                val name = android.net.Uri.parse(uriString).lastPathSegment ?: "Unknown Video"
                tv.text = if (position == queueIndex) "▶ $name" else name
                tv.setTextColor(if (position == queueIndex) getColor(R.color.continental_gold) else android.graphics.Color.WHITE)
                
                tv.setOnClickListener {
                    val actualPos = holder.bindingAdapterPosition
                    if (actualPos != androidx.recyclerview.widget.RecyclerView.NO_POSITION) {
                        queueIndex = actualPos
                        loadCurrentQueueItem()
                        binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.END)
                    }
                }
            }

            override fun getItemCount() = queue.size
        }

        binding.btnAddFiles.setOnClickListener {
            addFilesLauncher.launch(arrayOf("video/*"))
        }
    }

    // Companion extras
    companion object {
        const val EXTRA_URI_LIST = "uri_list"
        const val EXTRA_QUEUE_INDEX = "queue_index"
        const val EXTRA_TITLE = "title"

        fun startWithUri(from: android.content.Context, uri: Uri, title: String) {
            val intent = Intent(from, PlayerActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_URI_LIST, arrayListOf(uri.toString()))
                putExtra(EXTRA_QUEUE_INDEX, 0)
                putExtra(EXTRA_TITLE, title)
            }
            from.startActivity(intent)
        }

        fun startWithQueue(from: android.content.Context, uris: List<Uri>, index: Int, title: String) {
            val intent = Intent(from, PlayerActivity::class.java).apply {
                putStringArrayListExtra(EXTRA_URI_LIST, ArrayList(uris.map { it.toString() }))
                putExtra(EXTRA_QUEUE_INDEX, index)
                putExtra(EXTRA_TITLE, title)
            }
            from.startActivity(intent)
        }
    }

    // ─────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        resumeStore = ResumeStore.getInstance(this)
        audioEffects = AudioEffectsManager(settings)
        mediaSession = androidx.media3.session.MediaSession.Builder(this, player).build()

        applyOrientationFromSettings()
        applyKeepScreenOn()
        setupPlayerView()
        setupSeekBar()
        setupVolumeBar()
        wireControlButtons()
        setupGestureOverlay()
        setupPlaylistDrawer()
        setupEqualizerOverlay()

        // Handle "Open with The Continental" from a file manager
        val viewUri = intent?.data
        if (viewUri != null) {
            queue = arrayListOf(viewUri.toString())
            queueIndex = 0
            currentTitle = intent.getStringExtra(EXTRA_TITLE)
                ?: viewUri.lastPathSegment ?: "Video"
        } else {
            queue = intent?.getStringArrayListExtra(EXTRA_URI_LIST) ?: arrayListOf()
            queueIndex = intent?.getIntExtra(EXTRA_QUEUE_INDEX, 0) ?: 0
            currentTitle = intent?.getStringExtra(EXTRA_TITLE) ?: ""
        }

        loadCurrentQueueItem()
    }

    override fun onStart() {
        super.onStart()
        binding.playerView.onResume()
    }

    override fun onStop() {
        super.onStop()
        persistResumePosition()
        binding.playerView.onPause()
        if (!isInPictureInPictureMode) {
            player.pause()
        }
    }

    override fun onDestroy() {
        persistResumePosition()
        mediaSession?.release()
        audioEffects.release()
        player.release()
        super.onDestroy()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        binding.playerView.useController = !isInPictureInPictureMode
        binding.gestureOverlay.isVisible = !isInPictureInPictureMode
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player.isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }

    private var performanceOverlayEnabled = false
    private var droppedFrames = 0
    private var decoderName = ""
    private var videoFormat = ""
    private var audioFormat = ""
    
    private val analyticsListener = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@PlayerActivity.droppedFrames += droppedFrames
            updatePerformanceDashboard()
        }
        
        override fun onVideoDecoderInitialized(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            this@PlayerActivity.decoderName = decoderName
            updatePerformanceDashboard()
        }
        
        override fun onVideoInputFormatChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
            this@PlayerActivity.videoFormat = "${format.width}x${format.height} @ ${format.frameRate}fps ${format.sampleMimeType}"
            updatePerformanceDashboard()
        }
        
        override fun onAudioInputFormatChanged(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime, format: androidx.media3.common.Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
            this@PlayerActivity.audioFormat = "${format.sampleRate}Hz ${format.channelCount}ch ${format.sampleMimeType}"
            updatePerformanceDashboard()
        }
    }

    private fun updatePerformanceDashboard() {
        if (!performanceOverlayEnabled) return
        val runtime = Runtime.getRuntime()
        val usedMemInMB = (runtime.totalMemory() - runtime.freeMemory()) / 1048576L
        val maxHeapSizeInMB = runtime.maxMemory() / 1048576L
        val memoryStats = "MEM: ${usedMemInMB}MB / ${maxHeapSizeInMB}MB"
        val statsText = "DECODER: $decoderName\nVIDEO: $videoFormat\nAUDIO: $audioFormat\nDROPPED: $droppedFrames frames\n$memoryStats"
        binding.tvPerformanceDashboard.text = statsText
    }

    private val performanceUpdateRunnable = object : Runnable {
        override fun run() {
            updatePerformanceDashboard()
            if (performanceOverlayEnabled) {
                gestureIndicatorHandler.postDelayed(this, 1000)
            }
        }
    }

    private fun togglePerformanceDashboard() {
        performanceOverlayEnabled = !performanceOverlayEnabled
        if (performanceOverlayEnabled) {
            gestureIndicatorHandler.post(performanceUpdateRunnable)
            binding.tvPerformanceDashboard.apply {
                alpha = 0f
                isVisible = true
                animate()
                    .alpha(1f)
                    .setDuration(300)
                    .setListener(null)
                    .start()
            }
        } else {
            gestureIndicatorHandler.removeCallbacks(performanceUpdateRunnable)
            binding.tvPerformanceDashboard.animate()
                .alpha(0f)
                .setDuration(300)
                .withEndAction {
                    binding.tvPerformanceDashboard.isVisible = false
                }
                .start()
        }
    }

    // ─────────────────────────────────────────────
    // Player setup
    // ─────────────────────────────────────────────

    private fun setupPlayerView() {
        binding.playerView.player = player
        applyResizeModeFromSettings()
        
        if (player is androidx.media3.exoplayer.ExoPlayer) {
            (player as androidx.media3.exoplayer.ExoPlayer).addAnalyticsListener(analyticsListener)
        }

        player.addListener(object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (settings.resizeMode == ResizeMode.AUTO_FIT) {
                    updateAutoFitScale()
                }
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val sessionId = player.audioSessionId
                    if (sessionId != 0) audioEffects.attach(sessionId)
                    syncSpeedButton()
                    updateProgress()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                applyKeepScreenOn()
                if (isPlaying && !isScrubbing) {
                    gestureIndicatorHandler.post(progressUpdateRunnable)
                } else {
                    gestureIndicatorHandler.removeCallbacks(progressUpdateRunnable)
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                queueIndex = player.currentMediaItemIndex
                currentTitle = mediaItem?.mediaMetadata?.title?.toString()
                    ?: queue.getOrNull(queueIndex)?.let { Uri.parse(it).lastPathSegment }
                    ?: ""
                binding.playerView.findViewById<TextView>(R.id.tvVideoTitle)?.text = currentTitle
                if (settings.rememberPlaybackSpeed) {
                    player.playbackParameters = PlaybackParameters(settings.lastPlaybackSpeed)
                    syncSpeedButton()
                }
                binding.rvPlaylist.adapter?.notifyDataSetChanged()
            }

            override fun onPlayerError(error: PlaybackException) {
                showErrorOverlay(error)
            }
        })
    }

    private fun loadCurrentQueueItem() {
        if (queue.isEmpty()) { finish(); return }

        val mediaItems = queue.map { uriString ->
            val uri = Uri.parse(uriString)
            val name = (if (uriString == queue[queueIndex] && currentTitle.isNotEmpty()) currentTitle else uri.lastPathSegment) ?: "Video"
            val metadata = androidx.media3.common.MediaMetadata.Builder()
                .setTitle(name)
                .build()
            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(metadata)
                .build()
        }
        player.setMediaItems(mediaItems, queueIndex, 0L)

        // Check for a saved resume position
        val uriString = queue[queueIndex]
        val resumeEntry = resumeStore.getEntry(uriString)

        if (resumeEntry != null && resumeEntry.positionMs > 0) {
            if (settings.autoResumeWithoutPrompt) {
                player.seekTo(queueIndex, resumeEntry.positionMs)
                player.prepare()
                player.play()
            } else {
                showResumeDialog(resumeEntry.positionMs)
            }
        } else {
            player.prepare()
            player.play()
        }

        binding.playerView.findViewById<TextView>(R.id.tvVideoTitle)?.text = currentTitle
        if (settings.rememberPlaybackSpeed) {
            player.playbackParameters = PlaybackParameters(settings.lastPlaybackSpeed)
        }
    }

    // ─────────────────────────────────────────────
    // Seekbar & Progress
    // ─────────────────────────────────────────────

    private fun updateProgress() {
        if (isScrubbing) return
        val seekBar = binding.playerView.findViewById<android.widget.SeekBar>(R.id.realtime_progress) ?: return
        val tvPosition = binding.playerView.findViewById<TextView>(R.id.exo_position)
        val tvDuration = binding.playerView.findViewById<TextView>(R.id.exo_duration)

        val durationMs = player.duration.takeIf { it > 0 } ?: 0L
        val positionMs = player.currentPosition.takeIf { it >= 0 } ?: 0L
        val bufferedMs = player.bufferedPosition.takeIf { it >= 0 } ?: 0L

        seekBar.max = durationMs.toInt()
        seekBar.progress = positionMs.toInt()
        seekBar.secondaryProgress = bufferedMs.toInt()

        tvPosition?.text = FormatUtils.formatDuration(positionMs)
        tvDuration?.text = FormatUtils.formatDuration(durationMs)
    }

    private fun setupSeekBar() {
        val seekBar = binding.playerView.findViewById<android.widget.SeekBar>(R.id.realtime_progress)
        val tvPosition = binding.playerView.findViewById<TextView>(R.id.exo_position)

        seekBar?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    tvPosition?.text = FormatUtils.formatDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {
                isScrubbing = true
                gestureIndicatorHandler.removeCallbacks(progressUpdateRunnable)
            }

            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                isScrubbing = false
                val p = sb?.progress?.toLong() ?: 0L
                player.seekTo(p)
                updateProgress()
                if (player.isPlaying) {
                    gestureIndicatorHandler.post(progressUpdateRunnable)
                }
            }
        })
    }

    private fun setupVolumeBar() {
        val volumeBar = binding.playerView.findViewById<android.widget.SeekBar>(R.id.seekVolume)
        volumeBar?.max = 100
        volumeBar?.progress = (player.volume * 100).toInt()

        volumeBar?.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    player.volume = progress / 100f
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    // ─────────────────────────────────────────────
    // Control bar wiring
    // ─────────────────────────────────────────────

    private fun wireControlButtons() {
        val pv = binding.playerView

        // Back — pause, save position, finish
        pv.findViewById<View>(R.id.btnBack)?.setOnClickListener {
            persistResumePosition()
            finish()
        }

        // Lock / unlock screen controls
        pv.findViewById<View>(R.id.btnLock)?.setOnClickListener {
            controlsLocked = !controlsLocked
            applyLockState()
        }

        // More options popup
        pv.findViewById<View>(R.id.btnMore)?.setOnClickListener { anchor ->
            showMoreOptionsMenu(anchor)
        }
        
        pv.findViewById<View>(R.id.btnPlaylist)?.setOnClickListener {
            binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.END)
        }

        // Speed text button in bottom bar
        pv.findViewById<View>(R.id.btnSpeed)?.setOnClickListener {
            showSpeedPicker()
        }

        // Fullscreen toggle button
        pv.findViewById<android.widget.ImageButton>(R.id.btnFullscreenToggle)?.apply {
            setImageResource(if (settings.fullscreenImmersive) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
            setOnClickListener {
                settings.fullscreenImmersive = !settings.fullscreenImmersive
                setImageResource(if (settings.fullscreenImmersive) R.drawable.ic_fullscreen_exit else R.drawable.ic_fullscreen)
                applyImmersiveMode()
            }
        }

        // Error overlay buttons
        binding.btnErrorRetry.setOnClickListener {
            binding.llErrorOverlay.animate().alpha(0f).setDuration(300).withEndAction { binding.llErrorOverlay.isVisible = false }.start()
            player.prepare()
            player.play()
        }
        binding.btnErrorSkip.setOnClickListener {
            binding.llErrorOverlay.animate().alpha(0f).setDuration(300).withEndAction { binding.llErrorOverlay.isVisible = false }.start()
            if (queueIndex < queue.size - 1) {
                queueIndex++
                player.seekToNextMediaItem()
            } else {
                finish()
            }
        }
    }

    private fun applyLockState() {
        val pv = binding.playerView
        pv.useController = !controlsLocked
        val lockBtn = pv.findViewById<android.widget.ImageButton>(R.id.btnLock)
        lockBtn?.setImageResource(
            if (controlsLocked) R.drawable.ic_lock else R.drawable.ic_lock_open
        )
        // Keep the lock button itself always visible even when everything else is hidden
        lockBtn?.isVisible = true
    }

    // ─────────────────────────────────────────────
    // More Options menu
    // ─────────────────────────────────────────────

    private fun showMoreOptionsMenu(anchor: View) {
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_player_more, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_audio_track -> { showAudioTrackDialog(); true }
                R.id.action_subtitles -> { showSubtitleDialog(); true }
                R.id.action_resize_mode -> { cycleResizeMode(); true }
                R.id.action_orientation -> { showOrientationDialog(); true }
                R.id.action_audio_effects -> { showAudioEffectsDialog(); true }
                R.id.action_equalizer -> { showEqualizer(); true }
                R.id.action_performance -> { togglePerformanceDashboard(); true }
                R.id.action_pip -> { enterPip(); true }
                else -> false
            }
        }
        popup.show()
    }

    // ─────────────────────────────────────────────
    // Track selection dialogs
    // ─────────────────────────────────────────────

    private fun showAudioTrackDialog() {
        val tracks = player.currentTracks
        val groups = TrackSelectionHelper.audioGroups(tracks)
        if (groups.isEmpty()) {
            toast(getString(R.string.toast_no_track_available)); return
        }
        val labels = mutableListOf<String>()
        val groupIndices = mutableListOf<Pair<Int, Int>>() // (groupIndex, trackIndex)
        groups.forEachIndexed { gi, group ->
            for (ti in 0 until group.length) {
                labels.add(TrackSelectionHelper.trackLabel(group.getTrackFormat(ti), ti))
                groupIndices.add(Pair(gi, ti))
            }
        }
        var selected = groupIndices.indexOfFirst { (gi, ti) ->
            TrackSelectionHelper.isTrackSelected(groups[gi], ti)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.action_audio_track)
            .setSingleChoiceItems(labels.toTypedArray(), selected) { _, which ->
                selected = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val (gi, ti) = groupIndices[selected]
                TrackSelectionHelper.selectTrack(player, groups[gi], ti)
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .applyGoldTheme()
    }

    private fun showSubtitleDialog() {
        val tracks = player.currentTracks
        val groups = TrackSelectionHelper.textGroups(tracks)
        val labelItems = mutableListOf(getString(R.string.subtitles_off))
        val groupIndices = mutableListOf<Pair<Int, Int>?>()
        groupIndices.add(null) // "Off" option
        groups.forEachIndexed { gi, group ->
            for (ti in 0 until group.length) {
                labelItems.add(TrackSelectionHelper.trackLabel(group.getTrackFormat(ti), ti))
                groupIndices.add(Pair(gi, ti))
            }
        }
        // Detect if subs are currently disabled
        val textDisabled = player.trackSelectionParameters.disabledTrackTypes.contains(
            androidx.media3.common.C.TRACK_TYPE_TEXT
        )
        var selected = if (textDisabled || groups.isEmpty()) 0 else {
            groupIndices.indexOfFirst { pair ->
                pair != null && TrackSelectionHelper.isTrackSelected(groups[pair.first], pair.second)
            }.takeIf { it >= 0 } ?: 0
        }
        val loadSubtitleIndex = labelItems.size
        labelItems.add(getString(R.string.action_add_subtitle_file))
        
        AlertDialog.Builder(this)
            .setTitle(R.string.action_subtitles)
            .setSingleChoiceItems(labelItems.toTypedArray(), selected) { _, which -> selected = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (selected == loadSubtitleIndex) {
                    subtitlePickerLauncher.launch(arrayOf("*/*"))
                } else {
                    val pair = groupIndices[selected]
                    if (pair == null) {
                        TrackSelectionHelper.disableTextTracks(player)
                    } else {
                        TrackSelectionHelper.enableTextTracks(player)
                        TrackSelectionHelper.selectTrack(player, groups[pair.first], pair.second)
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .applyGoldTheme()
    }

    private fun loadExternalSubtitle(uri: android.net.Uri) {
        val currentItem = player.currentMediaItem ?: return
        
        val mimeType = when {
            uri.path?.endsWith(".vtt", ignoreCase = true) == true -> androidx.media3.common.MimeTypes.TEXT_VTT
            uri.path?.endsWith(".ass", ignoreCase = true) == true || uri.path?.endsWith(".ssa", ignoreCase = true) == true -> androidx.media3.common.MimeTypes.TEXT_SSA
            else -> androidx.media3.common.MimeTypes.APPLICATION_SUBRIP
        }

        val subtitleConfig = androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mimeType)
            .setLanguage("en")
            .setSelectionFlags(androidx.media3.common.C.SELECTION_FLAG_DEFAULT)
            .setId(uri.toString())
            .build()
        
        val existingConfigs = currentItem.localConfiguration?.subtitleConfigurations ?: emptyList()
        val newConfigs = existingConfigs + subtitleConfig
        
        val newItem = currentItem.buildUpon()
            .setSubtitleConfigurations(newConfigs)
            .build()
            
        val currentPos = player.currentPosition
        player.replaceMediaItem(player.currentMediaItemIndex, newItem)
        TrackSelectionHelper.enableTextTracks(player)
        // Automatically select the newly added track once it loads.
        
        android.widget.Toast.makeText(this, R.string.toast_added_subtitle, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ─────────────────────────────────────────────
    // Speed picker
    // ─────────────────────────────────────────────

    private fun showSpeedPicker() {
        val speeds = arrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)
        val labels = speeds.map { "${it}x" }.toTypedArray()
        val current = player.playbackParameters.speed
        var selected = speeds.indexOfFirst { Math.abs(it - current) < 0.01f }.takeIf { it >= 0 } ?: 3
        AlertDialog.Builder(this)
            .setTitle(R.string.action_playback_speed)
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val speed = speeds[selected]
                player.playbackParameters = PlaybackParameters(speed)
                if (settings.rememberPlaybackSpeed) settings.lastPlaybackSpeed = speed
                syncSpeedButton()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .applyGoldTheme()
    }

    private fun syncSpeedButton() {
        val speed = player.playbackParameters.speed
        val label = if (speed == 1.0f) "1.0x" else "${speed}x"
        binding.playerView.findViewById<TextView>(R.id.btnSpeed)?.text = label
    }

    // ─────────────────────────────────────────────
    // Resize / aspect ratio cycling
    // ─────────────────────────────────────────────

    private fun cycleResizeMode() {
        val nextMode = when (settings.resizeMode) {
            ResizeMode.FIT -> ResizeMode.FILL
            ResizeMode.FILL -> ResizeMode.ZOOM
            ResizeMode.ZOOM -> ResizeMode.AUTO_FIT
            ResizeMode.AUTO_FIT -> ResizeMode.FIT
        }
        settings.resizeMode = nextMode
        applyResizeModeFromSettings()
        
        val label = when (nextMode) {
            ResizeMode.FILL -> getString(R.string.resize_fill)
            ResizeMode.ZOOM -> getString(R.string.resize_zoom)
            ResizeMode.AUTO_FIT -> "Auto-Fit"
            ResizeMode.FIT -> getString(R.string.resize_fit)
        }
        showGestureIndicator("⬛ $label")
    }

    private fun applyResizeModeFromSettings() {
        when (settings.resizeMode) {
            ResizeMode.FILL -> binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
            ResizeMode.ZOOM -> binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            ResizeMode.FIT -> binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            ResizeMode.AUTO_FIT -> updateAutoFitScale()
        }
    }

    private fun updateAutoFitScale() {
        if (settings.resizeMode != ResizeMode.AUTO_FIT) return
        val videoFormat = player.videoFormat ?: return
        val videoWidth = videoFormat.width
        val videoHeight = videoFormat.height
        if (videoWidth <= 0 || videoHeight <= 0) {
             binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
             return
        }
        val videoRatio = videoWidth.toFloat() / videoHeight.toFloat()
        val screenWidth = binding.playerView.width
        val screenHeight = binding.playerView.height
        if (screenWidth <= 0 || screenHeight <= 0) {
             binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
             return
        }
        val screenRatio = screenWidth.toFloat() / screenHeight.toFloat()
        
        val diff = Math.abs(videoRatio / screenRatio - 1f)
        if (diff < 0.2f) {
             binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
             binding.playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    // ─────────────────────────────────────────────
    // Orientation
    // ─────────────────────────────────────────────

    private fun applyOrientationFromSettings() {
        requestedOrientation = when (settings.orientationMode) {
            OrientationMode.LOCKED_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            OrientationMode.LOCKED_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            OrientationMode.AUTO_SENSOR -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
            OrientationMode.FOLLOW_SYSTEM -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    private fun showOrientationDialog() {
        val entries = OrientationMode.entries
        val labels = entries.map { it.label }.toTypedArray()
        var selected = entries.indexOf(settings.orientationMode).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.pref_orientation_title))
            .setSingleChoiceItems(labels, selected) { _, which -> selected = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                settings.orientationMode = entries[selected]
                applyOrientationFromSettings()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .applyGoldTheme()
    }

    // ─────────────────────────────────────────────
    // Audio effects dialog
    // ─────────────────────────────────────────────

    private fun showAudioEffectsDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_audio_effects, null)
        val switchBass = view.findViewById<android.widget.Switch>(R.id.switchBassBoost)
        val seekBass = view.findViewById<android.widget.SeekBar>(R.id.seekBassStrength)
        val switchVirt = view.findViewById<android.widget.Switch>(R.id.switchVirtualizer)

        switchBass.isChecked = settings.bassBoostEnabled
        seekBass.progress = settings.bassBoostStrength
        seekBass.isEnabled = settings.bassBoostEnabled
        switchVirt.isChecked = settings.virtualizerEnabled

        switchBass.setOnCheckedChangeListener { _, checked ->
            seekBass.isEnabled = checked
            audioEffects.setBassBoostEnabled(checked)
        }
        seekBass.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) audioEffects.setBassBoostStrength(p)
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        switchVirt.setOnCheckedChangeListener { _, checked ->
            audioEffects.setVirtualizerEnabled(checked)
        }

        AlertDialog.Builder(this)
            .setTitle(R.string.audio_effects_title)
            .setView(view)
            .setPositiveButton(android.R.string.ok, null)
            .show()
            .applyGoldTheme()
    }

    // ─────────────────────────────────────────────
    // Resume dialog
    // ─────────────────────────────────────────────

    private fun showResumeDialog(positionMs: Long) {
        val label = FormatUtils.formatDuration(positionMs)
        AlertDialog.Builder(this)
            .setTitle(R.string.resume_dialog_title)
            .setMessage(getString(R.string.resume_dialog_message, label))
            .setPositiveButton(R.string.action_resume) { _, _ ->
                player.seekTo(queueIndex, positionMs)
                player.prepare()
                player.play()
            }
            .setNegativeButton(R.string.action_start_over) { _, _ ->
                resumeStore.clearPosition(queue[queueIndex])
                player.prepare()
                player.play()
            }
            .setCancelable(false)
            .show()
            .applyGoldTheme()
    }

    // ─────────────────────────────────────────────
    // Error overlay
    // ─────────────────────────────────────────────

    private fun showErrorOverlay(error: PlaybackException) {
        binding.llErrorOverlay.apply {
            alpha = 0f
            isVisible = true
            animate().alpha(1f).setDuration(300).setListener(null).start()
        }
        val isFormat = error.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
            error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED
        binding.tvErrorTitle.setText(
            if (isFormat) R.string.error_unsupported_format_title else R.string.error_generic_playback_title
        )
        binding.tvErrorBody.text =
            if (isFormat) getString(R.string.error_unsupported_format_body)
            else (error.message ?: error.localizedMessage ?: "Unknown playback error")
    }

    // ─────────────────────────────────────────────
    // PiP
    // ─────────────────────────────────────────────

    private fun enterPip() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(16, 9))
                    .build()
            )
        }
    }

    // ─────────────────────────────────────────────
    // Gestures
    // ─────────────────────────────────────────────

    private fun setupGestureOverlay() {
        val helper = PlayerGestureHelper(this, window, settings, binding.gestureOverlay, this)
        binding.gestureOverlay.setOnTouchListener(helper)
    }

    override fun isLocked(): Boolean = controlsLocked

    override fun getCurrentPositionMs(): Long = player.currentPosition

    override fun getDurationMs(): Long = player.duration.takeIf { it > 0 } ?: 0L

    private var defaultPlaybackSpeed = 1f

    override fun onLongPressStart() {
        if (!player.isPlaying) return
        defaultPlaybackSpeed = player.playbackParameters.speed
        player.playbackParameters = androidx.media3.common.PlaybackParameters(2f)
        binding.tvGestureIndicator.apply {
            text = "2x Speed"
            alpha = 1f
            animate().cancel()
        }
    }

    override fun onLongPressEnd() {
        player.playbackParameters = androidx.media3.common.PlaybackParameters(defaultPlaybackSpeed)
        gestureIndicatorHide.run()
    }

    override fun onSingleTap() {
        if (controlsLocked) return
        if (binding.playerView.isControllerFullyVisible) {
            binding.playerView.hideController()
        } else {
            binding.playerView.showController()
        }
    }

    override fun onDoubleTapSeek(forward: Boolean) {
        val ms = (settings.doubleTapSeekSeconds * 1000).toLong()
        val target = (player.currentPosition + if (forward) ms else -ms)
            .coerceIn(0L, player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
        player.seekTo(target)
        showGestureIndicator(if (forward) "+${settings.doubleTapSeekSeconds}s ▶▶" else "◀◀ -${settings.doubleTapSeekSeconds}s")
    }

    override fun onDoubleTapCenter() {
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun onSeekPreview(targetPositionMs: Long) {
        showGestureIndicator("⏩ ${FormatUtils.formatDuration(targetPositionMs)}")
    }

    override fun onSeekCommit(targetPositionMs: Long) {
        player.seekTo(targetPositionMs)
        hideGestureIndicatorNow()
    }

    override fun onVolumeIndicator(percent: Int) {
        binding.pbVolume.progress = percent
        binding.pbVolume.alpha = 1f
        binding.pbVolume.isVisible = true
        gestureIndicatorHandler.removeCallbacks(gestureIndicatorHide)
        gestureIndicatorHandler.postDelayed(gestureIndicatorHide, 1200)
    }

    override fun onBrightnessIndicator(percent: Int) {
        binding.pbBrightness.progress = percent
        binding.pbBrightness.alpha = 1f
        binding.pbBrightness.isVisible = true
        gestureIndicatorHandler.removeCallbacks(gestureIndicatorHide)
        gestureIndicatorHandler.postDelayed(gestureIndicatorHide, 1200)
    }

    private fun showGestureIndicator(text: String) {
        binding.tvGestureIndicator.text = text
        binding.tvGestureIndicator.alpha = 1f
        binding.tvGestureIndicator.isVisible = true
        gestureIndicatorHandler.removeCallbacks(gestureIndicatorHide)
        gestureIndicatorHandler.postDelayed(gestureIndicatorHide, 1200)
    }

    private fun hideGestureIndicatorNow() {
        gestureIndicatorHandler.removeCallbacks(gestureIndicatorHide)
        binding.tvGestureIndicator.isVisible = false
        binding.pbVolume.isVisible = false
        binding.pbBrightness.isVisible = false
    }

    // ─────────────────────────────────────────────
    // Persistence helpers
    // ─────────────────────────────────────────────

    private fun persistResumePosition() {
        val uri = queue.getOrNull(queueIndex) ?: return
        resumeStore.savePosition(
            uri = uri,
            title = currentTitle,
            positionMs = player.currentPosition,
            durationMs = player.duration.takeIf { it > 0 } ?: 0L
        )
    }

    private fun applyKeepScreenOn() {
        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ─────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────

    private fun AlertDialog.applyGoldTheme(): AlertDialog {
        // Material3 dialogs inherit from Theme.Continental.Dialog automatically; this is a
        // no-op hook that keeps the call sites clean for any future manual adjustments.
        return this
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
