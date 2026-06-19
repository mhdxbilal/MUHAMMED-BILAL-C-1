package com.continental.player.download

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * The single source of truth for every download, queued or finished. [DownloadService] writes
 * to it as work progresses; [DownloadActivity] just observes [tasks] — no Binder/Messenger
 * plumbing needed since both run in the default app process.
 */
class DownloadRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val gson = Gson()
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    init {
        _tasks.value = readHistory()
    }

    @Synchronized
    fun enqueue(url: String, quality: DownloadQuality): DownloadTask {
        val task = DownloadTask(id = UUID.randomUUID().toString(), sourceUrl = url, quality = quality)
        _tasks.update { listOf(task) + it }
        persist()
        return task
    }

    @Synchronized
    fun updateTask(id: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.update { list -> list.map { if (it.id == id) transform(it) else it } }
        val updated = _tasks.value.firstOrNull { it.id == id }
        if (updated != null && !updated.isActive) persist()
    }

    fun getTask(id: String): DownloadTask? = _tasks.value.firstOrNull { it.id == id }

    @Synchronized
    fun nextQueuedTask(): DownloadTask? = _tasks.value.firstOrNull { it.status == DownloadStatus.QUEUED }

    fun activeOrQueuedCount(): Int = _tasks.value.count { it.isActive }

    @Synchronized
    fun removeTask(id: String) {
        _tasks.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun clearCompletedHistory() {
        _tasks.update { list -> list.filter { it.isActive } }
        persist()
    }

    private fun persist() {
        // Only completed/failed/cancelled history needs to survive a process death — active
        // queue state is meaningless after a restart since the underlying process is gone too.
        val finished = _tasks.value.filterNot { it.isActive }
        prefs.edit().putString(KEY_HISTORY, gson.toJson(finished)).apply()
    }

    private fun readHistory(): List<DownloadTask> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<DownloadTask>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "continental_downloads"
        private const val KEY_HISTORY = "history"

        @Volatile private var instance: DownloadRepository? = null

        fun getInstance(context: Context): DownloadRepository =
            instance ?: synchronized(this) {
                instance ?: DownloadRepository(context.applicationContext).also { instance = it }
            }
    }
}
