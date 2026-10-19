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
    fun initials() {
        assertEquals('B', initialOf("  the beatles"))
        assertEquals('#', initialOf("...."))
        assertEquals('漢', initialOf("東京事変"))
        assertEquals('E', initialOf("électrique"))
    }
}
