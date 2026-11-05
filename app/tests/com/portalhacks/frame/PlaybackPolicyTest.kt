package com.portalhacks.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackPolicyTest {
    private val landscape = Slide("wide", null, width = 1600, height = 900)
    private val portrait = Slide("tall", null, width = 900, height = 1600)
    private val square = Slide("square", null, width = 900, height = 900)
    private val unknown = Slide("unknown", null)

    @Test fun compoundDurationAndBoundaries() {
        assertEquals(150_000L, PlaybackPolicy.duration("0", "2", "30"))
        assertEquals(1000L, PlaybackPolicy.duration("", "", "1"))
        assertEquals(86_400_000L, PlaybackPolicy.duration("24", "0", "0"))
        assertEquals(3_723_000L, PlaybackPolicy.duration("1", "2", "3"))
    }

    @Test fun invalidDurationCannotBeSaved() {
        for (parts in listOf(
            listOf("0", "0", "0"),
            listOf("-1", "0", "0"),
            listOf("24", "0", "1"),
            listOf("0", "60", "0"),
            listOf("0", "0", "60"),
            listOf("a", "0", "0"),
            listOf("999999999999999999999999", "0", "0"),
            listOf("0", "0", "1.5"),
        )) assertNull(PlaybackPolicy.duration(parts[0], parts[1], parts[2]))
    }

    @Test fun exifRotationAndMirroringUseDisplayDimensions() {
        for (exif in 1..8) {
            val (w, h) = PlaybackPolicy.displayDimensions(1600, 900, exif)
            val rotated = Slide("exif", null, width = w, height = h)
            assertEquals(exif in 5..8, PlaybackPolicy.matches(rotated, PlaybackPolicy.Orientation.PORTRAIT, false))
        }
    }

    @Test fun autoRecalculatesOnRotation() {
        val photos = listOf(landscape, portrait, square, unknown)

        fun eligible(vertical: Boolean) =
            photos.filter {
                PlaybackPolicy.matches(it, PlaybackPolicy.Orientation.AUTO, vertical)
            }
        assertEquals(listOf(landscape), eligible(false))
        assertEquals(listOf(portrait), eligible(true))
        assertEquals(listOf(landscape), eligible(false))
        assertEquals(4, photos.size) // filtering never mutates the source
    }

    @Test fun manualOrientationIgnoresDisplayRotation() {
        for (vertical in listOf(true, false)) {
            assertTrue(PlaybackPolicy.matches(portrait, PlaybackPolicy.Orientation.PORTRAIT, vertical))
            assertFalse(PlaybackPolicy.matches(landscape, PlaybackPolicy.Orientation.PORTRAIT, vertical))
            assertTrue(PlaybackPolicy.matches(landscape, PlaybackPolicy.Orientation.LANDSCAPE, vertical))
        }
    }

    @Test fun strictModesExcludeSquareAndMissingDimensions() {
        for (mode in PlaybackPolicy.Orientation.entries.filter { it != PlaybackPolicy.Orientation.ALL }) {
            assertFalse(PlaybackPolicy.matches(square, mode, false))
            assertFalse(PlaybackPolicy.matches(unknown, mode, true))
        }
        assertTrue(PlaybackPolicy.matches(square, PlaybackPolicy.Orientation.ALL, false))
        assertTrue(PlaybackPolicy.matches(unknown, PlaybackPolicy.Orientation.ALL, true))
    }

    @Test fun traversalSkipsEmptyAlbumsAndWraps() {
        val albums = linkedMapOf("a" to listOf(landscape), "empty" to emptyList(), "b" to listOf(portrait))
        assertEquals("b", PlaybackPolicy.nextAlbum(albums, "a"))
        assertEquals("a", PlaybackPolicy.nextAlbum(albums, "b"))
        assertEquals("a", PlaybackPolicy.nextAlbum(albums, "removed"))
        assertNull(PlaybackPolicy.nextAlbum(emptyMap(), "a"))
        assertNull(PlaybackPolicy.nextAlbum(mapOf("empty" to emptyList()), "empty"))
    }

    @Test fun repeatKeepsOnlyTheSelectedAlbum() {
        val albums = linkedMapOf("a" to listOf(landscape), "b" to listOf(portrait))
        repeat(3) {
            assertEquals("b", PlaybackPolicy.selectedAlbum(albums, "b", PlaybackPolicy.Mode.REPEAT_CURRENT))
            assertEquals(listOf(portrait), PlaybackPolicy.items(albums, "b", PlaybackPolicy.Mode.REPEAT_CURRENT))
        }
        assertEquals(emptyList<Slide>(), PlaybackPolicy.items(albums, "missing", PlaybackPolicy.Mode.REPEAT_CURRENT))
    }

    @Test fun nextModeSkipsAnAlbumAfterRotationButRepeatStaysEmpty() {
        val albums = linkedMapOf("wide" to emptyList(), "tall" to listOf(portrait))
        assertEquals("tall", PlaybackPolicy.selectedAlbum(albums, "wide", PlaybackPolicy.Mode.NEXT_ALBUM))
        assertEquals("wide", PlaybackPolicy.selectedAlbum(albums, "wide", PlaybackPolicy.Mode.REPEAT_CURRENT))
    }

    @Test fun singleEligibleAlbumRepeatsIndefinitely() {
        val albums = linkedMapOf("empty" to emptyList(), "one" to listOf(landscape))
        var current: String? = "one"
        repeat(10) {
            current = PlaybackPolicy.nextAlbum(albums, current)
            assertEquals("one", current)
        }
    }

    @Test fun shuffleAllDeduplicatesAcrossAlbums() {
        val albums = linkedMapOf("a" to listOf(landscape), "b" to listOf(landscape, portrait))
        assertEquals(listOf(landscape, portrait), PlaybackPolicy.items(albums, "a", PlaybackPolicy.Mode.SHUFFLE_ALL))
    }

    @Test
    fun traversalWaitsForEveryPhotoIncludingLateResolvedBounds() {
        val progress = AlbumProgress()
        progress.complete(portrait.id)
        assertEquals(listOf(landscape), progress.remaining(listOf(landscape, portrait)))
        progress.complete(landscape.id)
        assertTrue(progress.remaining(listOf(landscape, portrait)).isEmpty())
        progress.reset()
        assertEquals(listOf(landscape, portrait), progress.remaining(listOf(landscape, portrait)))
    }

    @Test
    fun rotationDoesNotTreatNewlyEligiblePhotosAsAlreadyDisplayed() {
        val progress = AlbumProgress()
        progress.complete(landscape.id)
        assertEquals(listOf(portrait), progress.remaining(listOf(portrait)))
        assertTrue(progress.remaining(listOf(landscape)).isEmpty())
    }
}
