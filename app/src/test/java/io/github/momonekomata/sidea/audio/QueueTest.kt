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

    @Test
    fun `play next goes after the current song, in shuffle order and folder order`() {
        val extra = Track(id = "x", title = "X", opener = { PlayableSource.File("/x") })
        val shuffled = QueueState(listOf(tracks[2], tracks[0], tracks[1]), 0, shuffle = true, original = tracks)
        val q = shuffled.withNext(extra)
        assertEquals(listOf("t3", "x", "t1", "t2"), q.tracks.map { it.id })
        assertEquals(listOf("t1", "t2", "t3", "x"), q.original.map { it.id })
        assertEquals(0, q.index)
    }

    @Test
    fun `add to queue appends, and starts a queue when there is none`() {
        val extra = Track(id = "x", title = "X", opener = { PlayableSource.File("/x") })
        assertEquals(listOf("t1", "t2", "t3", "x"), QueueState(tracks, 1).withAdded(extra).tracks.map { it.id })
        val fresh = QueueState().withAdded(extra)
        assertEquals(0, fresh.index)
        assertEquals("x", fresh.current?.id)
    }

    @Test
    fun `removing a song before the current one keeps the current one playing`() {
        val q = QueueState(tracks, 2).without(0)
        assertEquals(listOf("t2", "t3"), q.tracks.map { it.id })
        assertEquals("t3", q.current?.id)
        assertEquals(listOf("t2", "t3"), q.original.map { it.id })
    }

    @Test
    fun `reordering up next keeps the song playing and, unshuffled, the folder order follows`() {
        val q = QueueState(tracks, 0).withUpcoming(listOf(tracks[2], tracks[1]))
        assertEquals(listOf("t1", "t3", "t2"), q.tracks.map { it.id })
        assertEquals(listOf("t1", "t3", "t2"), q.original.map { it.id })
        assertEquals("t1", q.current?.id)
        val shuffled = QueueState(listOf(tracks[1], tracks[0], tracks[2]), 0, shuffle = true, original = tracks).withUpcoming(listOf(tracks[2]))
        assertEquals(listOf("t2", "t3"), shuffled.tracks.map { it.id })
        assertEquals(listOf("t2", "t3"), shuffled.original.map { it.id })
    }
}
