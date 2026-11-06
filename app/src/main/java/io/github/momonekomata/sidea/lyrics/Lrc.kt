package io.github.momonekomata.sidea.lyrics

/**
 * One line. [words] are the enhanced-LRC word stamps when the lyrics have them: where in [text]
 * each word starts, and when it's sung. Plain timed lyrics have none, and karaoke spreads the line
 * over its time instead.
 */
data class LyricLine(val timeMs: Long, val text: String, val words: List<WordTime> = emptyList())

data class WordTime(val charStart: Int, val timeMs: Long)

/** Lyrics ready to show. Unsynced lyrics have every timeMs at -1. */
data class Lyrics(val lines: List<LyricLine>, val synced: Boolean, val source: String) {
    /** Index of the line being sung at [ms], or -1 before the first one. */
    fun lineAt(ms: Long): Int {
        if (!synced) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= ms) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    /**
     * Karaoke: how many characters of line [i] have been sung at [ms], fractions included, for
     * the colour that sweeps across the line. With word stamps it goes word by word (and through
     * each word evenly); without, it spreads the line evenly over its time, which suits Japanese
     * and Chinese, written without spaces, as well as anything else. A line's time ends at the
     * next line, but never runs longer than the line could take to sing, so a long instrumental
     * break after it doesn't stretch the sweep out.
     */
    fun sungChars(i: Int, ms: Long): Float {
        val line = lines.getOrNull(i) ?: return 0f
        val len = line.text.length
        if (!synced || len == 0 || ms < line.timeMs) return 0f
        val next = lines.getOrNull(i + 1)?.timeMs ?: (line.timeMs + 6_000)
        val end = minOf(next, line.timeMs + maxOf(3_000L, len * 600L))
        if (line.words.isNotEmpty()) {
            val w = line.words.indexOfLast { it.timeMs <= ms }
            if (w < 0) return 0f
            val from = line.words[w]
            val to = line.words.getOrNull(w + 1)
            val toChar = to?.charStart ?: len
            val toTime = to?.timeMs ?: end.coerceAtLeast(from.timeMs + 1)
            val p = ((ms - from.timeMs).toFloat() / (toTime - from.timeMs).coerceAtLeast(1)).coerceIn(0f, 1f)
            return from.charStart + p * (toChar - from.charStart)
        }
        val p = ((ms - line.timeMs).toFloat() / (end - line.timeMs).coerceAtLeast(1)).coerceIn(0f, 1f)
        return p * len
    }
}

/**
 * LRC: `[mm:ss.xx]text`, several stamps per line allowed, `[offset:±ms]` honoured, and
 * enhanced LRC's word stamps (`<mm:ss.xx>word`) kept for karaoke.
 */
object Lrc {
    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordStamp = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")

    private fun ms(min: String, sec: String, frac: String): Long {
        val f = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return (min.toLong() * 60 + sec.toLong()) * 1000 + f
    }

    private fun stampText(t: Long) = "%02d:%02d.%02d".format(t / 60_000, t / 1000 % 60, t % 1000 / 10)

    /** The line's text without word stamps, and where each stamped word starts in it. */
    private fun words(raw: String): Pair<String, List<WordTime>> {
        val text = StringBuilder()
        val words = ArrayList<WordTime>()
        var at = 0
        for (m in wordStamp.findAll(raw)) {
            text.append(raw, at, m.range.first)
            words += WordTime(text.length, ms(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
            at = m.range.last + 1
        }
        text.append(raw, at, raw.length)
        // Trim, keeping the word positions pointing at the same characters.
        val lead = text.length - text.trimStart().length
        val trimmed = text.toString().trim()
        val shifted = words.map { it.copy(charStart = (it.charStart - lead).coerceIn(0, trimmed.length)) }
            // A stamp that closes the line (enhanced LRC ends with one) starts no word.
            .filter { it.charStart < trimmed.length }
            .distinctBy { it.charStart }
        return trimmed to shifted
    }
    private val offsetTag = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("""^\[[a-zA-Z]+:.*]$""")

    /** LRC text for [l]: `[mm:ss.xx]` stamps when it's timed, the plain lines when not. */
    fun format(l: Lyrics, title: String, artist: String, album: String): String = buildString {
        if (artist.isNotBlank()) append("[ar:").append(artist).append("]\n")
        if (title.isNotBlank()) append("[ti:").append(title).append("]\n")
        if (album.isNotBlank()) append("[al:").append(album).append("]\n")
        append("[re:WALKDECK · ").append(l.source).append("]\n")
        for (line in l.lines) {
            if (l.synced) append("[").append(stampText(line.timeMs.coerceAtLeast(0))).append("]")
            if (line.words.isEmpty()) {
                append(line.text)
            } else {
                // Word stamps go back in front of their words.
                var at = 0
                for (w in line.words) {
                    append(line.text, at, w.charStart)
                    append("<").append(stampText(w.timeMs)).append(">")
                    at = w.charStart
                }
                append(line.text, at, line.text.length)
            }
            append('\n')
        }
    }

    fun parse(text: String, source: String): Lyrics? {
        val raw = text.replace("\r\n", "\n").replace('\r', '\n').trim()
        if (raw.isEmpty()) return null
        var offset = 0L
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<String>()
        for (line in raw.lines()) {
            offsetTag.find(line)?.let { offset = it.groupValues[1].toLong() }
            val stamps = stamp.findAll(line).toList()
            if (stamps.isNotEmpty() && line.startsWith("[") && stamps.first().range.first == 0) {
                // Only stamps at the start of the line count; the text is what follows the last one.
                var end = 0
                val times = mutableListOf<Long>()
                for (m in stamps) {
                    if (m.range.first != end) break
                    end = m.range.last + 1
                    times += ms(m.groupValues[1], m.groupValues[2], m.groupValues[3])
                }
                val (text, words) = words(line.substring(end))
                times.forEach { timed += LyricLine(it, text, words) }
            } else if (!metaTag.matches(line.trim())) {
                plain += line.trimEnd()
            }
        }
        if (timed.isNotEmpty()) {
            // A positive offset shows lyrics earlier.
            val lines = timed.map { l ->
                l.copy(timeMs = (l.timeMs - offset).coerceAtLeast(0), words = l.words.map { it.copy(timeMs = (it.timeMs - offset).coerceAtLeast(0)) })
            }.sortedBy { it.timeMs }
            return Lyrics(lines, synced = true, source = source)
        }
        val lines = plain.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (lines.isEmpty()) return null
        return Lyrics(lines.map { LyricLine(-1, it) }, synced = false, source = source)
    }
}
