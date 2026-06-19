package com.continental.player

import android.app.Application
import android.util.Log
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ContinentalApp : Application() {

    /** True once init() has returned successfully — the download screen checks this before
     *  letting the user queue anything, so a half-initialized engine never silently no-ops. */
    @Volatile var downloadEngineReady: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(this@ContinentalApp)
                FFmpeg.getInstance().init(this@ContinentalApp)
                Aria2c.getInstance().init(this@ContinentalApp)
                downloadEngineReady = true
            } catch (e: YoutubeDLException) {
                Log.e(TAG, "Failed to initialize the embedded yt-dlp engine", e)
            } catch (e: Exception) {
                // Some OEM/ABI combos throw plain UnsatisfiedLinkError-style exceptions rather
                // than YoutubeDLException — the downloader degrades gracefully either way,
                // the rest of the app (player + library) is completely unaffected.
                Log.e(TAG, "Unexpected error initializing the downloader", e)
            }
        }
    }

    companion object {
        private const val TAG = "ContinentalApp"
    }
}
