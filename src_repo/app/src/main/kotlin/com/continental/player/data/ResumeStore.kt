package com.continental.player.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class ResumeEntry(
    val uri: String,
    val title: String,
    val positionMs: Long,
    val durationMs: Long,
    val lastPlayedAtMs: Long
)

/**
 * Tracks "where the user left off" per video, independent of [SettingsRepository]. Kept in
 * its own SharedPreferences file so a future "clear playback history" action never has to
 * touch the user's actual app settings.
 */
class ResumeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /** A position is only worth resuming if it's past the intro and not basically finished. */
    private fun isResumable(positionMs: Long, durationMs: Long): Boolean {
        if (durationMs <= 0) return positionMs > MIN_RESUME_MS
        val remaining = durationMs - positionMs
        return positionMs > MIN_RESUME_MS && remaining > END_THRESHOLD_MS
    }

    @Synchronized
    private fun readAll(): MutableMap<String, ResumeEntry> {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return mutableMapOf()
        return try {
            val type = object : TypeToken<MutableMap<String, ResumeEntry>>() {}.type
            gson.fromJson(json, type) ?: mutableMapOf()
        } catch (e: Exception) {
            mutableMapOf()
        }
    }

    @Synchronized
    private fun writeAll(map: Map<String, ResumeEntry>) {
        prefs.edit().putString(KEY_ENTRIES, gson.toJson(map)).apply()
    }

    fun getEntry(uri: String): ResumeEntry? = readAll()[uri]

    fun savePosition(uri: String, title: String, positionMs: Long, durationMs: Long) {
        val all = readAll()
        if (!isResumable(positionMs, durationMs)) {
            all.remove(uri)
        } else {
            all[uri] = ResumeEntry(uri, title, positionMs, durationMs, System.currentTimeMillis())
        }
        writeAll(all)
    }

    fun clearPosition(uri: String) {
        val all = readAll()
        all.remove(uri)
        writeAll(all)
    }

    fun getRecentlyWatched(limit: Int = 10): List<ResumeEntry> =
        readAll().values.sortedByDescending { it.lastPlayedAtMs }.take(limit)

    fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "continental_resume"
        private const val KEY_ENTRIES = "entries"
        private const val MIN_RESUME_MS = 5_000L
        private const val END_THRESHOLD_MS = 8_000L

        @Volatile private var instance: ResumeStore? = null

        fun getInstance(context: Context): ResumeStore =
            instance ?: synchronized(this) {
                instance ?: ResumeStore(context.applicationContext).also { instance = it }
            }
    }
}
