package com.continental.player.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import java.util.Locale

object TrackSelectionHelper {

    fun audioGroups(tracks: Tracks): List<Tracks.Group> =
        tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }

    fun textGroups(tracks: Tracks): List<Tracks.Group> =
        tracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }

    /** A human label for a track row in a selection dialog: language name if known, else a
     *  generic index, with codec info appended so two same-language tracks stay distinguishable. */
    fun trackLabel(format: Format, fallbackIndex: Int): String {
        val languageName = format.language?.let { code ->
            try {
                Locale(code).displayLanguage.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
        }
        val base = format.label ?: languageName ?: "Track ${fallbackIndex + 1}"
        val codecHint = format.codecs?.let { " ($it)" } ?: ""
        return base + codecHint
    }

    fun isTrackSelected(group: Tracks.Group, index: Int): Boolean = group.isTrackSelected(index)

    fun selectTrack(player: Player, group: Tracks.Group, trackIndex: Int) {
        val override = TrackSelectionOverride(group.mediaTrackGroup, trackIndex)
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(override)
            .build()
    }

    /** Turns subtitles off entirely — distinct from selecting a track, since there's no
     *  "no track" index to select. */
    fun disableTextTracks(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }

    fun enableTextTracks(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
    }
}
