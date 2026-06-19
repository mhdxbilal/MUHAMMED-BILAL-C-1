package com.continental.player.library

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import com.continental.player.data.LibraryRow
import com.continental.player.data.SortOrder
import com.continental.player.data.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class VideoRepository(private val context: Context) {

    /** Scans MediaStore for every video the app is allowed to see. Scoped-storage compliant —
     *  no raw filesystem walking, just the standard content provider query. */
    suspend fun scanVideos(): List<VideoItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<VideoItem>()
        val useRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        val projection = mutableListOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE
        ).apply {
            if (useRelativePath) add(MediaStore.Video.Media.RELATIVE_PATH)
            else @Suppress("DEPRECATION") add(MediaStore.Video.Media.DATA)
        }.toTypedArray()

        val sortColumn = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            sortColumn
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val relPathCol = if (useRelativePath)
                cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH) else -1
            @Suppress("DEPRECATION")
            val dataCol = if (!useRelativePath)
                cursor.getColumnIndex(MediaStore.Video.Media.DATA) else -1

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                val name = cursor.getString(nameCol) ?: "Untitled"
                val bucket = cursor.getString(bucketCol) ?: "Unknown"

                val folderPath = if (useRelativePath && relPathCol >= 0) {
                    cursor.getString(relPathCol)?.trimEnd('/') ?: bucket
                } else if (dataCol >= 0) {
                    cursor.getString(dataCol)?.let { File(it).parent } ?: bucket
                } else {
                    bucket
                }

                result.add(
                    VideoItem(
                        id = id,
                        uri = uri,
                        displayName = name,
                        durationMs = cursor.getLong(durationCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        dateAddedSec = cursor.getLong(dateCol),
                        folderPath = folderPath,
                        folderName = bucket,
                        mimeType = cursor.getString(mimeCol)
                    )
                )
            }
        }
        result
    }

    companion object {

        fun videoComparator(order: SortOrder): Comparator<VideoItem> = when (order) {
            SortOrder.DATE_NEWEST -> compareByDescending { it.dateAddedSec }
            SortOrder.DATE_OLDEST -> compareBy { it.dateAddedSec }
            SortOrder.NAME_AZ -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            SortOrder.NAME_ZA -> compareByDescending(String.CASE_INSENSITIVE_ORDER) { it.displayName }
            SortOrder.SIZE_LARGEST -> compareByDescending { it.sizeBytes }
            SortOrder.SIZE_SMALLEST -> compareBy { it.sizeBytes }
            SortOrder.DURATION_LONGEST -> compareByDescending { it.durationMs }
            SortOrder.DURATION_SHORTEST -> compareBy { it.durationMs }
        }

        /**
         * Builds the flat header+video list the adapter renders: filters out hidden folders
         * and search misses, orders folders and the videos inside them by the same
         * [SortOrder], and drops video rows for any folder currently collapsed.
         */
        fun buildLibraryRows(
            allVideos: List<VideoItem>,
            sortOrder: SortOrder,
            hiddenFolders: Set<String>,
            collapsedFolders: Set<String>,
            searchQuery: String
        ): List<LibraryRow> {
            val query = searchQuery.trim().lowercase()
            val visible = allVideos.filter { video ->
                video.folderPath !in hiddenFolders &&
                    (query.isEmpty() || video.displayName.lowercase().contains(query))
            }

            val grouped = visible.groupBy { it.folderPath }
            val videoComparator = videoComparator(sortOrder)

            val folderEntries = grouped.entries.map { (path, videos) ->
                val sortedVideos = videos.sortedWith(videoComparator)
                val totalDuration = videos.sumOf { it.durationMs }
                val representativeDate = videos.maxOf { it.dateAddedSec }
                val representativeSize = videos.sumOf { it.sizeBytes }
                FolderGroup(
                    folderPath = path,
                    folderName = videos.first().folderName,
                    videos = sortedVideos,
                    maxDateAddedSec = representativeDate,
                    totalSizeBytes = representativeSize,
                    totalDurationMs = totalDuration
                )
            }

            val orderedFolders = when (sortOrder) {
                SortOrder.DATE_NEWEST -> folderEntries.sortedByDescending { it.maxDateAddedSec }
                SortOrder.DATE_OLDEST -> folderEntries.sortedBy { it.maxDateAddedSec }
                SortOrder.NAME_AZ -> folderEntries.sortedBy { it.folderName.lowercase() }
                SortOrder.NAME_ZA -> folderEntries.sortedByDescending { it.folderName.lowercase() }
                SortOrder.SIZE_LARGEST -> folderEntries.sortedByDescending { it.totalSizeBytes }
                SortOrder.SIZE_SMALLEST -> folderEntries.sortedBy { it.totalSizeBytes }
                SortOrder.DURATION_LONGEST -> folderEntries.sortedByDescending { it.totalDurationMs }
                SortOrder.DURATION_SHORTEST -> folderEntries.sortedBy { it.totalDurationMs }
            }

            val rows = mutableListOf<LibraryRow>()
            for (folder in orderedFolders) {
                val collapsed = folder.folderPath in collapsedFolders
                rows.add(
                    LibraryRow.FolderHeader(
                        folderPath = folder.folderPath,
                        folderName = folder.folderName,
                        videoCount = folder.videos.size,
                        totalDurationMs = folder.totalDurationMs,
                        isCollapsed = collapsed
                    )
                )
                if (!collapsed) {
                    folder.videos.forEach { rows.add(LibraryRow.Video(it)) }
                }
            }
            return rows
        }

        /** Flat list of every visible video in display order — this becomes the player's queue. */
        fun flattenPlayableQueue(rows: List<LibraryRow>): List<VideoItem> =
            rows.filterIsInstance<LibraryRow.Video>().map { it.video }
    }

    private data class FolderGroup(
        val folderPath: String,
        val folderName: String,
        val videos: List<VideoItem>,
        val maxDateAddedSec: Long,
        val totalSizeBytes: Long,
        val totalDurationMs: Long
    )
}
