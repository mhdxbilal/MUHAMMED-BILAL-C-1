package com.continental.player.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import com.continental.player.data.SettingsRepository

/**
 * A built-in equalizer-adjacent feature for people who *aren't* already running something
 * like RootlessJamesDSP system-wide. Attaches directly to ExoPlayer's audio session, so it
 * sits below any system-level DSP rather than fighting it.
 */
class AudioEffectsManager(private val settings: SettingsRepository) {

    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var equalizer: Equalizer? = null

    fun attach(audioSessionId: Int) {
        release()
        if (audioSessionId == 0) return
        try {
            bassBoost = BassBoost(0, audioSessionId).apply {
                enabled = settings.bassBoostEnabled
                setStrength(strengthFromPercent(settings.bassBoostStrength))
            }
        } catch (e: Exception) {
            bassBoost = null
        }
        try {
            virtualizer = Virtualizer(0, audioSessionId).apply {
                enabled = settings.virtualizerEnabled
            }
        } catch (e: Exception) {
            virtualizer = null
        }
        try {
            equalizer = Equalizer(0, audioSessionId).apply {
                enabled = true
            }
        } catch (e: Exception) {
            equalizer = null
        }
    }
    
    fun getEqualizer() = equalizer

    private fun strengthFromPercent(percent: Int): Short = (percent.coerceIn(0, 100) * 10).toShort()

    fun setBassBoostEnabled(enabled: Boolean) {
        settings.bassBoostEnabled = enabled
        try {
            bassBoost?.enabled = enabled
        } catch (e: Exception) {
            // effect engine may have been torn down already — nothing to recover from here
        }
    }

    fun setBassBoostStrength(percent: Int) {
        settings.bassBoostStrength = percent
        try {
            bassBoost?.setStrength(strengthFromPercent(percent))
        } catch (e: Exception) {
        }
    }

    fun setVirtualizerEnabled(enabled: Boolean) {
        settings.virtualizerEnabled = enabled
        try {
            virtualizer?.enabled = enabled
        } catch (e: Exception) {
        }
    }

    fun release() {
        try {
            bassBoost?.release()
        } catch (e: Exception) {
        }
        try {
            virtualizer?.release()
        } catch (e: Exception) {
        }
        try {
            equalizer?.release()
        } catch (e: Exception) {
        }
        bassBoost = null
        virtualizer = null
        equalizer = null
    }
}
