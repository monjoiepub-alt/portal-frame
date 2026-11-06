package com.portalhacks.frame

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.IOException
import java.text.DateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class DisplayOverlayTest {
    @Test fun digitalClockAlwaysUses24HourTimeRegardlessOfUsLocale() {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            renderer(clock = true, weather = false, face = "minimal") { controller ->
                val format = ReflectionHelpers.getField<DateFormat>(controller, "timeFmt")
                format.timeZone = TimeZone.getTimeZone("UTC")
                val date =
                    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                        set(2026, 0, 1, 18, 42, 0)
                    }.time
                assertEquals("18:42", format.format(date))
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun temperatureIsIndependentOfClockAndNeverHiddenByFaceSelection() {
        for (face in listOf("minimal", "classic", "flip", "nixie", "analog")) {
            renderer(clock = false, weather = true, face = face) { controller ->
                ReflectionHelpers.setField(controller, "weather", Weather.Now("☀️", 12, false))
                ReflectionHelpers.callInstanceMethod<Unit>(controller, "updateClock")
                val line = ReflectionHelpers.getField<TextView>(controller, "dateLine")
                assertEquals(View.VISIBLE, line.visibility)
                assertEquals("☀️ 12°C", line.text.toString())
                assertEquals(View.GONE, ReflectionHelpers.getField<TextView>(controller, "clock").visibility)
            }
        }
    }

    @Test fun supportedWeatherRequestAlwaysUsesCelsiusAndFailuresReturnNoInventedReading() {
        val urls = mutableListOf<String>()
        val result =
            Weather.fetch { url ->
                urls.add(url)
                if (urls.size == 1) {
                    """{"latitude":"51.0","longitude":"0.0"}"""
                } else {
                    """{"current":{"temperature_2m":12.4,"weather_code":0,"is_day":1}}"""
                }
            }
        assertEquals("☀️ 12°C", result?.label())
        assertTrue(urls.last().contains("temperature_unit=celsius"))
        assertNull(Weather.fetch { throw IOException("test offline") })
        assertNull(Weather.fetch { "{}" })
    }

    @Test fun unavailableWeatherDoesNotHideTheClock() {
        renderer(clock = true, weather = true, face = "minimal") { controller ->
            ReflectionHelpers.setField(controller, "weatherFailed", true)
            ReflectionHelpers.callInstanceMethod<Unit>(controller, "updateClock")
            assertEquals("Weather unavailable", ReflectionHelpers.getField<TextView>(controller, "dateLine").text)
            assertEquals(View.VISIBLE, ReflectionHelpers.getField<TextView>(controller, "clock").visibility)
        }
    }

    private fun renderer(
        clock: Boolean,
        weather: Boolean,
        face: String,
        check: (SlideshowController) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE).edit().clear()
            .putBoolean(ConfigReceiver.KEY_CLOCK, clock).putBoolean(ConfigReceiver.KEY_WEATHER, weather)
            .putBoolean(ConfigReceiver.KEY_CLOCK_DATE, false).putString(ConfigReceiver.KEY_CLOCK_FACE, face).commit()
        val loader = ImageLoader(context)
        val controller = SlideshowController(context, FrameLayout(context), loader)
        try {
            check(controller)
        } finally {
            controller.stop()
            loader.shutdown()
        }
    }
}
