package io.github.momonekomata.sidea.audio

import io.github.momonekomata.sidea.sources.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueTest {
    private val tracks = (1..3).map { i -> Track(id = "t$i", title = "T$i", opener = { PlayableSource.File("/t$i") }) }

    @Test
    fun `repeat off stops at the end`() {
        val q = QueueState(tracks, 2)
        assertNull(q.nextIndex(auto = true))
        assertEquals(1, QueueState(tracks, 0).nextIndex(auto = false))
    }

    @Test
    fun `repeat all wraps, repeat one stays on auto but skips on demand`() {
        assertEquals(0, QueueState(tracks, 2, repeat = RepeatMode.ALL).nextIndex(auto = true))
        assertEquals(1, QueueState(tracks, 1, repeat = RepeatMode.ONE).nextIndex(auto = true))
        assertEquals(2, QueueState(tracks, 1, repeat = RepeatMode.ONE).nextIndex(auto = false))
    }
}
