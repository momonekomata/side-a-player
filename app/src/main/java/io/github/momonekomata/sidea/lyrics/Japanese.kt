package io.github.momonekomata.sidea.lyrics

import com.atilika.kuromoji.ipadic.Tokenizer

/**
 * A piece of a lyric line for furigana: [text] as written, and [ruby], the reading to print
 * above it in hiragana, only where [text] has kanji. Pieces cover the line in order, so their
 * lengths add up to it, which keeps karaoke's character count lined up.
 */
data class Ruby(val text: String, val ruby: String?)

/** A Japanese line ready to show: furigana pieces and its romaji. */
data class JapaneseLine(val pieces: List<Ruby>, val romaji: String)

/**
 * Readings for Japanese lyrics: furigana over the kanji, and romaji for the whole line. Kuromoji
 * with the IPADIC dictionary works out each word and how it's read in context; the dictionary is
 * loaded the first time Japanese lyrics are shown (a second or two, once).
 */
object Japanese {
    private val tokenizer: Tokenizer by lazy { Tokenizer() }

    /** Japanese, not Chinese: kanji alone could be either, kana says Japanese. */
    fun isJapanese(lines: List<String>): Boolean = lines.any { l -> l.any { it.isKana() } }

    fun annotate(line: String): JapaneseLine {
        val pieces = ArrayList<Ruby>()
        val romaji = StringBuilder()
        for (t in tokenizer.tokenize(line)) {
            val surface = t.surface
            val reading = t.reading.takeIf { it != "*" && it.isNotBlank() }?.toHiragana()
            pieces += furigana(surface, reading)
            // Romaji: a word at a time, spaced; particles said the way they're said.
            val sound = when {
                t.partOfSpeechLevel1 == "助詞" && surface == "は" -> "wa"
                t.partOfSpeechLevel1 == "助詞" && surface == "へ" -> "e"
                t.partOfSpeechLevel1 == "助詞" && surface == "を" -> "o"
                reading != null -> toRomaji(reading)
                surface.any { it.isKana() } -> toRomaji(surface.toHiragana())
                else -> surface
            }
            if (sound.isBlank()) {
                if (surface.isNotBlank() && romaji.isNotEmpty() && !romaji.endsWith(" ")) romaji.append(' ')
                continue
            }
            val joins = sound.first().isLetter().not() || t.partOfSpeechLevel1 == "記号"
            if (romaji.isNotEmpty() && !romaji.endsWith(" ") && !joins) romaji.append(' ')
            romaji.append(sound)
        }
        return JapaneseLine(merge(pieces), romaji.toString().replace(Regex(" +"), " ").trim())
    }

    /**
     * Furigana for one word: the reading goes over the kanji only. Kana the word shares with its
     * reading at either end (食べる / たべる → 食 with た, then べる) are left bare.
     */
    internal fun furigana(surface: String, reading: String?): List<Ruby> {
        if (reading == null || surface.none { it.isKanji() }) return listOf(Ruby(surface, null))
        val s = surface.toHiragana()
        var head = 0
        while (head < s.length && head < reading.length && !surface[head].isKanji() && s[head] == reading[head]) head++
        var tail = 0
        while (tail < s.length - head && tail < reading.length - head &&
            !surface[surface.length - 1 - tail].isKanji() && s[s.length - 1 - tail] == reading[reading.length - 1 - tail]
        ) tail++
        val out = ArrayList<Ruby>()
        if (head > 0) out += Ruby(surface.substring(0, head), null)
        val coreRuby = reading.substring(head, reading.length - tail)
        out += Ruby(surface.substring(head, surface.length - tail), coreRuby.ifEmpty { null })
        if (tail > 0) out += Ruby(surface.substring(surface.length - tail), null)
        return out
    }

    /** Neighbouring pieces without furigana become one, so lines wrap like normal text. */
    private fun merge(pieces: List<Ruby>): List<Ruby> {
        val out = ArrayList<Ruby>()
        for (p in pieces) {
            val last = out.lastOrNull()
            if (last != null && last.ruby == null && p.ruby == null) out[out.size - 1] = Ruby(last.text + p.text, null) else out += p
        }
        return out
    }

    // ---- kana ----

    private fun Char.isKana() = this in 'ぁ'..'ゖ' || this in 'ァ'..'ヺ' || this == 'ー'
    private fun Char.isKanji() = this in '一'..'鿿' || this in '㐀'..'䶿' || this == '々' || this == 'ヶ'

    /** Katakana to hiragana; the long-vowel mark and everything else stay as they are. */
    internal fun String.toHiragana() = map { if (it in 'ァ'..'ヶ') it - 0x60 else it }.joinToString("")

    private val SINGLE = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'ゐ' to "i", 'ゑ' to "e", 'を' to "o", 'ん' to "n", 'ゔ' to "vu",
        'ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o", 'ゎ' to "wa",
    )

    /** Kana followed by a small ya/yu/yo (or small vowel) read as one sound. */
    private val PAIRS = mapOf(
        "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo", "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
        "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho", "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo",
        "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho", "ぢゃ" to "ja", "ぢゅ" to "ju", "ぢょ" to "jo",
        "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo", "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
        "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo", "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
        "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo", "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
        "しぇ" to "she", "じぇ" to "je", "ちぇ" to "che", "てぃ" to "ti", "でぃ" to "di", "とぅ" to "tu", "どぅ" to "du",
        "ふぁ" to "fa", "ふぃ" to "fi", "ふぇ" to "fe", "ふぉ" to "fo", "うぃ" to "wi", "うぇ" to "we", "うぉ" to "wo",
        "ゔぁ" to "va", "ゔぃ" to "vi", "ゔぇ" to "ve", "ゔぉ" to "vo", "つぁ" to "tsa", "つぃ" to "tsi", "つぇ" to "tse", "つぉ" to "tso",
    )

    /** Hepburn romaji for hiragana: small っ doubles the next consonant, ー repeats the last vowel. */
    internal fun toRomaji(hira: String): String {
        val out = StringBuilder()
        var double = false
        var i = 0
        while (i < hira.length) {
            val c = hira[i]
            val pair = if (i + 1 < hira.length) PAIRS[hira.substring(i, i + 2)] else null
            val sound = pair ?: SINGLE[c]
            when {
                c == 'っ' -> { double = true; i++; continue }
                c == 'ー' -> out.lastOrNull { it in "aeiou" }?.let { out.append(it) }
                sound != null -> {
                    if (double) {
                        // っち is "tchi"; otherwise the consonant doubles.
                        out.append(if (sound.startsWith("ch")) 't' else sound.first())
                        double = false
                    }
                    // ん before a vowel or y is written n' so it doesn't read as na, nu…
                    if (out.endsWith("n") && i > 0 && hira[i - 1] == 'ん' && sound.first() in "aeiouy") out.append('\'')
                    out.append(sound)
                    if (pair != null) i++
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
