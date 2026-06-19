package com.continental.player.player

import android.content.Context
import android.media.AudioManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.Window
import com.continental.player.data.SettingsRepository
import kotlin.math.abs

/**
 * One touch listener attached directly to the gesture-overlay view in activity_player.xml.
 * Decides the gesture's axis (horizontal = seek, vertical = volume/brightness) on the first
 * meaningful movement of each touch sequence so a slightly diagonal swipe doesn't flicker
 * between modes. Each concern (which side of the screen, what's enabled in Settings, how to
 * actually move the seek bar) is intentionally kept inside this one class rather than spread
 * across the Activity, so the gesture rules can be read top-to-bottom in one place.
 */
class PlayerGestureHelper(
    context: Context,
    private val window: Window,
    private val settings: SettingsRepository,
    private val targetView: View,
    private val listener: Listener
) : View.OnTouchListener {

    interface Listener {
        fun isLocked(): Boolean
        fun getCurrentPositionMs(): Long
        fun getDurationMs(): Long
        fun onSingleTap()
        fun onDoubleTapSeek(forward: Boolean)
        fun onDoubleTapCenter()
        fun onSeekPreview(targetPositionMs: Long)
        fun onSeekCommit(targetPositionMs: Long)
        fun onVolumeIndicator(percent: Int)
        fun onBrightnessIndicator(percent: Int)
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    private val screenWidth = context.resources.displayMetrics.widthPixels
    private val screenHeight = context.resources.displayMetrics.heightPixels

    private var isDraggingSeek = false
    private var isDraggingVertical = false
    private var dragStartPositionMs = 0L
    private var accumulatedSeekMs = 0L
    private var scaleFactor = 1f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (listener.isLocked()) return false
                scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(0.5f, 3f)
                targetView.scaleX = scaleFactor
                targetView.scaleY = scaleFactor
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {

            override fun onDown(e: MotionEvent): Boolean {
                isDraggingSeek = false
                isDraggingVertical = false
                accumulatedSeekMs = 0L
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                listener.onSingleTap()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (listener.isLocked()) return false
                when {
                    e.x < screenWidth * 0.35f -> listener.onDoubleTapSeek(false)
                    e.x > screenWidth * 0.65f -> listener.onDoubleTapSeek(true)
                    else -> listener.onDoubleTapCenter()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (listener.isLocked()) return false
                val startX = e1?.x ?: e2.x

                if (!isDraggingSeek && !isDraggingVertical) {
                    if (abs(distanceX) > abs(distanceY)) {
                        if (!settings.gestureSeekEnabled) return false
                        isDraggingSeek = true
                        dragStartPositionMs = listener.getCurrentPositionMs()
                        accumulatedSeekMs = 0L
                    } else {
                        isDraggingVertical = true
                    }
                }

                if (isDraggingSeek) {
                    // distanceX is the delta since the PREVIOUS onScroll call (not since onDown),
                    // and is negative when the finger moves right — negate it so dragging right
                    // means seeking forward, matching every other player's convention.
                    accumulatedSeekMs -= (distanceX / screenWidth * MAX_SEEK_SWIPE_MS).toLong()
                    val duration = listener.getDurationMs()
                    val target = (dragStartPositionMs + accumulatedSeekMs).let {
                        if (duration > 0) it.coerceIn(0L, duration) else it.coerceAtLeast(0L)
                    }
                    listener.onSeekPreview(target)
                    return true
                }

                // distanceY is positive when the finger moves up — also matches the "swipe
                // up to increase" convention used here for both volume and brightness.
                val deltaFraction = distanceY / screenHeight
                if (startX < screenWidth / 2f) {
                    if (!settings.gestureVolumeEnabled) return false
                    val newVolume = (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) +
                        deltaFraction * maxVolume * 3f).coerceIn(0f, maxVolume.toFloat())
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume.toInt(), 0)
                    listener.onVolumeIndicator(((newVolume / maxVolume) * 100).toInt())
                } else {
                    if (!settings.gestureBrightnessEnabled) return false
                    val newBrightness = (currentBrightness() + deltaFraction * 1.5f).coerceIn(0.02f, 1f)
                    setBrightness(newBrightness)
                    listener.onBrightnessIndicator((newBrightness * 100).toInt())
                }
                return true
            }
        }
    )

    private fun currentBrightness(): Float {
        val current = window.attributes.screenBrightness
        return if (current in 0f..1f) current else 0.5f
    }

    private fun setBrightness(value: Float) {
        val params = window.attributes
        params.screenBrightness = value
        window.attributes = params
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            if (isDraggingSeek) {
                val duration = listener.getDurationMs()
                val target = (dragStartPositionMs + accumulatedSeekMs).let {
                    if (duration > 0) it.coerceIn(0L, duration) else it.coerceAtLeast(0L)
                }
                listener.onSeekCommit(target)
            }
            isDraggingSeek = false
            isDraggingVertical = false
        }
        return true
    }

    companion object {
        /** A full screen-width swipe covers 90 seconds of timeline — fast enough to cross a
         *  feature-length file in a couple of swipes, fine enough for second-level precision. */
        private const val MAX_SEEK_SWIPE_MS = 90_000f
    }
}
