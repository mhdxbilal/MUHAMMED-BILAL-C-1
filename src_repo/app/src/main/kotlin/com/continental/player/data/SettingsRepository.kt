package com.continental.player.data

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager

/**
 * Single source of truth for every preference in the app.
 *
 * Backed by the default [SharedPreferences] file so the same keys are readable from the
 * androidx.preference screen (SettingsActivity) and from plain Kotlin code (player, library,
 * downloader) without ever going out of sync.
 *
 * Every setter commits immediately, so a preference changed seconds before a crash or a
 * swipe-to-kill is still there on the next launch — nothing here is fire-and-forget.
 */
class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)

    // ---------------------------------------------------------------------
    // Library: sorting, folder visibility, expand/collapse, grid layout
    // ---------------------------------------------------------------------

    var sortOrder: SortOrder
        get() = SortOrder.fromName(prefs.getString(KEY_SORT_ORDER, null))
        set(value) = prefs.edit().putString(KEY_SORT_ORDER, value.name).apply()

    /** Folder paths the user chose to hide from the library. Files are untouched on disk. */
    var hiddenFolders: MutableSet<String>
        get() = HashSet(prefs.getStringSet(KEY_HIDDEN_FOLDERS, emptySet()) ?: emptySet())
        set(value) = prefs.edit().putStringSet(KEY_HIDDEN_FOLDERS, value).apply()

    fun hideFolder(folderPath: String) {
        hiddenFolders = hiddenFolders.apply { add(folderPath) }
    }

    fun unhideFolder(folderPath: String) {
        hiddenFolders = hiddenFolders.apply { remove(folderPath) }
    }

    /** Folder paths currently collapsed in the library list. Empty set = everything expanded. */
    var collapsedFolders: MutableSet<String>
        get() = HashSet(prefs.getStringSet(KEY_COLLAPSED_FOLDERS, emptySet()) ?: emptySet())
        set(value) = prefs.edit().putStringSet(KEY_COLLAPSED_FOLDERS, value).apply()

    fun setFolderCollapsed(folderPath: String, collapsed: Boolean) {
        collapsedFolders = collapsedFolders.apply {
            if (collapsed) add(folderPath) else remove(folderPath)
        }
    }

    fun isFolderCollapsed(folderPath: String): Boolean = collapsedFolders.contains(folderPath)

    var gridColumnsPortrait: Int
        get() = prefs.getInt(KEY_GRID_COLUMNS, 2)
        set(value) = prefs.edit().putInt(KEY_GRID_COLUMNS, value).apply()

    var lastSearchQuery: String
        get() = prefs.getString(KEY_LAST_SEARCH, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LAST_SEARCH, value).apply()

    // ---------------------------------------------------------------------
    // Playback behaviour
    // ---------------------------------------------------------------------

    var orientationMode: OrientationMode
        get() = OrientationMode.fromName(prefs.getString(KEY_ORIENTATION_MODE, null))
        set(value) = prefs.edit().putString(KEY_ORIENTATION_MODE, value.name).apply()

    var resizeMode: ResizeMode
        get() = ResizeMode.fromName(prefs.getString(KEY_RESIZE_MODE, null))
        set(value) = prefs.edit().putString(KEY_RESIZE_MODE, value.name).apply()

    var gestureBrightnessEnabled: Boolean
        get() = prefs.getBoolean(KEY_GESTURE_BRIGHTNESS, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURE_BRIGHTNESS, value).apply()

    var gestureVolumeEnabled: Boolean
        get() = prefs.getBoolean(KEY_GESTURE_VOLUME, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURE_VOLUME, value).apply()

    var gestureSeekEnabled: Boolean
        get() = prefs.getBoolean(KEY_GESTURE_SEEK, true)
        set(value) = prefs.edit().putBoolean(KEY_GESTURE_SEEK, value).apply()

    /** Stored as a String because androidx.preference's ListPreference (used for the preset
     *  5/10/15/30s choices in Settings) always writes Strings — getInt() here would crash
     *  with a ClassCastException the moment the user touched that preference. */
    var doubleTapSeekSeconds: Int
        get() = prefs.getString(KEY_DOUBLE_TAP_SEEK, "10")?.toIntOrNull() ?: 10
        set(value) = prefs.edit().putString(KEY_DOUBLE_TAP_SEEK, value.toString()).apply()

    /** When true, whatever speed the user lands on becomes the default for the next video. */
    var rememberPlaybackSpeed: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER_SPEED, true)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER_SPEED, value).apply()

    var lastPlaybackSpeed: Float
        get() = prefs.getFloat(KEY_LAST_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_LAST_SPEED, value).apply()

    var preferredAudioLanguage: String
        get() = prefs.getString(KEY_AUDIO_LANG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_AUDIO_LANG, value).apply()

    var preferredSubtitleLanguage: String
        get() = prefs.getString(KEY_SUBTITLE_LANG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SUBTITLE_LANG, value).apply()

    var subtitlesEnabledByDefault: Boolean
        get() = prefs.getBoolean(KEY_SUBTITLES_DEFAULT_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_SUBTITLES_DEFAULT_ON, value).apply()

    var keepScreenOn: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_ON, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, value).apply()

    /** App-wide immersive mode. A safety valve exists in Settings in case someone wants the bars back. */
    var fullscreenImmersive: Boolean
        get() = prefs.getBoolean(KEY_FULLSCREEN_IMMERSIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_FULLSCREEN_IMMERSIVE, value).apply()

    /** Skip the "Resume from mm:ss?" dialog entirely and just continue automatically. */
    var autoResumeWithoutPrompt: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESUME, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESUME, value).apply()

    var bassBoostEnabled: Boolean
        get() = prefs.getBoolean(KEY_BASS_BOOST, false)
        set(value) = prefs.edit().putBoolean(KEY_BASS_BOOST, value).apply()

    var bassBoostStrength: Int
        get() = prefs.getInt(KEY_BASS_BOOST_STRENGTH, 50)
        set(value) = prefs.edit().putInt(KEY_BASS_BOOST_STRENGTH, value).apply()

    var virtualizerEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIRTUALIZER, false)
        set(value) = prefs.edit().putBoolean(KEY_VIRTUALIZER, value).apply()

    // ---------------------------------------------------------------------
    // Downloader
    // ---------------------------------------------------------------------

    var downloadLocationMode: String
        get() = prefs.getString(KEY_DL_LOCATION_MODE, "MOVIES") ?: "MOVIES"
        set(value) = prefs.edit().putString(KEY_DL_LOCATION_MODE, value).apply()

    var downloadCustomTreeUri: String?
        get() = prefs.getString(KEY_DL_CUSTOM_URI, null)
        set(value) = prefs.edit().putString(KEY_DL_CUSTOM_URI, value).apply()

    var downloadSubfolderName: String
        get() = prefs.getString(KEY_DL_SUBFOLDER, "The Continental") ?: "The Continental"
        set(value) = prefs.edit().putString(KEY_DL_SUBFOLDER, value).apply()

    var downloadDefaultQuality: String
        get() = prefs.getString(KEY_DL_QUALITY, "Q1080") ?: "Q1080"
        set(value) = prefs.edit().putString(KEY_DL_QUALITY, value).apply()

    var downloadWifiOnly: Boolean
        get() = prefs.getBoolean(KEY_DL_WIFI_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_DL_WIFI_ONLY, value).apply()

    var downloadMaxConcurrent: Int
        get() = prefs.getInt(KEY_DL_MAX_CONCURRENT, 2)
        set(value) = prefs.edit().putInt(KEY_DL_MAX_CONCURRENT, value).apply()

    var downloadUseAria2: Boolean
        get() = prefs.getBoolean(KEY_DL_USE_ARIA2, true)
        set(value) = prefs.edit().putBoolean(KEY_DL_USE_ARIA2, value).apply()

    var downloadEmbedSubtitles: Boolean
        get() = prefs.getBoolean(KEY_DL_EMBED_SUBS, false)
        set(value) = prefs.edit().putBoolean(KEY_DL_EMBED_SUBS, value).apply()

    companion object {
        private const val KEY_SORT_ORDER = "pref_sort_order"
        private const val KEY_HIDDEN_FOLDERS = "pref_hidden_folders"
        private const val KEY_COLLAPSED_FOLDERS = "pref_collapsed_folders"
        private const val KEY_GRID_COLUMNS = "pref_grid_columns"
        private const val KEY_LAST_SEARCH = "pref_last_search"

        private const val KEY_ORIENTATION_MODE = "pref_orientation_mode"
        private const val KEY_RESIZE_MODE = "pref_resize_mode"
        private const val KEY_GESTURE_BRIGHTNESS = "pref_gesture_brightness"
        private const val KEY_GESTURE_VOLUME = "pref_gesture_volume"
        private const val KEY_GESTURE_SEEK = "pref_gesture_seek"
        private const val KEY_DOUBLE_TAP_SEEK = "pref_double_tap_seek"
        private const val KEY_REMEMBER_SPEED = "pref_remember_speed"
        private const val KEY_LAST_SPEED = "pref_last_speed"
        private const val KEY_AUDIO_LANG = "pref_audio_lang"
        private const val KEY_SUBTITLE_LANG = "pref_subtitle_lang"
        private const val KEY_SUBTITLES_DEFAULT_ON = "pref_subtitles_default_on"
        private const val KEY_KEEP_SCREEN_ON = "pref_keep_screen_on"
        private const val KEY_FULLSCREEN_IMMERSIVE = "pref_fullscreen_immersive"
        private const val KEY_AUTO_RESUME = "pref_auto_resume"
        private const val KEY_BASS_BOOST = "pref_bass_boost"
        private const val KEY_BASS_BOOST_STRENGTH = "pref_bass_boost_strength"
        private const val KEY_VIRTUALIZER = "pref_virtualizer"

        private const val KEY_DL_LOCATION_MODE = "pref_dl_location_mode"
        private const val KEY_DL_CUSTOM_URI = "pref_dl_custom_uri"
        private const val KEY_DL_SUBFOLDER = "pref_dl_subfolder"
        private const val KEY_DL_QUALITY = "pref_dl_quality"
        private const val KEY_DL_WIFI_ONLY = "pref_dl_wifi_only"
        private const val KEY_DL_MAX_CONCURRENT = "pref_dl_max_concurrent"
        private const val KEY_DL_USE_ARIA2 = "pref_dl_use_aria2"
        private const val KEY_DL_EMBED_SUBS = "pref_dl_embed_subs"

        @Volatile private var instance: SettingsRepository? = null

        fun getInstance(context: Context): SettingsRepository =
            instance ?: synchronized(this) {
                instance ?: SettingsRepository(context.applicationContext).also { instance = it }
            }
    }
}
