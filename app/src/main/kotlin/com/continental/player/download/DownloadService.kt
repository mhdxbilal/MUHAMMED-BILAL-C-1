package com.continental.player.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.continental.player.R
import com.continental.player.data.SettingsRepository
import com.continental.player.util.MediaStoreFileUtils
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The actual download engine. Pulls QUEUED [DownloadTask]s from [DownloadRepository], runs
 * yt-dlp on a bounded pool of background coroutines (respecting the user's "max concurrent
 * downloads" setting), and republishes the finished file through scoped-storage-safe channels.
 *
 * NOTE on the three-line block below marked "best-effort": the underlying youtubedl-android
 * API surface for a couple of flag-only CLI options and the aria2c hand-off isn't fully
 * documented, so those specific calls are the most likely spot to need a one-line tweak once
 * you build against the real library in Android Studio's autocomplete. Everything else here
 * (execute/progress/cancel/getInfo) is verified directly against the project's own README.
 */
class DownloadService : Service() {

    private lateinit var repository: DownloadRepository
    private lateinit var settings: SettingsRepository
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private var dispatcherJob: Job? = null
    private val cancelledIds = CopyOnWriteArraySet<String>()

    override fun onCreate() {
        super.onCreate()
        repository = DownloadRepository.getInstance(this)
        settings = SettingsRepository.getInstance(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_TASK_ID)?.let { cancelTask(it) }
            else -> startDispatcherIfNeeded()
        }
        return START_NOT_STICKY
    }

    private fun startDispatcherIfNeeded() {
        startForeground(NOTIFICATION_ID, buildNotification("Starting downloads…", 0, indeterminate = true))
        if (dispatcherJob?.isActive == true) return
        dispatcherJob = serviceScope.launch {
            val permits = settings.downloadMaxConcurrent.coerceIn(1, 3)
            val semaphore = Semaphore(permits)
            val workers = (1..permits).map {
                launch {
                    while (true) {
                        val task = repository.nextQueuedTask() ?: break
                        semaphore.withPermit { runDownload(task) }
                    }
                }
            }
            workers.forEach { it.join() }
            stopForegroundGracefully()
        }
    }

    private fun cancelTask(id: String) {
        cancelledIds.add(id)
        try {
            YoutubeDL.getInstance().destroyProcessById(id)
        } catch (e: Exception) {
            // Process may not have started yet — the QUEUED short-circuit below still catches it.
        }
        val task = repository.getTask(id)
        if (task != null && task.status == DownloadStatus.QUEUED) {
            repository.updateTask(id) { it.copy(status = DownloadStatus.CANCELLED) }
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private suspend fun runDownload(task: DownloadTask) {
        if (cancelledIds.contains(task.id)) {
            repository.updateTask(task.id) { it.copy(status = DownloadStatus.CANCELLED) }
            return
        }
        if (settings.downloadWifiOnly && !isWifiConnected()) {
            repository.updateTask(task.id) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = "Wi-Fi only is on in Settings, and no Wi-Fi is connected")
            }
            return
        }

        repository.updateTask(task.id) { it.copy(status = DownloadStatus.RUNNING) }
        updateAggregateNotification()

        val stagingRoot = File(File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), "staging"), task.id)
        stagingRoot.mkdirs()

        try {
            val request = YoutubeDLRequest(task.sourceUrl)
            request.addOption("-o", "${stagingRoot.absolutePath}/%(title).100s.%(ext)s")
            request.addOption("--no-mtime")
            request.addOption("--no-playlist")
            request.addOption("-f", task.quality.formatSelector)

            if (task.quality.isAudioOnly) {
                // best-effort: flag-only option, no value
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
            } else {
                request.addOption("--merge-output-format", "mp4")
                if (settings.downloadEmbedSubtitles) {
                    request.addOption("--embed-subs") // best-effort: flag-only option
                    request.addOption("--sub-langs", "all")
                }
            }
            if (settings.downloadUseAria2) {
                request.addOption("--external-downloader", "libaria2c.so") // best-effort hand-off string
            }

            // Verified against the project README: execute(request, callback, processId).
            YoutubeDL.getInstance().execute(request, task.id) { progress: Float, etaInSeconds: Long, _: String ->
                repository.updateTask(task.id) { it.copy(progressPercent = progress, etaSeconds = etaInSeconds) }
                updateAggregateNotification()
            }

            if (cancelledIds.contains(task.id)) {
                repository.updateTask(task.id) { it.copy(status = DownloadStatus.CANCELLED) }
                stagingRoot.deleteRecursively()
                return
            }

            publishResult(task, stagingRoot)
        } catch (e: YoutubeDLException) {
            handleFailure(task, stagingRoot, e.message ?: "Download failed")
        } catch (e: Exception) {
            handleFailure(task, stagingRoot, e.message ?: "Unexpected error")
        } finally {
            updateAggregateNotification()
        }
    }

    private fun publishResult(task: DownloadTask, stagingRoot: File) {
        val finishedFile = stagingRoot.listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.maxByOrNull { it.length() }

        if (finishedFile == null) {
            repository.updateTask(task.id) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = "No output file was produced")
            }
            return
        }

        val mimeType = if (task.quality.isAudioOnly) "audio/mpeg" else "video/mp4"
        val publishedUri = when (settings.downloadLocationMode) {
            "CUSTOM" -> settings.downloadCustomTreeUri?.let { treeUri ->
                MediaStoreFileUtils.publishToCustomTree(this, finishedFile, finishedFile.name, mimeType, treeUri)
            }
            "DOWNLOADS" -> MediaStoreFileUtils.publishToMediaStore(
                this, finishedFile, finishedFile.name, mimeType,
                MediaStoreFileUtils.Collection.DOWNLOADS, settings.downloadSubfolderName
            )
            else -> MediaStoreFileUtils.publishToMediaStore(
                this, finishedFile, finishedFile.name, mimeType,
                if (task.quality.isAudioOnly) MediaStoreFileUtils.Collection.DOWNLOADS else MediaStoreFileUtils.Collection.MOVIES,
                settings.downloadSubfolderName
            )
        }

        stagingRoot.deleteRecursively()

        if (publishedUri == null) {
            repository.updateTask(task.id) {
                it.copy(status = DownloadStatus.FAILED, errorMessage = "Could not save the finished file")
            }
        } else {
            repository.updateTask(task.id) {
                it.copy(
                    status = DownloadStatus.COMPLETED,
                    title = it.title.ifBlank { finishedFile.nameWithoutExtension },
                    progressPercent = 100f,
                    finalUri = publishedUri.toString(),
                    completedAtMs = System.currentTimeMillis()
                )
            }
        }
    }

    private fun handleFailure(task: DownloadTask, stagingRoot: File, message: String) {
        stagingRoot.deleteRecursively()
        val status = if (cancelledIds.contains(task.id)) DownloadStatus.CANCELLED else DownloadStatus.FAILED
        repository.updateTask(task.id) { it.copy(status = status, errorMessage = message) }
    }

    private fun updateAggregateNotification() {
        val active = repository.tasks.value.filter { it.isActive }
        if (active.isEmpty()) {
            stopForegroundGracefully()
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val current = active.firstOrNull { it.status == DownloadStatus.RUNNING } ?: active.first()
        val text = if (active.size > 1) "${current.displayTitle}  (+${active.size - 1} more)" else current.displayTitle
        manager.notify(NOTIFICATION_ID, buildNotification(text, current.progressPercent.toInt(), indeterminate = false))
    }

    private fun stopForegroundGracefully() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String, progress: Int, indeterminate: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, DownloadActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.notif_downloading_title))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress, indeterminate)
            .setContentIntent(openIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Progress for media downloads" }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_CANCEL = "com.continental.player.action.CANCEL_DOWNLOAD"
        const val EXTRA_TASK_ID = "task_id"
        private const val CHANNEL_ID = "continental_downloads"
        private const val NOTIFICATION_ID = 4242

        fun start(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context, taskId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_TASK_ID, taskId)
            }
            context.startService(intent)
        }
    }
}
