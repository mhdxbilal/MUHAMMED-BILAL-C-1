package com.continental.player.player

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Centralizes every "make 4K60 not stutter" decision in one place.
 *
 * Hardware decoding: [DefaultRenderersFactory] already resolves H.264/HEVC/AV1 through
 * MediaCodecSelector.DEFAULT, which lists the device's hardware (non-software) decoders
 * first for a given mime type — that's the actual mechanism that keeps decoding on the
 * Snapdragon's video DSP instead of the CPU. [setEnableDecoderFallback] is the safety net:
 * if a hardware decoder fails to initialize for a specific file, it retries with the next
 * matching decoder (software included) instead of just showing a black screen.
 *
 * Buffers: bumped above ExoPlayer's stock defaults (15s/30s/2.5s/5s) on the max side, since
 * a remuxed 4K60 file can be 80-100+ Mbps and benefits from a deeper buffer on slower storage.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object PlayerEngine {

    fun createPlayer(context: Context): ExoPlayer {
        val hwFirstSelector = MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val defaultInfos = MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
            defaultInfos.sortedByDescending { it.hardwareAccelerated }.toMutableList()
        }

        val renderersFactory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setMediaCodecSelector(hwFirstSelector)

        val trackSelector = DefaultTrackSelector(context)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000
            )
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setSeekForwardIncrementMs(5_000)
            .setSeekBackIncrementMs(5_000)
            .build()
    }
}
