package io.github.momonekomata.sidea.library

import io.github.momonekomata.sidea.sources.parseHttpDate
import io.github.momonekomata.sidea.sources.parseIsoTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RescanTest {
    @Test fun aRetaggedSongOfTheSameSizeIsReadAgain() {
        val known = KnownSong(size = 1000, probed = true, modified = 1_700_000_000_000)
        assertTrue(known.unchanged(1000, 1_700_000_000_000))
        // Tag editors write into the padding: same size, new time.
        assertFalse(known.unchanged(1000, 1_700_000_100_000))
        assertFalse(known.unchanged(1200, 1_700_000_000_000))
    }

    @Test fun withoutTimesOnlyTheSizeCounts() {
        // A listing that doesn't say when files changed.
        assertTrue(KnownSong(1000, true, 0).unchanged(1000, 0))
        // Scanned before times were kept: read again once, to pick the time up.
        assertFalse(KnownSong(1000, true, 0).unchanged(1000, 1_700_000_000_000))
    }

    @Test fun newOrUnreadSongsAreRead() {
        val none: KnownSong? = null
        assertFalse(none.unchanged(1000, 5))
        assertFalse(KnownSong(1000, probed = false, modified = 5).unchanged(1000, 5))
    }

    @Test fun listingTimes() {
        assertEquals(1_762_251_121_000, parseHttpDate("Tue, 04 Nov 2025 10:12:01 GMT"))
        assertEquals(0L, parseHttpDate("not a date"))
        assertEquals(0L, parseHttpDate(null))
        assertEquals(1_762_251_121_000, parseIsoTime("2025-11-04T10:12:01Z"))
        assertEquals(1_762_251_121_500, parseIsoTime("2025-11-04T10:12:01.500Z"))
        assertEquals(1_762_251_121_000, parseIsoTime("2025-11-04T11:12:01+01:00"))
        assertEquals(0L, parseIsoTime(""))
    }
}
