package com.portalhacks.frame

import android.content.SharedPreferences

internal data class PlaybackSettings(
    val orientation: PlaybackPolicy.Orientation = PlaybackPolicy.Orientation.AUTO,
    val mode: PlaybackPolicy.Mode = PlaybackPolicy.Mode.SHUFFLE_ALL,
    val album: String? = null,
    val durationMs: Long = ConfigReceiver.DEFAULT_DELAY_MS,
    val pairs: Boolean = false,
    val shuffle: Boolean = false,
) {
    companion object {
        fun read(prefs: SharedPreferences): PlaybackSettings =
            PlaybackSettings(
                orientation =
                    PlaybackPolicy.Orientation.entries.firstOrNull {
                        it.name == prefs.getString(ConfigReceiver.KEY_ORIENTATION, "AUTO")
                    } ?: PlaybackPolicy.Orientation.AUTO,
                mode =
                    PlaybackPolicy.Mode.entries.firstOrNull {
                        it.name == prefs.getString(ConfigReceiver.KEY_PLAYBACK_MODE, "SHUFFLE_ALL")
                    } ?: PlaybackPolicy.Mode.SHUFFLE_ALL,
                album = prefs.getString(ConfigReceiver.KEY_SELECTED_ALBUM, null),
                durationMs =
                    prefs.getLong(ConfigReceiver.KEY_DELAY_MS, ConfigReceiver.DEFAULT_DELAY_MS)
                        .coerceIn(1000L, PlaybackPolicy.MAX_DURATION_MS),
                pairs = prefs.getBoolean(ConfigReceiver.KEY_PAIRS, false),
                shuffle = prefs.getBoolean(ConfigReceiver.KEY_SHUFFLE, false),
            )
    }
}
