package io.github.momonekomata.sidea.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JapaneseTest {
    @Test
    fun romajiFromKana() {
        assertEquals("kyou", Japanese.toRomaji("きょう"))
        assertEquals("kitto", Japanese.toRomaji("きっと"))
        assertEquals("matcha", Japanese.toRomaji("まっちゃ"))
        assertEquals("kon'ya", Japanese.toRomaji("こんや"))
        assertEquals("shinbun", Japanese.toRomaji("しんぶん"))
        assertEquals("raamen", Japanese.toRomaji("らーめん"))
    }

    @Test
    fun furiganaGoesOverTheKanjiOnly() {
        assertEquals(listOf(Ruby("食", "た"), Ruby("べる", null)), Japanese.furigana("食べる", "たべる"))
        assertEquals(listOf(Ruby("お", null), Ruby("茶", "ちゃ")), Japanese.furigana("お茶", "おちゃ"))
        assertEquals(listOf(Ruby("東京", "とうきょう")), Japanese.furigana("東京", "とうきょう"))
        assertEquals(listOf(Ruby("さくら", null)), Japanese.furigana("さくら", "さくら"))
    }

    @Test
    fun wholeLine() {
        val l = Japanese.annotate("君の名前を呼んでいる")
        // The pieces put the line back together exactly, so karaoke stays lined up.
        assertEquals("君の名前を呼んでいる", l.pieces.joinToString("") { it.text })
        assertEquals("きみ", l.pieces.first { it.text == "君" }.ruby)
        assertEquals("なまえ", l.pieces.first { it.text == "名前" }.ruby)
        assertEquals("kimi no namae o yon de iru", l.romaji)
    }

    @Test
    fun particleWaAndMixedText() {
        val l = Japanese.annotate("今日は Rock だ")
        assertEquals("今日は Rock だ", l.pieces.joinToString("") { it.text })
        assertTrue(l.romaji, l.romaji.startsWith("kyou wa"))
        assertTrue(l.romaji, "Rock" in l.romaji)
    }

    @Test
    fun kanaMeansJapaneseHanziAloneDoesnt() {
        assertTrue(Japanese.isJapanese(listOf("夜空", "きらきら")))
        assertFalse(Japanese.isJapanese(listOf("月亮代表我的心")))
    }
}
