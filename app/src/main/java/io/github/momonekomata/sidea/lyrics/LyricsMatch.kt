package io.github.momonekomata.sidea.lyrics

/**
 * Streaming apps report titles like "Song (feat. X) - 2011 Remaster" and artists like
 * "A, B & C"; lyric databases file them as "Song" by "A". These give the lookup cleaner
 * variants to try after the exact one.
 */
object LyricsMatch {
    private val bracketed = Regex(
        """\s*[(\[](?:feat\.?|ft\.?|featuring|with|prod\.?|from|remaster(?:ed)?|\d{4}\s+remaster(?:ed)?|live|mono|stereo|demo|acoustic|explicit|clean|bonus|deluxe|single|radio|album|extended|original|version|edit)\b[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val dashed = Regex(
        """\s+[-–—]\s+(?:\d{4}\s+)?(?:remaster(?:ed)?|live|mono|stereo|demo|acoustic|radio edit|single version|album version|edit|version|from\s|bonus|re-?recorded|\d{4}\s+mix)\b.*$""",
        RegexOption.IGNORE_CASE,
    )
    private val artistSplit = Regex("""\s*(?:,|&|\band\b|\bx\b|\bfeat\.?|\bft\.?|\bfeaturing\b|\bwith\b|;)\s*""", RegexOption.IGNORE_CASE)

    fun cleanTitle(title: String): String {
        var t = title
        repeat(3) { t = t.replace(bracketed, "").replace(dashed, "") }
        return t.trim().ifEmpty { title.trim() }
    }

    fun primaryArtist(artist: String): String =
        artist.split(artistSplit).firstOrNull { it.isNotBlank() }?.trim() ?: artist.trim()

    /** (title, artist) pairs to try, most specific first, without repeats. */
    fun variants(title: String, artist: String): List<Pair<String, String>> =
        listOf(
            title.trim() to artist.trim(),
            cleanTitle(title) to artist.trim(),
            cleanTitle(title) to primaryArtist(artist),
        ).filter { it.first.isNotBlank() }.distinct()
}
