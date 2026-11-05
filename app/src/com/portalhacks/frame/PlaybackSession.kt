package com.portalhacks.frame

import kotlin.random.Random

internal data class PlaybackEntry(val album: String, val slide: Slide, val partner: Slide? = null)

/** The only owner of selection and actual display history; renderers never advance their own list. */
@Suppress("TooManyFunctions") // Cohesive navigation state machine, covered by sequence tests.
internal class PlaybackSession(private val random: Random = Random.Default) {
    private var albums: Map<String, List<Slide>> = emptyMap()
    private var mode = PlaybackPolicy.Mode.SHUFFLE_ALL
    private var orientation = PlaybackPolicy.Orientation.AUTO
    private var portrait = false
    private var pairing = false
    private var shuffleWithin = false
    private val shuffled get() =
        mode == PlaybackPolicy.Mode.SHUFFLE_ALL || (mode == PlaybackPolicy.Mode.REPEAT_CURRENT && shuffleWithin)
    private val history = mutableListOf<PlaybackEntry>()
    private var cursor = -1
    private val failed = mutableSetOf<String>()
    private val deck = mutableListOf<PlaybackEntry>()
    private val completed = mutableMapOf<String, MutableSet<String>>()
    private var available: Map<String, List<Slide>> = emptyMap()
    var selection: String? = null
        private set
    val current: PlaybackEntry? get() = history.getOrNull(cursor)?.takeIf { allowed(it) }

    fun configure(
        sources: Map<String, List<Slide>>,
        settings: PlaybackSettings,
        screenPortrait: Boolean,
    ) {
        val reset = mode != settings.mode || selection != settings.album
        val previousPool = available.mapValues { (_, slides) -> slides.map { it.id } }
        val rotated =
            portrait != screenPortrait || orientation != settings.orientation ||
                shuffleWithin != settings.shuffle
        albums = sources
        mode = settings.mode
        orientation = settings.orientation
        portrait = screenPortrait
        pairing = settings.pairs && orientation == PlaybackPolicy.Orientation.ALL &&
            mode != PlaybackPolicy.Mode.NEXT_ALBUM
        selection = settings.album
        shuffleWithin = settings.shuffle
        available = filterSources()
        val poolChanged = previousPool != available.mapValues { (_, slides) -> slides.map { it.id } }
        if (reset || rotated || poolChanged) deck.clear()
        if (reset) {
            completed.clear()
            history.clear()
            cursor = -1
        } else {
            val old = current
            val retained = history.take(cursor + 1).filter { allowed(it) }.size
            history.removeAll { !allowed(it) }
            cursor = if (old != null) retained - 1 else -1
        }
    }

    private fun eligible(): Map<String, List<Slide>> = available

    private fun filterSources(): Map<String, List<Slide>> =
        albums.mapValues { (_, slides) ->
            slides.filter { it.id !in failed && PlaybackPolicy.matches(it, orientation, portrait) }.distinctBy { it.id }
        }

    fun allowed(entry: PlaybackEntry): Boolean {
        if (entry.partner != null && !pairing) return false
        val slides = eligible()[entry.album].orEmpty()
        if (mode == PlaybackPolicy.Mode.REPEAT_CURRENT && entry.album != selected()) return false
        return slides.any { it.id == entry.slide.id } &&
            (entry.partner == null || slides.any { it.id == entry.partner.id })
    }

    private fun selected(): String? = selection?.takeIf { it in albums } ?: albums.keys.firstOrNull()

    fun initial(): PlaybackEntry? =
        current ?: when {
            shuffled -> shuffledNext()
            else -> {
                val available = eligible()
                val album = PlaybackPolicy.selectedAlbum(available, selected(), mode)
                available[album]?.firstOrNull()?.let { frame(album!!, it) }
            }
        }

    fun next(): PlaybackEntry? {
        history.getOrNull(cursor + 1)?.takeIf { allowed(it) }?.let { return it }
        val now = current ?: return initial()
        if (shuffled) {
            return shuffledNext()
        }
        val available = eligible()
        val slides = available[now.album].orEmpty()
        val step = if (now.partner == null) 1 else 2
        val index = slides.indexOfFirst { it.id == now.slide.id } + step
        if (index in slides.indices) return frame(now.album, slides[index])
        if (mode == PlaybackPolicy.Mode.NEXT_ALBUM) {
            slides.firstOrNull { it.id !in completed[now.album].orEmpty() }?.let { return frame(now.album, it) }
        }
        val album =
            if (mode == PlaybackPolicy.Mode.NEXT_ALBUM) {
                PlaybackPolicy.nextAlbum(available, now.album)
            } else {
                now.album
            }
        completed[album]?.clear()
        return available[album]?.firstOrNull()?.let { frame(album!!, it) }
    }

