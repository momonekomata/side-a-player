package io.github.momonekomata.sidea.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcTest {
    @Test
    fun `timed lines sort and repeated stamps expand`() {
        val l = Lrc.parse(
            """
            [ti:Song]
            [ar:Someone]
            [00:12.50]second
            [00:01.00][00:30.25]chorus
            [01:02.123]<00:01.00>word <00:01.50>stamps
            """.trimIndent(),
            "test",
        )!!
        assertTrue(l.synced)
        assertEquals(listOf(1000L, 12500L, 30250L, 62123L), l.lines.map { it.timeMs })
        assertEquals(listOf("chorus", "second", "chorus", "word stamps"), l.lines.map { it.text })
    }

    @Test
    fun `offset moves lines earlier`() {
        val l = Lrc.parse("[offset:+500]\n[00:02.00]a\n[00:00.20]b", "t")!!
        assertEquals(listOf(0L, 1500L), l.lines.map { it.timeMs })
    }

    @Test
    fun `line lookup`() {
        val l = Lrc.parse("[00:01.00]a\n[00:02.00]b\n[00:03.00]c", "t")!!
        assertEquals(-1, l.lineAt(500))
        assertEquals(0, l.lineAt(1000))
        assertEquals(1, l.lineAt(2999))
        assertEquals(2, l.lineAt(99_000))
    }

    @Test
    fun `plain lyrics stay unsynced`() {
        val l = Lrc.parse("\nFirst line\n\nSecond line\n", "t")!!
        assertFalse(l.synced)
        assertEquals(listOf("First line", "", "Second line"), l.lines.map { it.text })
        assertEquals(-1, l.lineAt(5000))
    }
}
