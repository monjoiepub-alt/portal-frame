package com.portalhacks.frame

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class DurationControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savingCustomDurationUpdatesSavedLabelWithoutReopening() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)
        prefs.edit().clear().putLong(ConfigReceiver.KEY_DELAY_MS, 10_000).commit()
        val controller = Robolectric.buildActivity(SettingsActivity::class.java).create()
        try {
            compose.setContent { MaterialTheme { Column { controller.get().DurationEditor {} } } }
            compose.onNode(hasSetTextAction() and hasText("Seconds")).performTextReplacement("7")
            compose.waitForIdle()
            compose.onNodeWithText("Save duration").performClick()
            compose.onNodeWithText("Saved: 0h 0m 7s").assertIsDisplayed()
            compose.runOnIdle { assertEquals(7000L, prefs.getLong(ConfigReceiver.KEY_DELAY_MS, 0)) }
            prefs.edit().putLong(ConfigReceiver.KEY_DELAY_MS, 9000).apply()
            compose.onNodeWithText("Saved: 0h 0m 9s").assertIsDisplayed()
        } finally {
            controller.destroy()
        }
    }
}