    fun previous(): PlaybackEntry? = history.take(cursor.coerceAtLeast(0)).lastOrNull { allowed(it) }

    /** Called only after a frame really becomes visible, never when a load merely starts. */
    fun shown(entry: PlaybackEntry) {
        if (!allowed(entry)) return
        val back = history.indexOfLast { it === entry }
        if (back >= 0) {
            cursor = back
        } else if (current?.let { it.album == entry.album && it.slide.id == entry.slide.id } != true) {
            while (history.size > cursor + 1) history.removeAt(history.lastIndex)
            history.add(entry)
            if (history.size > 200) history.removeAt(0)
            cursor = history.lastIndex
        }
        completed.getOrPut(entry.album) { mutableSetOf() }.add(entry.slide.id)
        if (mode == PlaybackPolicy.Mode.NEXT_ALBUM) selection = entry.album
    }

    fun reject(entry: PlaybackEntry) {
        failed.add(entry.slide.id)
        available = filterSources()
        deck.removeAll { it.slide.id == entry.slide.id }
    }

    fun retryFailures() {
        failed.clear()
        available = filterSources()
    }

    private fun frame(
        album: String,
        slide: Slide,
    ): PlaybackEntry {
        val slides = eligible()[album].orEmpty()
        val next = slides.getOrNull(slides.indexOfFirst { it.id == slide.id } + 1)
        val partner = next?.takeIf { pairing && it.portrait != portrait && slide.portrait != portrait }
        return PlaybackEntry(album, slide, partner)
    }

    private fun shuffledNext(): PlaybackEntry? {
        if (deck.isEmpty()) {
            val pool =
                if (mode == PlaybackPolicy.Mode.REPEAT_CURRENT) {
                    eligible().filterKeys { it == selected() }
                } else {
                    eligible()
                }
            val entries =
                pool.flatMap { (album, slides) -> slides.map { PlaybackEntry(album, it) } }
                    .distinctBy { it.slide.id }.shuffled(random)
                    .sortedBy { it.slide.caption?.endsWith("✨") != true }
            deck.addAll(entries)
            if (deck.size > 1 && deck.first().slide.id == current?.slide?.id) {
                deck.add(deck.removeAt(0))
            }
        }
        if (deck.isEmpty()) return null
        val first = deck.removeAt(0)
        val companion =
            deck.firstOrNull {
                pairing && it.album == first.album && it.slide.portrait != portrait && first.slide.portrait != portrait
            }
        if (companion != null) deck.remove(companion)
        return first.copy(partner = companion?.slide)
    }
}

/** Monotonic hold timer. Independent pause reasons cannot accidentally resume each other. */
internal class PlaybackTimer(var durationMs: Long) {
    private var remainingMs = durationMs
    private var deadline = 0L
    private val holds = mutableSetOf<String>()
    val paused: Boolean get() = holds.isNotEmpty()
    val userPaused: Boolean get() = "user" in holds

    fun remaining(now: Long): Long = if (paused) remainingMs else (deadline - now).coerceAtLeast(0)

    fun reset(now: Long) {
        remainingMs = durationMs
        deadline = now + durationMs
    }

    fun hold(
        reason: String,
        enabled: Boolean,
        now: Long,
    ) {
        if (enabled) {
            if (!paused) remainingMs = remaining(now)
            holds.add(reason)
        } else if (holds.remove(reason) && !paused) {
            deadline = now + remainingMs
        }
    }
}

internal object TouchZones {
    enum class Action { PREVIOUS, PAUSE, NEXT }

    fun action(
        x: Float,
        width: Int,
    ): Action =
        when {
            x < width / 3f -> Action.PREVIOUS
            x < width * 2f / 3f -> Action.PAUSE
            else -> Action.NEXT
        }
}

/** Sensor angles are relative to the display's natural orientation, which may be landscape. */
internal object PhysicalOrientation {
    fun portrait(
        angle: Int,
        naturalPortrait: Boolean,
    ): Boolean? =
        when (angle) {
            in 0..20, in 160..200, in 340..359 -> naturalPortrait
            in 70..110, in 250..290 -> !naturalPortrait
            else -> null // flat/unknown/diagonal: retain the last stable orientation
        }
}
