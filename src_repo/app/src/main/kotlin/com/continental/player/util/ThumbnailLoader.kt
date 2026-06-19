package com.continental.player.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads an actual frame from the video file as its thumbnail — never just a generic
 * placeholder icon, unless extraction genuinely fails (corrupt file, permission revoked, etc).
 *
 * Two strategies, in order:
 *  1. [android.content.ContentResolver.loadThumbnail] (API 29+) — fast, uses the system's own
 *     pre-rendered/cached thumbnail when the URI lives in MediaStore.
 *  2. [MediaMetadataRetriever] — works for any content/file Uri (SAF picks, fresh downloads not
 *     yet re-scanned into MediaStore, API 26-28 devices), at the cost of decoding one frame.
 */
object ThumbnailLoader {

    private val memoryCache: LruCache<String, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        val cacheSizeKb = maxKb / 8
        object : LruCache<String, Bitmap>(cacheSizeKb) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
        }
    }

    private fun cacheKey(uri: Uri, w: Int, h: Int) = "${uri}_${w}x${h}"

    suspend fun load(context: Context, uri: Uri, widthPx: Int, heightPx: Int): Bitmap? {
        val key = cacheKey(uri, widthPx, heightPx)
        memoryCache.get(key)?.let { return it }

        val bitmap = withContext(Dispatchers.IO) {
            loadViaContentResolver(context, uri, widthPx, heightPx)
                ?: loadViaMetadataRetriever(context, uri, widthPx, heightPx)
        }

        if (bitmap != null) memoryCache.put(key, bitmap)
        return bitmap
    }

    private fun loadViaContentResolver(context: Context, uri: Uri, w: Int, h: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            context.contentResolver.loadThumbnail(uri, Size(w, h), null)
        } catch (e: Exception) {
            null
        }
    }

    private fun loadViaMetadataRetriever(context: Context, uri: Uri, w: Int, h: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    1_000_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    w,
                    h
                )
            } else {
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            frame ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                // already released or never opened — safe to ignore
            }
        }
    }

    fun clearMemoryCache() = memoryCache.evictAll()
}
