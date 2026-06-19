package com.continental.player.data

import android.net.Uri

/** A single playable video, whether it came from MediaStore, SAF, or a finished download. */
data class VideoItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val folderPath: String,
    val folderName: String,
    val mimeType: String? = null
)

/**
 * What the expandable library RecyclerView actually renders: a flat list mixing folder
 * headers and the video rows beneath them. Collapsing a folder simply removes its [Video]
 * rows from this list — no nested adapters, no diffing headaches.
 */
sealed class LibraryRow {
    data class FolderHeader(
        val folderPath: String,
        val folderName: String,
        val videoCount: Int,
        val totalDurationMs: Long,
        val isCollapsed: Boolean
    ) : LibraryRow()

    data class Video(val video: VideoItem) : LibraryRow()
}
