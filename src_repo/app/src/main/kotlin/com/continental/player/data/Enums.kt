package com.continental.player.data

/** How the library list is ordered. Persisted so the app reopens exactly as it was left. */
enum class SortOrder(val label: String) {
    DATE_NEWEST("Date added (newest)"),
    DATE_OLDEST("Date added (oldest)"),
    NAME_AZ("Name (A–Z)"),
    NAME_ZA("Name (Z–A)"),
    SIZE_LARGEST("File size (largest)"),
    SIZE_SMALLEST("File size (smallest)"),
    DURATION_LONGEST("Duration (longest)"),
    DURATION_SHORTEST("Duration (shortest)");

    companion object {
        fun fromName(name: String?): SortOrder =
            entries.firstOrNull { it.name == name } ?: DATE_NEWEST
    }
}

/** Controls whether the player is pinned to landscape, portrait, or follows the device sensor. */
enum class OrientationMode(val label: String) {
    LOCKED_LANDSCAPE("Landscape (locked)"),
    LOCKED_PORTRAIT("Portrait (locked)"),
    AUTO_SENSOR("Auto-rotate (sensor)"),
    FOLLOW_SYSTEM("Follow system setting");

    companion object {
        fun fromName(name: String?): OrientationMode =
            entries.firstOrNull { it.name == name } ?: AUTO_SENSOR
    }
}

/** Video resize / aspect ratio behaviour inside the player surface. */
enum class ResizeMode(val label: String) {
    FIT("Fit"),
    FILL("Stretch to fill"),
    ZOOM("Crop / Zoom");

    companion object {
        fun fromName(name: String?): ResizeMode =
            entries.firstOrNull { it.name == name } ?: FIT
    }
}
