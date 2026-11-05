package com.portalhacks.frame

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "w1200dp-h800dp-land")
class PlaybackControlsTest {
    @get:Rule val compose = createComposeRule()
    private val prefs get() =
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)

    @Before fun setup() {
        prefs.edit().clear().commit()
        compose.setContent { MaterialTheme { PlaybackControls(prefs, false) } }
    }

    @Test fun actualRadioClicksRecomposeAndPersistAcrossRepeatedSelections() {
        compose.onNodeWithTag("orientation-auto").assertIsSelected()
        compose.onNodeWithTag("orientation-all").performClick().assertIsSelected()
        assertEquals("ALL", PlaybackSettings.read(prefs).orientation.name)
        compose.onNodeWithTag("orientation-manual").performClick().assertIsSelected()
        compose.onNodeWithTag("orientation-PORTRAIT").performClick().assertIsSelected()
        assertEquals("PORTRAIT", PlaybackSettings.read(prefs).orientation.name)
        compose.onNodeWithTag("orientation-auto").performClick().assertIsSelected()
        assertEquals("AUTO", PlaybackSettings.read(prefs).orientation.name)
        for (mode in PlaybackPolicy.Mode.entries) {
            compose.onNodeWithTag("mode-${mode.name}").performClick().assertIsSelected()
            assertEquals(mode, PlaybackSettings.read(prefs).mode)
        }
    }

    @Test fun uiSelectionsChangeTheActualSessionRatherThanOnlyLabels() {
        val sources =
            linkedMapOf(
                "a" to
                    listOf(
                        Slide("wide", null, width = 800, height = 400),
                        Slide("tall", null, width = 400, height = 800),
                    ),
                "b" to listOf(Slide("b", null, width = 400, height = 800)),
            )
        prefs.edit().putString(ConfigReceiver.KEY_SELECTED_ALBUM, "a").commit()
        compose.onNodeWithTag("orientation-manual").performClick()
        compose.onNodeWithTag("orientation-PORTRAIT").performClick()
        compose.onNodeWithTag("mode-REPEAT_CURRENT").performClick()
        val session = PlaybackSession()
        session.configure(sources, PlaybackSettings.read(prefs), false)
        assertEquals("tall", session.initial()!!.slide.id)
        session.shown(session.initial()!!)
        assertEquals("a", session.next()!!.album)
        compose.onNodeWithTag("mode-NEXT_ALBUM").performClick()
        session.configure(sources, PlaybackSettings.read(prefs), false)
        session.shown(session.initial()!!)
        assertEquals("b", session.next()!!.album)
    }

    @Test
    fun selectorObservesEnabledAlbumsWithoutRestartingTheScreen() {
        val a = "https://photos.google.com/share/a"
        val b = "https://photos.google.com/share/b"
        Albums.add(prefs, a)
        Albums.add(prefs, b)
        compose.onNodeWithTag("album-selector").performClick()
        compose.onNodeWithTag("album-$b").assertIsDisplayed()
        Albums.setEnabled(prefs, b, false)
        compose.onNodeWithTag("album-$b").assertDoesNotExist()
        compose.onNodeWithTag("album-$a").assertIsDisplayed().performClick()
        assertEquals(a, PlaybackSettings.read(prefs).album)
    }
}
