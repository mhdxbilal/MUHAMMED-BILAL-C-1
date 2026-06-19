package com.continental.player.download

/**
 * Quality presets map straight onto yt-dlp's own format-selector mini-language, so the same
 * string works across virtually every site yt-dlp supports rather than us trying to enumerate
 * exact format IDs per source (which differ wildly between extractors).
 */
enum class DownloadQuality(val label: String, val formatSelector: String, val isAudioOnly: Boolean = false) {
    BEST("Best available", "bestvideo+bestaudio/best"),
    Q2160("4K (2160p)", "bestvideo[height<=2160]+bestaudio/best[height<=2160]"),
    Q1080("Full HD (1080p)", "bestvideo[height<=1080]+bestaudio/best[height<=1080]"),
    Q720("HD (720p)", "bestvideo[height<=720]+bestaudio/best[height<=720]"),
    Q480("SD (480p)", "bestvideo[height<=480]+bestaudio/best[height<=480]"),
    AUDIO_ONLY("Audio only (MP3)", "bestaudio/best", isAudioOnly = true);

    companion object {
        fun fromName(name: String?): DownloadQuality =
            entries.firstOrNull { it.name == name } ?: Q1080
    }
}

enum class DownloadStatus {
    QUEUED, FETCHING_INFO, RUNNING, COMPLETED, FAILED, CANCELLED
}

data class DownloadTask(
    val id: String,
    val sourceUrl: String,
    var title: String = "",
    var quality: DownloadQuality,
    var status: DownloadStatus = DownloadStatus.QUEUED,
    var progressPercent: Float = 0f,
    var etaSeconds: Long = -1,
    var errorMessage: String? = null,
    var finalUri: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    var completedAtMs: Long? = null
) {
    val isActive: Boolean
        get() = status == DownloadStatus.QUEUED || status == DownloadStatus.FETCHING_INFO ||
            status == DownloadStatus.RUNNING

    val displayTitle: String
        get() = title.ifBlank { sourceUrl }
}
