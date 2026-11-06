package com.portalhacks.frame

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Hosts rendering, lifecycle and sync; PlaybackSession owns selection and navigation history. */
@Suppress("TooManyFunctions")
class SlideshowComposeActivity : ComponentActivity() {
    private lateinit var loader: ImageLoader
    private lateinit var controller: SlideshowController
    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private val session = PlaybackSession()
    private val timer = PlaybackTimer(ConfigReceiver.DEFAULT_DELAY_MS)
    private var active = false
    private var screenPortrait = false
    private var renderToken = 0L
    private var visible: PlaybackEntry? = null
    private var pending: PlaybackEntry? = null
    private var sourcesDirty = true
    private var sources: Map<String, List<Slide>> = emptyMap()
    private val dimensions = mutableMapOf<String, Slide>()
    private val probing = mutableSetOf<String>()
    private val fetching = mutableSetOf<String>()
    private val prefs get() = getSharedPreferences(ConfigReceiver.PREFS, MODE_PRIVATE)
    private val settings get() = PlaybackSettings.read(prefs)
    private val sensorManager by lazy { getSystemService(SENSOR_SERVICE) as SensorManager }
    private val lightSensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) }
    private val lightListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val lux = event.values.firstOrNull() ?: return
                if (lux <= LOW_LUX || lux >= HIGH_LUX) {
                    val dark = lux <= LOW_LUX
                    timer.hold("light", dark, now())
                    controller.setClockOnly(dark)
                    scheduleAuto()
                }
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) {}
        }
    private val uploadReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                sourcesDirty = true
                refreshPlayback()
            }
        }
    private val applySettings =
        Runnable {
            if (active) {
                val updated = settings
                if (timer.durationMs != updated.durationMs) {
                    timer.durationMs = updated.durationMs
                    timer.reset(now())
                }
                createRenderer()
                refreshPlayback(force = true)
            }
        }
    private val preferenceListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key?.startsWith("album_cache_") == true || key in sourceKeys) sourcesDirty = true
            if (key in playbackKeys && active) {
                val internalAdvance = key == ConfigReceiver.KEY_SELECTED_ALBUM && settings.album == session.selection
                if (!internalAdvance) {
                    handler.removeCallbacks(applySettings)
                    handler.post(applySettings)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        screenPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        loader = ImageLoader(this)
        loader.onDimensions = { slide ->
            probing.add(slide.id)
            val old = dimensions.put(slide.id, slide)
            if (active && (old?.width != slide.width || old.height != slide.height)) refreshPlayback()
        }
        root = FrameLayout(this)
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val changed = right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop
            if (active && right > left && bottom > top) {
                if (changed) {
                    screenPortrait = bottom - top > right - left
                    refreshPlayback(force = true)
                }
            }
        }
        setContent { AndroidView(factory = { root }, modifier = Modifier.fillMaxSize()) }
    }

    private fun createRenderer() {
        renderToken++
        pending = null
        if (::controller.isInitialized) controller.stop()
        root.removeAllViews()
        controller =
            SlideshowController(this, root, loader).apply {
                setOnSettings { openMenu() }
                manageNavigation { action ->
                    if (action == TouchZones.Action.PAUSE) togglePause() else navigate(action)
                }
                freezeMotion(timer.userPaused)
            }
    }

    private fun openMenu() {
        timer.hold("menu", true, now())
        scheduleAuto()
        val options = arrayOf("Settings & albums", "Move/resize clock", "Move note", "Close slideshow")
        val dialog =
            AlertDialog.Builder(this).setTitle("Frame playback")
                .setItems(options) { _, index ->
                    when (index) {
                        0 -> startActivity(Intent(this, SettingsActivity::class.java))
                        1 -> controller.editClock()
                        2 -> controller.editNote()
                        else -> finishAndRemoveTask()
                    }
                }.create()
        dialog.setOnDismissListener {
            timer.hold("menu", false, now())
            scheduleAuto()
        }
        dialog.show()
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
        sourcesDirty = true
        timer.hold("lifecycle", false, now())
        if (timer.durationMs != settings.durationMs) {
            timer.durationMs = settings.durationMs
            timer.reset(now())
        }
        session.retryFailures()
        createRenderer()
        prefs.registerOnSharedPreferenceChangeListener(preferenceListener)
        refreshPlayback(force = true)
        DropServerService.start(this)
        ContextCompat.registerReceiver(
            this,
            uploadReceiver,
            IntentFilter(DropServerService.ACTION_UPLOAD),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (prefs.getBoolean(ConfigReceiver.KEY_CLOCK_LOW_LIGHT, ConfigReceiver.DEFAULT_CLOCK_LOW_LIGHT)) {
            lightSensor?.let { sensorManager.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        } else {
            timer.hold("light", false, now())
        }
        fetchAlbums()
        handler.postDelayed(refreshTick, REFRESH_INTERVAL_MS)
    }

    override fun onPause() {
        timer.hold("lifecycle", true, now())
        active = false
        renderToken++
        pending = null
        handler.removeCallbacks(refreshTick)
        handler.removeCallbacks(autoTick)
        handler.removeCallbacks(applySettings)
        sensorManager.unregisterListener(lightListener)
        unregisterReceiver(uploadReceiver)
        prefs.unregisterOnSharedPreferenceChangeListener(preferenceListener)
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
        // AUTO follows the displayed viewport, including ADB rotation and rotation-locked screens.
        screenPortrait = newConfig.orientation == Configuration.ORIENTATION_PORTRAIT
        if (active) refreshPlayback(force = true)
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

    private fun configureSession() {
        if (sourcesDirty) {
            sources = catalog().mapValues { (_, slides) -> controller.prepareAlbum(slides) }
            sourcesDirty = false
        }
        val resolved = sources.mapValues { (_, slides) -> slides.map { withDimensions(it) } }
        session.configure(resolved, settings, screenPortrait)
        resolved.values.flatten().distinctBy { it.id }
            .filter { it.width <= 0 || it.height <= 0 || loader.hasCachedSource(it.id) }.forEach { probe(it) }
    }

    private fun refreshPlayback(force: Boolean = false) {
        if (!active || !::controller.isInitialized) return
        configureSession()
        if (visible?.let { !session.allowed(it) } == true) {
            controller.blank()
            visible = null
        }
        if (!force && pending?.let { session.allowed(it) } == true) return
        val target = session.current ?: session.initial()
        if (target == null) {
            showEmptySelection()
        } else {
            timer.hold("empty", false, now())
            if (force || pending != null || !sameFrame(visible, target)) {
                present(target, reset = visible?.slide?.id != target.slide.id, instant = true)
            }
        }
    }

    private fun withDimensions(slide: Slide): Slide {
        val bounds = dimensions[slide.id] ?: return slide
        return Slide(slide.id, slide.caption, slide.timeMs, bounds.portrait, bounds.width, bounds.height)
    }

    private fun present(
        entry: PlaybackEntry,
        reset: Boolean,
        instant: Boolean,
    ) {
        val token = ++renderToken
        pending = entry
        timer.hold("loading", true, now())
        handler.removeCallbacks(autoTick)
        controller.cancelPresentation()
        val filter =
            when (settings.orientation) {
                PlaybackPolicy.Orientation.AUTO -> if (screenPortrait) "PORTRAIT" else "LANDSCAPE"
                else -> settings.orientation.name
            }
        val width = root.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val height = root.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        controller.viewport(width, height, filter)
        controller.present(listOfNotNull(entry.slide, entry.partner), instant) { success ->
            onFramePresented(token, entry, reset, success)
        }
    }

    private fun onFramePresented(
        token: Long,
        entry: PlaybackEntry,
        reset: Boolean,
        success: Boolean,
    ) {
        if (!active || token != renderToken || pending == null) return
        pending = null
        if (success && session.allowed(entry)) {
            session.shown(entry)
            visible = entry
            // Stable opaque identifiers allow ADB sequence checks without logging private album URLs.
            Log.i(
                "FramePlayback",
                "shown photo=${entry.slide.id.hashCode().toUInt().toString(16)} " +
                    "album=${entry.album.hashCode().toUInt().toString(16)} mode=${settings.mode} " +
                    "filter=${settings.orientation} screenPortrait=$screenPortrait " +
                    "photo=${entry.slide.width}x${entry.slide.height} viewport=${root.width}x${root.height}",
            )
            if (reset) timer.reset(now())
            if (settings.mode == PlaybackPolicy.Mode.NEXT_ALBUM && settings.album != session.selection) {
                prefs.edit().putString(ConfigReceiver.KEY_SELECTED_ALBUM, session.selection).apply()
            }
            timer.hold("loading", false, now())
            controller.freezeMotion(timer.userPaused)
            scheduleAuto()
        } else {
            session.reject(entry)
            timer.hold("loading", false, now())
            handler.post { if (active && token == renderToken) navigate(TouchZones.Action.NEXT) }
        }
    }

    private fun sameFrame(
        a: PlaybackEntry?,
        b: PlaybackEntry,
    ): Boolean = a?.album == b.album && a.slide.id == b.slide.id && a.partner?.id == b.partner?.id

    private fun showEmptySelection() {
        renderToken++
        pending = null
        controller.showEmpty(
            "No matching photos in the selected albums. " +
                "Square and unknown-size photos are excluded in strict modes.",
        )
        timer.hold("empty", true, now())
        scheduleAuto()
    }

    private fun navigate(action: TouchZones.Action) {
        if (!active || pending != null) return
        configureSession() // Recheck current orientation, enabled albums and metadata before EVERY navigation.
        val target = if (action == TouchZones.Action.PREVIOUS) session.previous() ?: session.current else session.next()
        if (target != null) {
            present(target, reset = true, instant = timer.userPaused)
        } else {
            refreshPlayback()
        }
    }

    private fun togglePause() {
        val pause = !timer.userPaused
        timer.hold("user", pause, now())
        Log.i("FramePlayback", "userPaused=$pause remainingMs=${timer.remaining(now())}")
        if (pause && visible != null && pending != null) {
            renderToken++
            pending = null
            controller.cancelPresentation()
            timer.hold("loading", false, now())
            visible?.let { present(it, reset = false, instant = true) }
        }
        controller.freezeMotion(pause)
        Toast.makeText(this, if (pause) "Slideshow paused" else "Slideshow resumed", Toast.LENGTH_SHORT).show()
        scheduleAuto()
    }

    private fun scheduleAuto() {
        handler.removeCallbacks(autoTick)
        if (!active || timer.paused) return
        if (pending != null || session.current == null) return
        handler.postDelayed(autoTick, timer.remaining(now()).coerceAtLeast(1))
    }

    private val autoTick =
        Runnable {
            if (active && !timer.paused) navigate(TouchZones.Action.NEXT)
        }

    private fun now(): Long = SystemClock.elapsedRealtime()

    private fun probe(slide: Slide) {
        if (!probing.add(slide.id)) return
        loader.executor().execute {
            val resolved = loader.dimensions(slide)
            runOnUiThread {
                dimensions[slide.id] = resolved
                if (active) refreshPlayback()
            }
        }
    }

    private val refreshTick =
        object : Runnable {
            override fun run() {
                if (!active) return
                session.retryFailures()
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
                        sourcesDirty = true
                        if (active) refreshPlayback()
                    }
                }
            }
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 20 * 60 * 1000L
        private const val LOW_LUX = 8f
        private const val HIGH_LUX = 25f
        private val sourceKeys = setOf(ConfigReceiver.KEY_ALBUMS, ConfigReceiver.KEY_ALBUMS_DISABLED)
        private val playbackKeys =
            setOf(
                ConfigReceiver.KEY_ORIENTATION,
                ConfigReceiver.KEY_PLAYBACK_MODE,
                ConfigReceiver.KEY_SELECTED_ALBUM,
                ConfigReceiver.KEY_DELAY_MS,
                ConfigReceiver.KEY_PAIRS,
                ConfigReceiver.KEY_SHUFFLE,
                ConfigReceiver.KEY_ON_THIS_DAY,
                ConfigReceiver.KEY_ALBUMS,
                ConfigReceiver.KEY_ALBUMS_DISABLED,
            )
    }
}
