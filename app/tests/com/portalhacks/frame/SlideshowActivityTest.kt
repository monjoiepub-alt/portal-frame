package com.portalhacks.frame

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], qualifiers = "w1200dp-h800dp-land")
class SlideshowActivityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE)

    private fun photo(
        name: String,
        width: Int,
        height: Int,
        exif: Int = 1,
    ): File {
        val file = File(LocalUploads.dir(context), name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, exif.toString())
            saveAttributes()
        }
        return file
    }

    private fun await(block: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (block()) return
            Thread.sleep(10)
        }
        assertTrue("Activity did not finish rendering the expected photo", block())
    }

    @Test fun livePreferencesAndRotationReachActualRenderedPhotos() {
        prefs.edit().clear().putBoolean(ConfigReceiver.KEY_CLOCK_LOW_LIGHT, false)
            .putBoolean(ConfigReceiver.KEY_WEATHER, false)
            .putBoolean(ConfigReceiver.KEY_CLOCK, false).putLong(ConfigReceiver.KEY_DELAY_MS, 60_000).commit()
        LocalUploads.clearAll(context)
        val wide = photo("wide.jpg", 80, 40)
        val rotated = photo("exif-portrait.jpg", 80, 40, 6)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(
            context.packageName + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        val activityController = Robolectric.buildActivity(SlideshowComposeActivity::class.java).setup()
        val activity = activityController.get()
        val session = ReflectionHelpers.getField<PlaybackSession>(activity, "session")
        val root = ReflectionHelpers.getField<FrameLayout>(activity, "root")
        try {
            await { session.current?.slide?.id == wide.path && (root.getChildAt(0) as ImageView).drawable != null }
            prefs.edit().putString(ConfigReceiver.KEY_ORIENTATION, "PORTRAIT").apply()
            await { session.current?.slide?.id == rotated.path && (root.getChildAt(0) as ImageView).drawable != null }
            prefs.edit().putString(ConfigReceiver.KEY_ORIENTATION, "LANDSCAPE").apply()
            await { session.current?.slide?.id == wide.path }
            prefs.edit().putString(ConfigReceiver.KEY_ORIENTATION, "AUTO").apply()
            shadowOf(Looper.getMainLooper()).idle()
            RuntimeEnvironment.setQualifiers("w800dp-h1200dp-port")
            activity.onConfigurationChanged(
                Configuration(activity.resources.configuration).apply {
                    orientation = Configuration.ORIENTATION_PORTRAIT
                },
            )
            await { session.current?.slide?.id == rotated.path }
            root.measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 900, 600)
            await { session.current?.slide?.id == wide.path && ReflectionHelpers.getField<PlaybackEntry?>(activity, "pending") == null }
            val timer = ReflectionHelpers.getField<PlaybackTimer>(activity, "timer")

            fun tap(x: Float) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
                val time = SystemClock.uptimeMillis()
                val overlay = root.getChildAt(root.childCount - 1)
                for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                    val event = MotionEvent.obtain(time, time + 20, action, x, 200f, 0)
                    overlay.dispatchTouchEvent(event)
                    event.recycle()
                }
            }
            tap(450f)
            assertTrue(timer.userPaused)
            val remaining = timer.remaining(SystemClock.elapsedRealtime())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(30))
            assertEquals(remaining, timer.remaining(SystemClock.elapsedRealtime()))
            assertEquals(wide.path, session.current?.slide?.id)
            assertNotNull((root.getChildAt(0) as ImageView).drawable)
            tap(800f)
            await { ReflectionHelpers.getField<PlaybackEntry?>(activity, "pending") == null }
            assertTrue(timer.userPaused)
            assertEquals(60_000L, timer.remaining(SystemClock.elapsedRealtime()))
            tap(450f)
            assertFalse(timer.userPaused)
        } finally {
            activityController.pause().stop().destroy()
            LocalUploads.clearAll(context)
        }
    }

    @Test fun actualLocalHeaderResolutionHonorsExifAndDoesNotTrustLegacyPortraitFlag() {
        val file = photo("rotated.jpg", 80, 40, 6)
        val loader = ImageLoader(context)
        try {
            val resolved = loader.dimensions(Slide(file.path, null, portrait = false, width = 80, height = 40))
            assertEquals(40, resolved.width)
            assertEquals(80, resolved.height)
            assertTrue(PlaybackPolicy.matches(resolved, PlaybackPolicy.Orientation.AUTO, true))
            assertFalse(PlaybackPolicy.matches(resolved, PlaybackPolicy.Orientation.AUTO, false))
        } finally {
            loader.shutdown()
            file.delete()
        }
    }
}
