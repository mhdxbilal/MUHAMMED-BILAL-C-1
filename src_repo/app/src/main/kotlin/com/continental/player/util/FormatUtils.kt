package com.continental.player.util

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

object FormatUtils {

    /** 65000L -> "1:05", 3_700_000L -> "1:01:40" */
    fun formatDuration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

    /** Same as [formatDuration] but always zero-padded hours when present, e.g. for folder totals. */
    fun formatDurationLong(ms: Long): String {
        val totalMinutes = TimeUnit.MILLISECONDS.toMinutes(ms)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            else -> "${minutes}m"
        }
    }

    /** 1536L -> "1.5 KB", handles up to TB sanely. */
    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unitIndex = 0
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return if (unitIndex == 0) "${size.toInt()} ${units[unitIndex]}"
        else String.format(Locale.US, "%.1f %s", size, units[unitIndex])
    }

    fun formatDateAdded(epochSeconds: Long): String {
        val sdf = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
        return sdf.format(epochSeconds * 1000)
    }

    /** Bytes-per-second -> "4.2 MB/s" for download speed readouts. */
    fun formatSpeed(bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return "—"
        return "${formatFileSize(bytesPerSecond)}/s"
    }

    /** Seconds remaining -> "2m 14s" / "58s" for download ETA readouts. */
    fun formatEta(seconds: Long): String {
        if (seconds < 0) return "—"
        val minutes = seconds / 60
        val secs = seconds % 60
        return if (minutes > 0) "${minutes}m ${secs}s" else "${secs}s"
    }
}
