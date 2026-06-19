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

class PlayerActivity : BaseActivity(), PlayerGestureHelper.Listener {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var resumeStore: ResumeStore
    private lateinit var audioEffects: AudioEffectsManager

    private val player by lazy { PlayerEngine.createPlayer(this) }

    // Queue state
    private var queue: ArrayList<String> = arrayListOf()   // list of URI strings
    private var queueIndex: Int = 0
    private var currentTitle: String = ""

    // UI state
    private var controlsLocked = false
    private var isResizeCycleMode = false
    private val gestureIndicatorHandler = Handler(Looper.getMainLooper())
    private val gestureIndicatorHide = Runnable {
        binding.tvGestureIndicator.animate().alpha(0f).setDuration(250).withEndAction {
            binding.tvGestureIndicator.isVisible = false
            binding.tvGestureIndicator.alpha = 1f
        }.start()
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

        applyOrientationFromSettings()
        applyKeepScreenOn()
        setupPlayerView()
        wireControlButtons()
        setupGestureOverlay()

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

    // ─────────────────────────────────────────────
    // Player setup
    // ─────────────────────────────────────────────

    private fun setupPlayerView() {
        binding.playerView.player = player
        applyResizeModeFromSettings()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val sessionId = player.audioSessionId
                    if (sessionId != 0) audioEffects.attach(sessionId)
                    syncSpeedButton()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                applyKeepScreenOn()
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
            }

            override fun onPlayerError(error: PlaybackException) {
                showErrorOverlay(error)
            }
        })
    }

    private fun loadCurrentQueueItem() {
        if (queue.isEmpty()) { finish(); return }

        val mediaItems = queue.map { uriString ->
            MediaItem.Builder()
                .setUri(Uri.parse(uriString))
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

        // Speed text button in bottom bar
        pv.findViewById<View>(R.id.btnSpeed)?.setOnClickListener {
            showSpeedPicker()
        }

        // Error overlay buttons
        binding.btnErrorRetry.setOnClickListener {
            binding.llErrorOverlay.isVisible = false
            player.prepare()
            player.play()
        }
        binding.btnErrorSkip.setOnClickListener {
            binding.llErrorOverlay.isVisible = false
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
        AlertDialog.Builder(this)
            .setTitle(R.string.action_subtitles)
            .setSingleChoiceItems(labelItems.toTypedArray(), selected) { _, which -> selected = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pair = groupIndices[selected]
                if (pair == null) {
                    TrackSelectionHelper.disableTextTracks(player)
                } else {
                    TrackSelectionHelper.enableTextTracks(player)
                    TrackSelectionHelper.selectTrack(player, groups[pair.first], pair.second)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
            .applyGoldTheme()
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
        val next = when (binding.playerView.resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        binding.playerView.resizeMode = next
        val mode = when (next) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> ResizeMode.FILL
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> ResizeMode.ZOOM
            else -> ResizeMode.FIT
        }
        settings.resizeMode = mode
        val label = when (mode) {
            ResizeMode.FILL -> getString(R.string.resize_fill)
            ResizeMode.ZOOM -> getString(R.string.resize_zoom)
            else -> getString(R.string.resize_fit)
        }
        showGestureIndicator("⬛ $label")
    }

    private fun applyResizeModeFromSettings() {
        binding.playerView.resizeMode = when (settings.resizeMode) {
            ResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            ResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
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
        binding.llErrorOverlay.isVisible = true
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
        showGestureIndicator("🔊 $percent%")
    }

    override fun onBrightnessIndicator(percent: Int) {
        showGestureIndicator("☀️ $percent%")
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
