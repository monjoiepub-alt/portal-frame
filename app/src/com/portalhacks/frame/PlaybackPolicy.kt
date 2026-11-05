package com.portalhacks.frame

/** Pure playback rules shared by the Android UI and JVM tests. */
internal object PlaybackPolicy {
    const val MAX_DURATION_MS = 86_400_000L
    const val LOCAL_ALBUM = "local:uploads"
    const val SAMPLE_ALBUM = "local:samples"

    enum class Orientation { AUTO, LANDSCAPE, PORTRAIT, ALL }

    enum class Mode { REPEAT_CURRENT, NEXT_ALBUM, SHUFFLE_ALL }

    fun duration(
        hours: String,
        minutes: String,
        seconds: String,
    ): Long? {
        val parts =
            listOf(hours, minutes, seconds).map { text ->
                if (text.isBlank()) 0L else text.toLongOrNull() ?: return null
            }
        if (parts.any { it < 0 }) return null
        if (parts[0] > 24 || parts[1] > 59 || parts[2] > 59) return null
        return ((parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000)
            .takeIf { it in 1000..MAX_DURATION_MS }
    }

    /** EXIF orientations 5–8 swap the axes, including mirrored rotations. */
    fun displayDimensions(
        width: Int,
        height: Int,
        exifOrientation: Int,
    ): Pair<Int, Int> = if (exifOrientation in 5..8) height to width else width to height

    fun matches(
        slide: Slide,
        orientation: Orientation,
        screenPortrait: Boolean,
    ): Boolean {
        if (orientation == Orientation.ALL) return true
        if (slide.width <= 0 || slide.height <= 0 || slide.width == slide.height) return false
        val portrait =
            when (orientation) {
                Orientation.AUTO -> screenPortrait
                Orientation.PORTRAIT -> true
                else -> false
            }
        return (slide.height > slide.width) == portrait
    }

    /** Preserve album order, skipping empty albums and wrapping without duplicating photos. */
    fun nextAlbum(
        albums: Map<String, List<Slide>>,
        current: String?,
    ): String? {
        val keys = albums.keys.toList()
        val start = keys.indexOf(current)
        for (offset in 1..keys.size) {
            val key = keys[(start + offset).mod(keys.size)]
            if (albums.getValue(key).isNotEmpty()) return key
        }
        return null
    }

    fun selectedAlbum(
        albums: Map<String, List<Slide>>,
        selected: String?,
        mode: Mode,
    ): String? {
        val key = selected?.takeIf { it in albums } ?: albums.keys.firstOrNull()
        return if (mode == Mode.NEXT_ALBUM && albums[key].isNullOrEmpty()) nextAlbum(albums, key) else key
    }

    fun items(
        albums: Map<String, List<Slide>>,
        selected: String?,
        mode: Mode,
    ): List<Slide> {
        val slides = if (mode == Mode.SHUFFLE_ALL) albums.values.flatten() else albums[selected].orEmpty()
        return slides.distinctBy { it.id }
    }
}

/** Per-album progress survives catalog refreshes; late-resolved photos must not be skipped. */
internal class AlbumProgress {
    private val completed = mutableSetOf<String>()

    fun complete(id: String) {
        completed.add(id)
    }

    fun remaining(slides: List<Slide>): List<Slide> = slides.filter { it.id !in completed }.distinctBy { it.id }

    fun reset() {
        completed.clear()
    }
}
