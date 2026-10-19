package io.github.momonekomata.sidea.lyrics

data class LyricLine(val timeMs: Long, val text: String)

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
}

/** LRC: `[mm:ss.xx]text`, several stamps per line allowed, `[offset:±ms]` honoured, word stamps dropped. */
object Lrc {
    private val stamp = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val wordStamp = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
    private val offsetTag = Regex("""^\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
    private val metaTag = Regex("""^\[[a-zA-Z]+:.*]$""")

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
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val ms = when (frac.length) {
                        0 -> 0L
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.take(3).toLong()
                    }
                    times += (min * 60 + sec) * 1000 + ms
                }
                val words = line.substring(end).replace(wordStamp, "").trim()
                times.forEach { timed += LyricLine(it, words) }
            } else if (!metaTag.matches(line.trim())) {
                plain += line.trimEnd()
            }
        }
        if (timed.isNotEmpty()) {
            // A positive offset shows lyrics earlier.
            val lines = timed.map { it.copy(timeMs = (it.timeMs - offset).coerceAtLeast(0)) }.sortedBy { it.timeMs }
            return Lyrics(lines, synced = true, source = source)
        }
        val lines = plain.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }
        if (lines.isEmpty()) return null
        return Lyrics(lines.map { LyricLine(-1, it) }, synced = false, source = source)
    }
}
