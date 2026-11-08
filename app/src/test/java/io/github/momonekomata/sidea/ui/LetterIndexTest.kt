package io.github.momonekomata.sidea.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LetterIndexTest {
    @Test
    fun firstEntryOfEachLetterInListOrder() {
        val labels = listOf("Abbey Road", "Animals", "Blue", "The Bends", "1999", "Élan", "ไม่รู้", "さくら", "(What's the Story)")
        assertEquals(
            listOf('#' to 6, 'A' to 2, 'B' to 4, 'E' to 7, 'W' to 10, 'ก' to 8, 'あ' to 9),
            letterIndex(labels, firstItem = 2),
        )
    }

    @Test
    fun listsSortTheWayTheIndexFilesThem() {
        val albums = listOf(
            "\"PODIUM\" EPISODE 02", "#好きなんだ", "(What's the Story) Morning Glory", "Unknown album", "Abbey Road",
            "1999", "The Bends", "Élan", "after hours", "Pablo Honey", "さくら", "...", "Wish You Were Here",
        )
        val sorted = io.github.momonekomata.sidea.library.LibOrder.sorted(albums) { it }
        assertEquals(
            listOf(
                "...", "1999", "Abbey Road", "after hours", "The Bends", "Élan", "Pablo Honey", "\"PODIUM\" EPISODE 02",
                "(What's the Story) Morning Glory", "Wish You Were Here", "さくら", "#好きなんだ", "Unknown album",
            ),
            sorted,
        )
        // Each letter's first entry, so tapping it lands on the right row: A is "Abbey Road" (2).
        val index = letterIndex(sorted, firstItem = 0).toMap()
        assertEquals(2, index['A'])
        assertEquals(6, index['P'])
        assertEquals(8, index['W'])
    }

    @Test
    fun initials() {
        assertEquals('B', initialOf("  the beatles"))
        assertEquals('#', initialOf("...."))
        assertEquals('漢', initialOf("東京事変"))
        assertEquals('E', initialOf("électrique"))
        assertEquals('R', initialOf("ＲＡ:ONLY vol.1"))
        assertEquals('#', initialOf("÷"))
        // Mathematical bold letters, as some releases style their titles.
        assertEquals('R', initialOf("𝗥𝗔:𝗢𝗡𝗟𝗬 vol.1"))
        assertEquals(
            listOf("Quiet", "𝗥𝗔:ONLY", "Silence"),
            io.github.momonekomata.sidea.library.LibOrder.sorted(listOf("Silence", "𝗥𝗔:ONLY", "Quiet")) { it },
        )
    }
}
