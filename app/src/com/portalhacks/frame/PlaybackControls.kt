package com.portalhacks.frame

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role

/** Observe the preferences inside this composable: parent recomposition alone can skip this child. */
@Composable
internal fun PlaybackControls(
    prefs: SharedPreferences,
    localAvailable: Boolean,
) {
    val revision = rememberPreferenceRevision(prefs)
    val settings = remember(prefs, revision) { PlaybackSettings.read(prefs) }
    Column {
        Text("Photo orientation", color = PortalColors.Text)
        PlaybackPolicy.Orientation.entries.forEach { value ->
            val tag =
                when (value) {
                    PlaybackPolicy.Orientation.AUTO, PlaybackPolicy.Orientation.ALL -> value.name.lowercase()
                    else -> value.name
                }
            Choice(value.name, "orientation-$tag", settings.orientation == value) {
                prefs.edit().putString(ConfigReceiver.KEY_ORIENTATION, value.name).apply()
            }
        }
        Text(
            "Auto follows the screen. Auto, Landscape and Portrait exclude square/unknown images " +
                "and show whole photos.",
            color = PortalColors.TextMuted,
        )
        Text("Album playback", color = PortalColors.Text)
        PlaybackPolicy.Mode.entries.forEach { value ->
            Choice(modeLabel(value), "mode-${value.name}", settings.mode == value) {
                prefs.edit().putString(ConfigReceiver.KEY_PLAYBACK_MODE, value.name).apply()
            }
        }
        AlbumChoice(prefs, settings.album, localAvailable, revision)
    }
}

private fun modeLabel(mode: PlaybackPolicy.Mode): String =
    when (mode) {
        PlaybackPolicy.Mode.REPEAT_CURRENT -> "Repeat Current Album"
        PlaybackPolicy.Mode.NEXT_ALBUM -> "Next Album"
        PlaybackPolicy.Mode.SHUFFLE_ALL -> "Shuffle All"
    }

@Composable
private fun Choice(
    label: String,
    tag: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag(tag).selectable(selected, role = Role.RadioButton, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, color = PortalColors.Text)
    }
}

@Composable
private fun AlbumChoice(
    prefs: SharedPreferences,
    selected: String?,
    localAvailable: Boolean,
    revision: Int,
) {
    val albums = remember(prefs, revision) { Albums.enabled(prefs) }.toMutableList()
    if (localAvailable) albums.add(PlaybackPolicy.LOCAL_ALBUM)

    fun label(url: String?): String =
        when (url) {
            null -> "No enabled albums"
            PlaybackPolicy.LOCAL_ALBUM -> "Photos from phones"
            else -> AlbumCache.title(prefs, url)?.takeIf { it.isNotBlank() } ?: url
        }
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(
            onClick = { expanded = true },
            enabled = albums.isNotEmpty(),
            modifier = Modifier.testTag("album-selector"),
        ) {
            Text("Selected: " + label(selected?.takeIf { it in albums } ?: albums.firstOrNull()))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            albums.forEach { url ->
                DropdownMenuItem(text = { Text(label(url)) }, modifier = Modifier.testTag("album-$url"), onClick = {
                    prefs.edit().putString(ConfigReceiver.KEY_SELECTED_ALBUM, url).apply()
                    expanded = false
                })
            }
        }
        Text(
            "Repeat and Next use the selection. Shuffle All mixes every enabled album and local uploads.",
            color = PortalColors.TextMuted,
        )
    }
}
