package io.github.momonekomata.sidea.library

import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/**
 * The Library's A–Z order, the same one the letter index down the card's edge uses, so a letter
 * jumps to where its entries are: "The Beatles" under B, "(What's the Story) Morning Glory"
 * under W, "\"PODIUM\" EPISODE 02" under P, accents off (É with E). Digits and symbols come first
 * (#), then A–Z, then scripts without an alphabet order of their own (Greek, Cyrillic, Thai,
 * kana, kanji, hangul), one group each. Unknown artists, albums and genres go last.
 */
object LibOrder {
    /** The index letter [label] files under. */
    fun initialOf(label: String): Char {
        val cp = sortText(label).codePoints().findFirst().orElse(-1)
        if (cp < 0 || Character.isDigit(cp)) return '#'
        // Accents off (É → E) and full-width letters to plain ones (Ｒ → R).
        val plain = Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKD)[0].uppercaseChar()
        if (plain in 'A'..'Z') return plain
        return when (Character.UnicodeScript.of(cp)) {
            Character.UnicodeScript.THAI -> 'ก'
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> 'あ'
            Character.UnicodeScript.HAN -> '漢'
            Character.UnicodeScript.HANGUL -> '한'
            Character.UnicodeScript.CYRILLIC -> 'Я'
            Character.UnicodeScript.GREEK -> 'Ω'
            else -> '#'
        }
    }

    /** Where an index letter's group comes: # first, then A–Z, then the other scripts. */
    fun rank(c: Char) = when {
        c == '#' -> 0
        c in 'A'..'Z' -> 1
        else -> 2
    }

    /** What [label] sorts by: without a leading "The " and without the symbols before its first letter or digit. */
    fun sortText(label: String): String {
        var t = label.trim()
        if (t.startsWith("the ", ignoreCase = true)) t = t.substring(4).trimStart()
        // By whole characters: styled letters (𝗥𝗔, 𝓐) lie outside the basic range, in two halves.
        var i = 0
        while (i < t.length) {
            val cp = t.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) return t.substring(i)
            i += Character.charCount(cp)
        }
        return ""
    }

    private val unknown = setOf(UNKNOWN_ARTIST, UNKNOWN_ALBUM, UNKNOWN_GENRE)

    /** [items] in the Library's A–Z order of [label]. */
    fun <T> sorted(items: List<T>, label: (T) -> String): List<T> {
        if (items.size < 2) return items
        // A collator of its own: they aren't safe to share between threads.
        val collator = Collator.getInstance(Locale.ROOT).apply { strength = Collator.SECONDARY }
        class Key(val last: Boolean, val rank: Int, val initial: Char, val text: java.text.CollationKey)
        val keys = items.map { item ->
            val l = label(item)
            val i = initialOf(l)
            // NFKC: styled and full-width letters sort as the plain ones they stand for.
            Key(l in unknown, rank(i), i, collator.getCollationKey(Normalizer.normalize(sortText(l), Normalizer.Form.NFKC)))
        }
        val order = compareBy<Int>({ keys[it].last }, { keys[it].rank }, { keys[it].initial })
            .thenComparator { a, b -> keys[a].text.compareTo(keys[b].text) }
        return items.indices.sortedWith(order).map { items[it] }
    }
}
