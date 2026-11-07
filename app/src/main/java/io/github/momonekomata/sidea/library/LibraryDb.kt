package io.github.momonekomata.sidea.library

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** One song in the Library: where it is, what its tags say, and what's inside the file. */
data class LibTrack(
    val id: String,
    val source: String,
    val folder: String,
    val folderName: String,
    val file: String,
    val size: Long,
    val title: String,
    val artist: String,
    val albumArtist: String,
    val album: String,
    val genre: String,
    val year: Int?,
    val track: Int?,
    val disc: Int?,
    val durationMs: Long,
    val codec: String,
    val sampleRate: Int,
    val bits: Int?,
    val channels: Int,
    val composer: String = "",
    val label: String = "",
    val compilation: Boolean = false,
) {
    /** Who the album is by: the album artist, else the track's artist. */
    val albumBy: String get() = albumArtist.ifBlank { artist }.ifBlank { UNKNOWN_ARTIST }
    val albumKey: String get() = albumKeyOf(albumBy, album)
}

const val UNKNOWN_ARTIST = "Unknown artist"
const val UNKNOWN_ALBUM = "Unknown album"
const val UNKNOWN_GENRE = "No genre"

/** Matched without regard to case in the database, so "ABBA" and "Abba" make one album. */
fun albumKeyOf(by: String, album: String) = by + "\u0001" + album

data class LibArtist(val name: String, val albums: Int, val tracks: Int, val sampleId: String)
/** [raw] is the album tag as stored (blank when there's none); [title] is what to show. */
data class LibAlbum(val raw: String, val by: String, val year: Int?, val tracks: Int, val sampleId: String, val bestRate: Int, val bestBits: Int?, val codec: String) {
    val title get() = raw.ifBlank { UNKNOWN_ALBUM }
    val key get() = albumKeyOf(by, raw)
}
data class LibGenre(val name: String, val tracks: Int, val albums: Int)

/**
 * What the Library shows: words to search for (all must match, in any tag, the file name or the
 * folder), and filters on any of the song's attributes. Empty sets mean "any".
 */
