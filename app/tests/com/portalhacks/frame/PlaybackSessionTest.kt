package com.portalhacks.frame

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaybackSessionTest {
    private fun wide(id: String) = Slide(id, null, width = 1600, height = 900)

    private fun tall(id: String) = Slide(id, null, portrait = true, width = 900, height = 1600)

    private val albums =
        linkedMapOf(
            "a" to listOf(wide("a1"), tall("a2"), wide("a3")),
            "empty" to emptyList(),
            "b" to listOf(wide("b1"), tall("b2")),
        )

    private fun options(
        mode: PlaybackPolicy.Mode = PlaybackPolicy.Mode.NEXT_ALBUM,
        orientation: PlaybackPolicy.Orientation = PlaybackPolicy.Orientation.AUTO,
        album: String = "a",
    ) = PlaybackSettings(orientation, mode, album)

    @Test fun nextAndPreviousCrossAlbumBoundariesUsingActualHistory() {
        val session = PlaybackSession()
        session.configure(albums, options(), false)
        val first = session.initial()!!
        session.shown(first)
        assertEquals("a", first.album)
        val second = session.next()!!
        session.shown(second)
        assertEquals(setOf("a1", "a3"), setOf(first.slide.id, second.slide.id))
        val third = session.next()!!
        session.shown(third)
        assertEquals("b1", third.slide.id)
        assertSame(second, session.previous())
        session.shown(session.previous()!!)
        assertSame(third, session.next())
        session.shown(third)
        assertTrue(session.next()!!.slide.id in setOf("a1", "a3"))
    }

    @Test fun repeatNeverEscapesSelectedAlbumAndSwitchingModesAppliesImmediately() {
        val session = PlaybackSession()
        val repeat = options(PlaybackPolicy.Mode.REPEAT_CURRENT)
        session.configure(albums, repeat, false)
        session.shown(session.initial()!!)
        repeat(8) {
            val next = session.next()!!
            assertEquals("a", next.album)
            session.shown(next)
        }
        session.configure(albums, options(album = "b"), false)
        assertEquals("b1", session.initial()!!.slide.id)
    }

    @Test fun filteringIsReappliedOnEveryRotationAndInvalidHistoryIsRemoved() {
        val session = PlaybackSession()
        session.configure(albums, options(), false)
        val first = session.initial()!!
        session.shown(first)
        session.configure(albums, options(), true)
        assertNull(session.current)
        assertNull(session.previous())
        assertEquals("a2", session.initial()!!.slide.id)
        session.shown(session.initial()!!)
        assertEquals("b2", session.next()!!.slide.id)
        session.configure(albums, options(), false)
        assertEquals((setOf("a1", "a3") - first.slide.id).single(), session.initial()!!.slide.id)
    }

    @Test fun manualPortraitIgnoresPhysicalRotationAndAllIncludesBoth() {
        val session = PlaybackSession(Random(4))
        session.configure(albums, options(orientation = PlaybackPolicy.Orientation.PORTRAIT), false)
        assertEquals("a2", session.initial()!!.slide.id)
        session.configure(albums, options(orientation = PlaybackPolicy.Orientation.PORTRAIT), true)
        assertEquals("a2", session.initial()!!.slide.id)
        session.configure(albums, options(PlaybackPolicy.Mode.SHUFFLE_ALL, PlaybackPolicy.Orientation.ALL), false)
        val seen = mutableSetOf<String>()
        repeat(5) {
            val entry = session.next()!!
            seen.add(entry.slide.id)
            session.shown(entry)
        }
        assertEquals(setOf("a1", "a2", "a3", "b1", "b2"), seen)
    }

    @Test fun disabledAlbumsAndStaleDimensionsCannotBeNavigatedBackTo() {
        val session = PlaybackSession()
        session.configure(albums, options(), false)
        val first = session.initial()!!
        session.shown(first)
        val remaining = (setOf("a1", "a3") - first.slide.id).single()
        val corrected = albums.toMutableMap().apply { put("a", listOf(tall(first.slide.id), wide(remaining))) }
        session.configure(corrected, options(), false)
        assertNull(session.current)
        assertEquals(remaining, session.initial()!!.slide.id)
        session.configure(mapOf("b" to albums.getValue("b")), options(), false)
        assertEquals("b1", session.initial()!!.slide.id)
        assertNull(session.previous())
    }

    @Test fun lateResolvedPhotosArePlayedBeforeAdvancing() {
        val session = PlaybackSession()
        session.configure(mapOf("a" to listOf(wide("last")), "b" to listOf(wide("b"))), options(), false)
        session.shown(session.initial()!!)
        session.configure(mapOf("a" to listOf(wide("new"), wide("last")), "b" to listOf(wide("b"))), options(), false)
        assertEquals("new", session.next()!!.slide.id)
    }

    @Test fun lateResolvedPhotoDoesNotReplayCompletedPhotosBeforeAdvancing() {
        val session = PlaybackSession()
        val initial = mapOf("a" to listOf(wide("a1"), wide("a3")), "b" to listOf(wide("b1")))
        session.configure(initial, options(), false)
        session.shown(session.initial()!!)
        session.shown(session.next()!!)
        session.configure(
            initial + ("a" to listOf(wide("a1"), wide("a2"), wide("a3"))),
            options(),
            false,
        )
        val late = session.next()!!
        assertEquals("a2", late.slide.id)
        session.shown(late)
        assertEquals("b1", session.next()!!.slide.id)
    }

    @Test fun nextAlbumVisitsEachPhotoOnceAndReshufflesEveryReturningCycle() {
        val session = PlaybackSession(Random(42))
        val sources =
            linkedMapOf(
                "a" to (1..5).map { wide("a$it") },
                "b" to (1..3).map { wide("b$it") },
            )
        session.configure(sources, options(), false)
        val cycles = mutableListOf<List<String>>()
        repeat(3) {
            val cycle = mutableListOf<String>()
            repeat(8) {
                val entry = session.next()!!
                cycle.add(entry.slide.id)
                session.shown(entry)
                // Match the Activity's persisted automatic album advance on each frame.
                session.configure(sources, options(album = session.selection!!), false)
            }
            assertEquals(sources.getValue("a").map { it.id }.toSet(), cycle.take(5).toSet())
            assertEquals(sources.getValue("b").map { it.id }.toSet(), cycle.drop(5).toSet())
            cycles.add(cycle)
        }
        assertTrue(cycles[0].take(5) != cycles[1].take(5))
        assertTrue(cycles[1].take(5) != cycles[2].take(5))
    }

    @Test fun failedLoadsDoNotEnterHistoryOrTrapTraversal() {
        val session = PlaybackSession()
        session.configure(albums, options(), false)
        val first = session.initial()!!
        session.shown(first)
        val failed = session.next()!!
        session.reject(failed)
        val next = session.next()!!
        assertEquals("b1", next.slide.id)
        session.shown(next)
        assertEquals(first.slide.id, session.previous()!!.slide.id)
    }

    @Test fun squareAndUnknownOnlyAlbumsHaveARealEmptyState() {
        val session = PlaybackSession()
        session.configure(
            mapOf("a" to listOf(Slide("square", null, width = 10, height = 10), Slide("unknown", null))),
            options(PlaybackPolicy.Mode.REPEAT_CURRENT),
            false,
        )
        assertNull(session.initial())
        assertNull(session.next())
    }

    @Test fun physicalAnglesRespectNaturalLandscapeDevicesAndHysteresis() {
        assertEquals(true, PhysicalOrientation.portrait(0, true))
        assertEquals(false, PhysicalOrientation.portrait(90, true))
        assertEquals(true, PhysicalOrientation.portrait(270, false))
        assertEquals(false, PhysicalOrientation.portrait(180, false))
        assertNull(PhysicalOrientation.portrait(-1, true))
        assertNull(PhysicalOrientation.portrait(45, true))
    }

    @Test fun pauseResumesRemainingIntervalAndManualNavigationResetsIt() {
        val timer = PlaybackTimer(10_000)
        timer.reset(100)
        timer.hold("user", true, 4100)
        assertEquals(6000L, timer.remaining(100_000))
        timer.hold("user", false, 100_000)
        assertEquals(6000L, timer.remaining(100_000))
        assertEquals(1L, timer.remaining(105_999))
        timer.hold("user", true, 105_999)
        timer.reset(106_000) // previous/next while paused
        assertTrue(timer.userPaused)
        assertEquals(10_000L, timer.remaining(120_000))
    }

    @Test fun lifecycleAndLowLightCannotOverrideUserPause() {
        val timer = PlaybackTimer(10_000)
        timer.reset(0)
        timer.hold("user", true, 3000)
        timer.hold("lifecycle", true, 5000)
        timer.hold("lifecycle", false, 100_000)
        timer.hold("light", true, 100_000)
        timer.hold("light", false, 110_000)
        assertTrue(timer.paused)
        assertEquals(7000L, timer.remaining(110_000))
        timer.hold("user", false, 110_000)
        assertEquals(0L, timer.remaining(117_000))
    }

    @Test fun thirdsWorkWithEitherViewportShape() {
        for (width in listOf(600, 900)) {
            assertEquals(TouchZones.Action.PREVIOUS, TouchZones.action(1f, width))
            assertEquals(TouchZones.Action.PAUSE, TouchZones.action(width / 2f, width))
            assertEquals(TouchZones.Action.NEXT, TouchZones.action(width - 1f, width))
        }
    }

    @Test
    fun strictModeCannotReusePairedHistoryAndSkipTheSecondPhoto() {
        val session = PlaybackSession()
        val sources = mapOf("a" to listOf(tall("p1"), tall("p2")))
        val all = options(PlaybackPolicy.Mode.REPEAT_CURRENT, PlaybackPolicy.Orientation.ALL).copy(pairs = true)
        session.configure(sources, all, false)
        val paired = session.initial()!!
        assertEquals("p2", paired.partner?.id)
        session.shown(paired)
        session.configure(sources, all.copy(orientation = PlaybackPolicy.Orientation.PORTRAIT), false)
        assertNull(session.current)
        val first = session.initial()!!
        assertNull(first.partner)
        session.shown(first)
        assertEquals("p2", session.next()!!.slide.id)
    }

    @Test
    fun legacyShuffleStaysWithinRepeatAndCannotShuffleNextAlbum() {
        val session = PlaybackSession(Random(8))
        session.configure(albums, options(PlaybackPolicy.Mode.REPEAT_CURRENT).copy(shuffle = true), false)
        repeat(5) {
            val frame = session.next()!!
            assertEquals("a", frame.album)
            session.shown(frame)
        }
        session.configure(albums, options().copy(shuffle = true), false)
        assertTrue(session.initial()!!.slide.id in setOf("a1", "a3"))
    }
}
