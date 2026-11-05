package com.portalhacks.frame

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The live slideshow screensaver, hosted in Jetpack Compose.
 *
 * The slideshow's rendering is a deeply imperative, custom-animated View stack —
 * crossfading [android.widget.ImageView]s, a [android.animation.ValueAnimator]
 * Ken Burns engine, a `Canvas` shimmer, an `ImageSpan` weather glyph, distance-based
 * touch gestures — exactly the case Compose's `AndroidView` interop exists for. So we
 * keep the battle-tested [SlideshowController] and bridge it into `setContent`, rather
 * than re-deriving the animation engine in Compose (which would risk regressing the
 * marquee features for no user-facing gain). The album fetch/cache/refresh and
 * night-dimming logic live here in Kotlin.
 *
 * This is the screensaver target ([FrameDreamService] launches it).
 */
@Suppress("TooManyFunctions")
class SlideshowComposeActivity : ComponentActivity() {
    private lateinit var loader: ImageLoader
    private lateinit var controller: SlideshowController
    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var currentAlbum: String? = null
    private var signature = ""
    private val progress = AlbumProgress()
    private var eligible: Map<String, List<Slide>> = emptyMap()
    private val dimensions = mutableMapOf<String, Slide>()
    private val probing = mutableSetOf<String>()
    private val fetching = mutableSetOf<String>()
    private var uploadReceiverRegistered = false
    private val prefs get() = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)
    private val mode get() =
        PlaybackPolicy.Mode.entries.firstOrNull {
            it.name == prefs.getString(ConfigReceiver.KEY_PLAYBACK_MODE, "SHUFFLE_ALL")
        } ?: PlaybackPolicy.Mode.SHUFFLE_ALL
    private val orientation get() =
        PlaybackPolicy.Orientation.entries.firstOrNull {
            it.name == prefs.getString(ConfigReceiver.KEY_ORIENTATION, "AUTO")
        } ?: PlaybackPolicy.Orientation.AUTO
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val lightSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) }
    private val lightListener =
        object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val lux = e.values.firstOrNull() ?: return
                when {
                    lux <= LOW_LUX -> controller.setClockOnly(true)
                    lux >= HIGH_LUX -> controller.setClockOnly(false)
                }
            }

            override fun onAccuracyChanged(
                s: Sensor?,
                accuracy: Int,
            ) {}
        }
    private val uploadReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                c: Context?,
                i: Intent?,
            ) {
                rebuild()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        loader = ImageLoader(this)
        loader.onDimensions = { slide ->
            val old = dimensions.put(slide.id, slide)
            if (active && (old?.width != slide.width || old.height != slide.height)) rebuild()
        }
        root = FrameLayout(this)
        setContent {
            Box(Modifier.fillMaxSize()) {
                AndroidView(factory = { root }, modifier = Modifier.fillMaxSize())
                Button(
                    onClick = { openSettings() },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) { Text("Albums & playback") }
            }
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            WindowCompat.getInsetsController(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        active = true
        // Rebuild the view controller to apply ALL saved preferences, not only clock transforms.
        if (::controller.isInitialized) controller.stop()
        root.removeAllViews()
        controller =
            SlideshowController(this, root, loader).apply {
                setOnDismiss { finishAndRemoveTask() }
                setOnSettings { openSettings() }
                setOnCycle { advanceAlbum() }
                setOnItemFinished { progress.complete(it) }
            }
        currentAlbum = prefs.getString(ConfigReceiver.KEY_SELECTED_ALBUM, null)
        signature = ""
        progress.reset()
        rebuild()
        DropServerService.start(this)
        ContextCompat.registerReceiver(
            this,
            uploadReceiver,
            IntentFilter(DropServerService.ACTION_UPLOAD),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        uploadReceiverRegistered = true
        if (prefs.getBoolean(ConfigReceiver.KEY_CLOCK_LOW_LIGHT, ConfigReceiver.DEFAULT_CLOCK_LOW_LIGHT)) {
            lightSensor?.let { sensorManager.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        fetchAlbums()
        handler.postDelayed(refreshTick, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        active = false
        handler.removeCallbacks(refreshTick)
        sensorManager.unregisterListener(lightListener)
        if (uploadReceiverRegistered) {
            unregisterReceiver(uploadReceiver)
            uploadReceiverRegistered = false
        }
        controller.stop()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        loader.onDimensions = null
        loader.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (active) {
            controller.stop()
            controller.blank()
            controller.onScreenConfigChanged()
            rebuild(force = true)
        }
    }

    private fun catalog(): Map<String, List<Slide>> {
        val result = linkedMapOf<String, List<Slide>>()
        for (url in Albums.enabled(prefs)) result[url] = AlbumCache.read(prefs, url).orEmpty()
        val local = LocalUploads.slides(this)
        if (local.isNotEmpty()) result[PlaybackPolicy.LOCAL_ALBUM] = local
        if (Albums.list(prefs).isEmpty() && local.isEmpty()) {
            result[PlaybackPolicy.SAMPLE_ALBUM] =
                assets.list("slides").orEmpty()
                    .filter { it.endsWith(".jpg") || it.endsWith(".png") }.sorted()
                    .map { Slide("slides/$it", null) }
        }
        return result
    }

    private fun rebuild(
        force: Boolean = false,
        restart: Boolean = false,
    ) {
        if (!active) return
        val portrait = resources.displayMetrics.heightPixels > resources.displayMetrics.widthPixels
        val catalog =
            catalog().mapValues { (_, slides) ->
                slides.map { slide -> withDimensions(slide) }
            }
        eligible =
            catalog.mapValues { (_, slides) ->
                slides.filter { PlaybackPolicy.matches(it, orientation, portrait) }
            }
        currentAlbum = PlaybackPolicy.selectedAlbum(eligible, currentAlbum, mode)
        val items = PlaybackPolicy.items(eligible, currentAlbum, mode)
        val nextSignature = "$currentAlbum|$mode|" + items.joinToString { "${it.id}:${it.width}:${it.height}" }
        if (force || nextSignature != signature) {
            signature = nextSignature
            applyItems(items, restart)
        }
        // Legacy caches and uploads lack bounds. Probe headers off the UI thread; never decode full bitmaps.
        catalog.values.flatten().distinctBy { it.id }.filter { it.width <= 0 || it.height <= 0 }
            .forEach { probe(it) }
    }

    private fun withDimensions(slide: Slide): Slide {
        val bounds = dimensions[slide.id] ?: return slide
        return Slide(slide.id, slide.caption, slide.timeMs, bounds.portrait, bounds.width, bounds.height)
    }

    private fun applyItems(
        items: List<Slide>,
        restart: Boolean,
    ) {
        if (items.isEmpty()) {
            controller.showEmpty(emptyMessage())
            return
        }
        val current = controller.currentId().takeUnless { restart }
        if (current != null && items.any { it.id == current }) {
            controller.setItemsShowing(items, current, instant = true)
        } else {
            controller.setItems(items)
        }
    }

    private fun emptyMessage(): String {
        if (orientation == PlaybackPolicy.Orientation.ALL) return "No photos in the selected albums"
        val portrait = resources.displayMetrics.heightPixels > resources.displayMetrics.widthPixels
        val vertical =
            orientation == PlaybackPolicy.Orientation.PORTRAIT ||
                (orientation == PlaybackPolicy.Orientation.AUTO && portrait)
        return "No matching ${if (vertical) "portrait" else "landscape"} photos. " +
            "Square and unknown-size images are excluded."
    }

    private fun probe(slide: Slide) {
        if (!probing.add(slide.id)) return
        loader.executor().execute {
            val resolved = loader.dimensions(slide)
            runOnUiThread {
                // Keep attempted ids until next refresh, so corrupt files do not cause a retry loop.
                dimensions[slide.id] = resolved
                if (active) rebuild()
            }
        }
    }

    private fun advanceAlbum(): Boolean {
        if (mode != PlaybackPolicy.Mode.NEXT_ALBUM) return false
        val remaining = progress.remaining(eligible[currentAlbum].orEmpty())
        if (remaining.isNotEmpty()) {
            controller.setItems(remaining)
            return true
        }
        progress.reset()
        currentAlbum = PlaybackPolicy.nextAlbum(eligible, currentAlbum)
        prefs.edit().putString(ConfigReceiver.KEY_SELECTED_ALBUM, currentAlbum).apply()
        rebuild(force = true, restart = true)
        return true
    }

    private val refreshTick =
        object : Runnable {
            override fun run() {
                if (!active) return
                fetchAlbums()
                handler.postDelayed(this, REFRESH_INTERVAL_MS)
            }
        }

    private fun fetchAlbums() {
        // Retry completed failed probes on refresh, never duplicate in-flight header reads.
        val failed = dimensions.filterValues { it.width <= 0 || it.height <= 0 }.keys.toSet()
        probing.removeAll(failed)
        dimensions.keys.removeAll(failed)
        for (url in Albums.enabled(prefs)) {
            if (!fetching.add(url)) continue
            loader.executor().execute {
                try {
                    val album = PhotoSources.fetch(url)
                    // An empty scraped response is ambiguous: retain offline cache on parser/server failure.
                    if (album.slides.isNotEmpty()) AlbumCache.write(prefs, url, album.slides, album.title)
                } catch (e: Exception) {
                    Log.w("PortalFrame", "album refresh failed", e)
                } finally {
                    runOnUiThread {
                        fetching.remove(url)
                        if (active) rebuild()
                    }
                }
            }
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 20 * 60 * 1000L
        private const val LOW_LUX = 8f
        private const val HIGH_LUX = 25f
    }
}