data class LibFilter(
    val query: String = "",
    /** Performers: the track's own artist. */
    val artists: Set<String> = emptySet(),
    val composers: Set<String> = emptySet(),
    val labels: Set<String> = emptySet(),
    /** 1 for mono, 2 for stereo, 3 for anything with more. */
    val channels: Set<Int> = emptySet(),
    val compilationsOnly: Boolean = false,
    /** Only the N most played songs. */
    val topPlayed: Int? = null,
    val playedWithinDays: Int? = null,
    val addedWithinDays: Int? = null,
    /** Inspector checks: see [INSPECT]. */
    val inspect: Set<String> = emptySet(),
    val genres: Set<String> = emptySet(),
    val codecs: Set<String> = emptySet(),
    val rates: Set<Int> = emptySet(),
    val bits: Set<Int> = emptySet(),
    val sources: Set<String> = emptySet(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val losslessOnly: Boolean = false,
    /** Only the songs of one list: [FAVORITES_LIST] or a playlist's id; shown in its order. */
    val list: String? = null,
) {
    /** How many filters (not the search) are set, for the Filter button. */
    val count get() = listOf(
        genres.isNotEmpty(), codecs.isNotEmpty(), rates.isNotEmpty(), bits.isNotEmpty(), sources.isNotEmpty(),
        yearFrom != null || yearTo != null, losslessOnly, artists.isNotEmpty(), composers.isNotEmpty(), labels.isNotEmpty(),
        channels.isNotEmpty(), compilationsOnly, topPlayed != null, playedWithinDays != null, addedWithinDays != null,
    ).count { it } + inspect.size // the list shown (Favourites, a playlist) is its own tab, not a Focus filter
}

/** The inspector's checks, by key: what each finds. */
val INSPECT = linkedMapOf(
    "nogenre" to "No genre",
    "nodate" to "No year",
    "noartist" to "No artist",
    "untagged" to "Untagged (title from the file name)",
    "dupes" to "Duplicates",
    "multiformat" to "Albums in several formats",
)

/** Everything the filter sheet can offer, from what's actually in the Library. */
data class LibFacets(
    val genres: List<Pair<String, Int>>,
    val codecs: List<Pair<String, Int>>,
    val rates: List<Pair<Int, Int>>,
    val bits: List<Pair<Int, Int>>,
    val years: IntRange?,
    val decades: List<Pair<Int, Int>>,
    val artists: List<Pair<String, Int>>,
    val composers: List<Pair<String, Int>>,
    val labels: List<Pair<String, Int>>,
    val channels: List<Pair<Int, Int>>,
    val compilations: Int,
    val played: Int,
    /** Songs in the Library per list ([FAVORITES_LIST], playlist ids). */
    val lists: Map<String, Int> = emptyMap(),
)

/** Ranks a song's format: lossless first, then rate, then bits; "codec|rate|bits" follows the rank. */
private const val BEST_FORMAT = "printf('%d%010d%03d|%s|%d|%s', codec IN ('FLAC','ALAC','WAV','AIFF','DSD'), rate, coalesce(bits, 0), codec, rate, coalesce(bits, ''))"

/** Focus on the favourites: the [LibFilter.list] for them. Playlists use their own ids. */
const val FAVORITES_LIST = "fav"

class LibraryDb(context: Context) : SQLiteOpenHelper(context, "library.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tracks (
                id TEXT PRIMARY KEY, source TEXT NOT NULL, folder TEXT, folderName TEXT, file TEXT, size INTEGER,
                title TEXT, artist TEXT, albumArtist TEXT, album TEXT, genre TEXT, year INTEGER, track INTEGER, disc INTEGER,
                duration INTEGER, codec TEXT, rate INTEGER, bits INTEGER, channels INTEGER,
                albumBy TEXT, haystack TEXT, probed INTEGER, seen INTEGER, added INTEGER,
                composer TEXT, label TEXT, compilation INTEGER)""",
        )
        createPlays(db)
        createLists(db)
        db.execSQL("CREATE INDEX t_source ON tracks(source)")
        db.execSQL("CREATE INDEX t_albumBy ON tracks(albumBy COLLATE NOCASE)")
        db.execSQL("CREATE INDEX t_album ON tracks(album COLLATE NOCASE)")
        db.execSQL("CREATE INDEX t_genre ON tracks(genre COLLATE NOCASE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tracks ADD COLUMN composer TEXT")
            db.execSQL("ALTER TABLE tracks ADD COLUMN label TEXT")
            db.execSQL("ALTER TABLE tracks ADD COLUMN compilation INTEGER")
            // The next scan reads every song again, for the tags read now that weren't before.
            db.execSQL("UPDATE tracks SET probed = 0")
            createPlays(db)
        }
        if (oldVersion < 3) createLists(db)
    }

    /** Favourites and playlists, mirrored from where they're kept, so Focus can filter on them. */
    private fun createLists(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS lists (list TEXT NOT NULL, id TEXT NOT NULL, pos INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS l_list ON lists(list, id)")
    }

    /** Plays of any song, in the Library or not: how many, the last one, and its names to show. */
    private fun createPlays(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS plays (id TEXT PRIMARY KEY, count INTEGER, last INTEGER, title TEXT, artist TEXT, album TEXT, size INTEGER)")
    }

    // ---- plays ----

    fun played(id: String, title: String, artist: String, album: String, size: Long) {
        val db = writableDatabase
        val now = System.currentTimeMillis()
        // Two statements rather than an upsert, which Android 10's SQLite doesn't have.
        db.execSQL("INSERT OR IGNORE INTO plays (id, count, last, title, artist, album, size) VALUES (?, 0, ?, ?, ?, ?, ?)", arrayOf(id, now, title, artist, album, size))
        db.execSQL("UPDATE plays SET count = count + 1, last = ?, title = ?, artist = ?, album = ? WHERE id = ?", arrayOf(now, title, artist, album, id))
    }

    data class Played(val id: String, val count: Int, val last: Long, val title: String, val artist: String, val album: String, val size: Long)

    fun mostPlayed(n: Int): List<Played> = readableDatabase.rawQuery(
        "SELECT id, count, last, title, artist, album, size FROM plays ORDER BY count DESC, last DESC LIMIT $n", null,
    ).use { c -> buildList { while (c.moveToNext()) add(Played(c.getString(0), c.getInt(1), c.getLong(2), c.getString(3).orEmpty(), c.getString(4).orEmpty(), c.getString(5).orEmpty(), c.getLong(6))) } }

    // ---- writing, for the scanner ----

    /** (size, whether the tags and format were read) of each song already known in [source]. */
    fun known(source: String): Map<String, Pair<Long, Boolean>> {
        val out = HashMap<String, Pair<Long, Boolean>>()
        readableDatabase.rawQuery("SELECT id, size, probed FROM tracks WHERE source = ?", arrayOf(source)).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1) to (c.getInt(2) != 0)
        }
        return out
    }

    fun markSeen(ids: Collection<String>, generation: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val st = db.compileStatement("UPDATE tracks SET seen = ? WHERE id = ?")
            for (id in ids) {
                st.bindLong(1, generation)
                st.bindString(2, id)
                st.executeUpdateDelete()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun put(rows: List<LibTrack>, probed: List<Boolean>, generation: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            rows.forEachIndexed { i, t ->
                val v = ContentValues().apply {
                    put("id", t.id); put("source", t.source); put("folder", t.folder); put("folderName", t.folderName)
                    put("file", t.file); put("size", t.size); put("title", t.title); put("artist", t.artist)
                    put("albumArtist", t.albumArtist); put("album", t.album); put("genre", t.genre); put("year", t.year)
                    put("track", t.track); put("disc", t.disc); put("duration", t.durationMs); put("codec", t.codec)
                    put("rate", t.sampleRate); put("bits", t.bits); put("channels", t.channels); put("albumBy", t.albumBy)
                    put("composer", t.composer); put("label", t.label); put("compilation", if (t.compilation) 1 else 0)
                    put("haystack", listOf(t.title, t.artist, t.albumArtist, t.album, t.genre, t.composer, t.label, t.file, t.folderName, t.codec, t.year?.toString().orEmpty()).joinToString(" ").lowercase())
                    put("probed", if (probed[i]) 1 else 0); put("seen", generation)
                }
                // Keep when it was first added across rescans.
                val added = db.rawQuery("SELECT added FROM tracks WHERE id = ?", arrayOf(t.id)).use { c -> if (c.moveToFirst()) c.getLong(0) else now }
                v.put("added", added)
                db.insertWithOnConflict("tracks", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Drops what earlier scans took for songs but isn't: playlists and cue sheets (servers label
     * them audio) and macOS's "._" twins. Returns how many went.
     */
    fun dropNonSongs(): Int {
        val names = io.github.momonekomata.sidea.sources.NOT_SONG_EXTENSIONS.map { "%.$it" }
        val where = (names.map { "lower(file) LIKE ?" } + "file LIKE '._%'").joinToString(" OR ")
        return writableDatabase.delete("tracks", where, names.toTypedArray())
    }

    /** After a complete scan: songs of [source] that weren't seen are gone from it. */
    fun dropUnseen(source: String, generation: Long) {
        writableDatabase.delete("tracks", "source = ? AND seen <> ?", arrayOf(source, generation.toString()))
    }

    fun dropSource(source: String) {
        writableDatabase.delete("tracks", "source = ?", arrayOf(source))
    }

    // ---- reading ----

    private fun where(f: LibFilter, extra: List<Pair<String, String?>> = emptyList()): Pair<String, Array<String>> {
        val parts = ArrayList<String>()
        val args = ArrayList<String>()
        for (w in f.query.lowercase().split(' ').filter { it.isNotBlank() }) {
            parts += "haystack LIKE ? ESCAPE '\\'"
            args += "%" + w.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        }
        fun inSet(col: String, values: Collection<String>) {
            if (values.isEmpty()) return
            parts += "$col IN (${values.joinToString(",") { "?" }})"
            args += values
        }
        if (f.genres.isNotEmpty()) {
            val named = f.genres - UNKNOWN_GENRE
            val noGenre = UNKNOWN_GENRE in f.genres
            val clauses = ArrayList<String>()
            if (named.isNotEmpty()) { clauses += "genre IN (${named.joinToString(",") { "?" }})"; args += named }
            if (noGenre) clauses += "(genre IS NULL OR genre = '')"
            parts += "(" + clauses.joinToString(" OR ") + ")"
        }
        inSet("codec", f.codecs)
        inSet("rate", f.rates.map { it.toString() })
        inSet("bits", f.bits.map { it.toString() })
        inSet("source", f.sources)
        f.yearFrom?.let { parts += "year >= ?"; args += it.toString() }
        f.yearTo?.let { parts += "year <= ?"; args += it.toString() }
        if (f.losslessOnly) parts += "codec IN ('FLAC','ALAC','WAV','AIFF','DSD')"
        inSet("artist", f.artists)
        inSet("composer", f.composers)
        inSet("label", f.labels)
        if (f.channels.isNotEmpty()) {
            parts += "(" + f.channels.joinToString(" OR ") { if (it >= 3) "channels >= 3" else "channels = $it" } + ")"
        }
        if (f.compilationsOnly) parts += "compilation = 1"
        f.list?.let { parts += "id IN (SELECT id FROM lists WHERE list = ?)"; args += it }
        f.topPlayed?.let { parts += "id IN (SELECT id FROM plays ORDER BY count DESC LIMIT $it)" }
        f.playedWithinDays?.let { parts += "id IN (SELECT id FROM plays WHERE last >= ?)"; args += (System.currentTimeMillis() - it * 86_400_000L).toString() }
        f.addedWithinDays?.let { parts += "added >= ?"; args += (System.currentTimeMillis() - it * 86_400_000L).toString() }
        for (k in f.inspect) when (k) {
            "nogenre" -> parts += "(genre IS NULL OR genre = '')"
            "nodate" -> parts += "year IS NULL"
            "noartist" -> parts += "(artist IS NULL OR artist = '')"
            "untagged" -> parts += "(artist IS NULL OR artist = '') AND (genre IS NULL OR genre = '') AND year IS NULL"
            "dupes" -> parts += "(lower(title) || char(1) || lower(albumBy)) IN (SELECT lower(title) || char(1) || lower(albumBy) FROM tracks GROUP BY 1 HAVING count(*) > 1)"
            "multiformat" -> parts += "(albumBy || char(1) || album) IN (SELECT albumBy || char(1) || album FROM tracks GROUP BY 1 HAVING count(DISTINCT codec) > 1)"
        }
        for ((clause, arg) in extra) {
            parts += clause
            if (arg != null) args += arg
        }
        return (if (parts.isEmpty()) "" else "WHERE " + parts.joinToString(" AND ")) to args.toTypedArray()
    }

    private fun Cursor.track() = LibTrack(
        id = getString(0), source = getString(1), folder = getString(2).orEmpty(), folderName = getString(3).orEmpty(),
        file = getString(4).orEmpty(), size = getLong(5), title = getString(6).orEmpty(), artist = getString(7).orEmpty(),
        albumArtist = getString(8).orEmpty(), album = getString(9).orEmpty(), genre = getString(10).orEmpty(),
        year = if (isNull(11)) null else getInt(11), track = if (isNull(12)) null else getInt(12), disc = if (isNull(13)) null else getInt(13),
        durationMs = getLong(14), codec = getString(15).orEmpty(), sampleRate = getInt(16), bits = if (isNull(17)) null else getInt(17),
        channels = getInt(18), composer = getString(19).orEmpty(), label = getString(20).orEmpty(), compilation = getInt(21) != 0,
    )

    private val trackCols = "id, source, folder, folderName, file, size, title, artist, albumArtist, album, genre, year, track, disc, duration, codec, rate, bits, channels, composer, label, compilation"

    /** Songs matching [f], in title order, or album order when [album] is given. */
    fun tracks(f: LibFilter, artist: String? = null, album: String? = null, genre: String? = null, source: String? = null, limit: Int = 20_000): List<LibTrack> {
        val extra = buildList {
            artist?.let { add("albumBy = ? COLLATE NOCASE" to it) }
            album?.let { add("(albumBy || char(1) || album) = ? COLLATE NOCASE" to it) }
            genre?.let { if (it == UNKNOWN_GENRE) add("(genre IS NULL OR genre = '')" to null) else add("genre = ? COLLATE NOCASE" to it) }
            source?.let { add("source = ?" to it) }
        }
        val (w, a) = where(f, extra)
        // A favourites or playlist Focus keeps the list's own order (newest favourite first).
        val inList = f.list != null && album == null
        val order = when {
            album != null -> "disc, track, file COLLATE NOCASE"
            inList -> "(SELECT min(pos) FROM lists WHERE lists.list = ? AND lists.id = tracks.id)"
            else -> "title COLLATE NOCASE, artist COLLATE NOCASE"
        }
        val args = if (inList) a + f.list!! else a
        return readableDatabase.rawQuery("SELECT $trackCols FROM tracks $w ORDER BY $order LIMIT $limit", args).use { c ->
            buildList { while (c.moveToNext()) add(c.track()) }
        }
    }

    /** One song by its id, if it's in the Library. */
    fun track(id: String): LibTrack? = readableDatabase.rawQuery("SELECT $trackCols FROM tracks WHERE id = ?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) c.track() else null
    }

    fun artists(f: LibFilter, genre: String? = null): List<LibArtist> {
        val (w, a) = where(f, listOfNotNull(genre?.let { "genre = ? COLLATE NOCASE" to it }))
        return readableDatabase.rawQuery(
            "SELECT albumBy, count(DISTINCT album), count(*), min(id) FROM tracks $w GROUP BY albumBy COLLATE NOCASE ORDER BY albumBy COLLATE NOCASE", a,
        ).use { c -> buildList { while (c.moveToNext()) add(LibArtist(c.getString(0).orEmpty().ifBlank { UNKNOWN_ARTIST }, c.getInt(1), c.getInt(2), c.getString(3))) } }
    }

    fun albums(f: LibFilter, artist: String? = null, genre: String? = null): List<LibAlbum> {
        val extra = listOfNotNull(
            artist?.let { "albumBy = ? COLLATE NOCASE" to it },
            genre?.let { if (it == UNKNOWN_GENRE) "(genre IS NULL OR genre = '')" to null else "genre = ? COLLATE NOCASE" to it },
        )
        val (w, a) = where(f, extra)
        // By an artist: newest first, like a discography. Otherwise by title.
        val order = if (artist != null) "min(year) DESC, album COLLATE NOCASE" else "album COLLATE NOCASE"
        return readableDatabase.rawQuery(
                // The album's best file, whole: lossless before lossy, then rate, then bit depth. Taking
            // max() of each column on its own would mix files into a format none of them has.
            "SELECT album, albumBy, min(year), count(*), min(id), max($BEST_FORMAT) FROM tracks $w " +
                "GROUP BY albumBy COLLATE NOCASE, album COLLATE NOCASE ORDER BY $order", a,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val (codec, rate, bits) = bestFormat(c.getString(5))
                    add(LibAlbum(c.getString(0).orEmpty(), c.getString(1).orEmpty(), if (c.isNull(2)) null else c.getInt(2), c.getInt(3), c.getString(4), rate, bits, codec))
                }
            }
        }
    }

    /** "codec|rate|bits" from [BEST_FORMAT]'s ranking string. */
    private fun bestFormat(s: String?): Triple<String, Int, Int?> {
        val p = s.orEmpty().split('|')
        return Triple(p.getOrNull(1).orEmpty(), p.getOrNull(2)?.toIntOrNull() ?: 0, p.getOrNull(3)?.toIntOrNull())
    }

    // ---- lists: favourites and playlists, for Focus ----

    /** Replaces the songs of [list] (favourites or a playlist), in their order. */
    fun setList(list: String, ids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("lists", "list = ?", arrayOf(list))
            val st = db.compileStatement("INSERT INTO lists (list, id, pos) VALUES (?, ?, ?)")
            ids.forEachIndexed { i, id ->
                st.bindString(1, list); st.bindString(2, id); st.bindLong(3, i.toLong())
                st.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** Lists that aren't among [keep] any more (a deleted playlist) go. */
    fun keepLists(keep: Set<String>) {
        val db = writableDatabase
        val have = db.rawQuery("SELECT DISTINCT list FROM lists", null).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        (have - keep).forEach { db.delete("lists", "list = ?", arrayOf(it)) }
    }

    /** How many of each list's songs are in the Library. */
    fun listCounts(): Map<String, Int> = readableDatabase.rawQuery(
        "SELECT list, count(*) FROM lists WHERE id IN (SELECT id FROM tracks) GROUP BY list", null,
    ).use { c -> buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) } }

    fun genres(f: LibFilter): List<LibGenre> {
        val (w, a) = where(f)
        return readableDatabase.rawQuery(
            "SELECT genre, count(*), count(DISTINCT albumBy || char(1) || album) FROM tracks $w GROUP BY genre COLLATE NOCASE ORDER BY genre COLLATE NOCASE", a,
        ).use { c -> buildList { while (c.moveToNext()) add(LibGenre(c.getString(0).orEmpty().ifBlank { UNKNOWN_GENRE }, c.getInt(1), c.getInt(2))) } }
    }

    /** Song count per source, with [f] applied. */
    fun sourceCounts(f: LibFilter): Map<String, Int> {
        val (w, a) = where(f)
        return readableDatabase.rawQuery("SELECT source, count(*) FROM tracks $w GROUP BY source", a).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getInt(1)) }
        }
    }

    fun totals(): Triple<Int, Int, Int> = readableDatabase.rawQuery(
        "SELECT count(*), count(DISTINCT albumBy || char(1) || album), count(DISTINCT albumBy) FROM tracks", null,
    ).use { c -> if (c.moveToFirst()) Triple(c.getInt(0), c.getInt(1), c.getInt(2)) else Triple(0, 0, 0) }

    fun facets(): LibFacets {
        val db = readableDatabase
        fun <T> counted(sql: String, value: (Cursor) -> T?): List<Pair<T, Int>> = db.rawQuery(sql, null).use { c ->
            buildList { while (c.moveToNext()) value(c)?.let { add(it to c.getInt(1)) } }
        }
        val text = { c: Cursor -> c.getString(0)?.takeIf { it.isNotBlank() } }
        val number = { c: Cursor -> if (c.isNull(0)) null else c.getInt(0) }
        val years = db.rawQuery("SELECT min(year), max(year) FROM tracks WHERE year IS NOT NULL", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0)..c.getInt(1) else null
        }
        return LibFacets(
            genres = counted("SELECT genre, count(*) FROM tracks GROUP BY genre COLLATE NOCASE ORDER BY count(*) DESC") { it.getString(0).orEmpty().ifBlank { UNKNOWN_GENRE } },
            codecs = counted("SELECT codec, count(*) FROM tracks GROUP BY codec ORDER BY count(*) DESC", text),
            rates = counted("SELECT rate, count(*) FROM tracks WHERE rate > 0 GROUP BY rate ORDER BY rate", number),
            bits = counted("SELECT bits, count(*) FROM tracks WHERE bits IS NOT NULL GROUP BY bits ORDER BY bits", number),
            years = years,
            decades = counted("SELECT year / 10 * 10, count(*) FROM tracks WHERE year IS NOT NULL GROUP BY 1 ORDER BY 1", number),
            artists = counted("SELECT artist, count(*) FROM tracks WHERE artist <> '' GROUP BY artist COLLATE NOCASE ORDER BY count(*) DESC LIMIT 200", text),
            composers = counted("SELECT composer, count(*) FROM tracks WHERE composer <> '' GROUP BY composer COLLATE NOCASE ORDER BY count(*) DESC LIMIT 200", text),
            labels = counted("SELECT label, count(*) FROM tracks WHERE label <> '' GROUP BY label COLLATE NOCASE ORDER BY count(*) DESC LIMIT 200", text),
            channels = counted("SELECT CASE WHEN channels >= 3 THEN 3 ELSE channels END, count(*) FROM tracks WHERE channels > 0 GROUP BY 1 ORDER BY 1", number),
            compilations = db.rawQuery("SELECT count(*) FROM tracks WHERE compilation = 1", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 },
            played = db.rawQuery("SELECT count(*) FROM plays", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 },
            lists = listCounts(),
        )
    }
}
