package com.portalhacks.frame

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class TouchNavigationTest {
    @Test fun realTouchOverlayRoutesSingleTapsAndLongPressWithoutDoubleNavigation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(ConfigReceiver.PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(ConfigReceiver.KEY_CLOCK, false).putBoolean(ConfigReceiver.KEY_NOTES, false).commit()
        val root = FrameLayout(context)
        val loader = ImageLoader(context)
        val controller = SlideshowController(context, root, loader)
        val actions = mutableListOf<TouchZones.Action>()
        var menus = 0
        controller.manageNavigation { actions.add(it) }
        controller.setOnSettings { menus++ }
        try {
            root.measure(
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 900, 600)
            val overlay = root.getChildAt(root.childCount - 1)

            fun send(
                start: Long,
                action: Int,
                x: Float,
                at: Long = start,
            ) {
                val event = MotionEvent.obtain(start, at, action, x, 200f, 0)
                overlay.dispatchTouchEvent(event)
                event.recycle()
            }
            for ((time, x) in listOf(1000L to 100f, 2000L to 450f, 3000L to 800f, 3100L to 800f)) {
                send(time, MotionEvent.ACTION_DOWN, x)
                send(time, MotionEvent.ACTION_UP, x, time + 40)
            }
            assertEquals(listOf(TouchZones.Action.PREVIOUS, TouchZones.Action.PAUSE, TouchZones.Action.NEXT), actions)
            send(4000, MotionEvent.ACTION_DOWN, 450f)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(1000))
            send(4000, MotionEvent.ACTION_UP, 450f, 5000)
            assertEquals(1, menus)
            assertEquals(3, actions.size)
        } finally {
            controller.stop()
            loader.shutdown()
        }
    }
}
