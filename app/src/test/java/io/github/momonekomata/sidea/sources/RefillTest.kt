package io.github.momonekomata.sidea.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RefillTest {
    private val mb = 1024L * 1024

    @Test
    fun waitsTenSecondsAheadWhenTheDownloadKeepsUp() {
        // DSD128 stereo: ~1.4 MB/s; the link does 5 MB/s.
        val rate = 1_411_200L
        val t = refillTarget(want = 50 * mb, size = 400 * mb, playRate = rate, written = 50 * mb, elapsedNs = 10_000_000_000L)
        assertEquals(50 * mb + rate * 10, t)
    }

    @Test
    fun onASlowLinkWaitsUntilTheRestArrivesInTime() {
        // Plays at 2.8 MB/s (DSD256), downloads at 2 MB/s.
        val rate = 2_822_400L
        val size = 800 * mb
        val want = 100 * mb
        val elapsed = 50_000_000_000L
        val t = refillTarget(want, size, rate, written = 100 * mb, elapsedNs = elapsed)
        val download = (100 * mb * 1e9 / elapsed)
        // From t on, the download finishes no later than playback reaches the end.
        val downloadLeft = (size - t) / download
        val playLeft = (size - want).toDouble() / rate
        assertTrue(downloadLeft <= playLeft + 0.01)
        assertTrue(t > want + rate * 10)
    }

    @Test
    fun guessesFromTheSizeBeforeTheRateIsKnown() {
        val t = refillTarget(want = 4096, size = 240 * mb, playRate = 0, written = 0, elapsedNs = 0)
        assertEquals(4096 + mb * 10, t)
    }

    @Test
    fun unknownSizeStillWaitsTwoMegabytes() {
        assertEquals(1000 + 2 * mb, refillTarget(want = 1000, size = -1, playRate = 0, written = 0, elapsedNs = 0))
    }
}
