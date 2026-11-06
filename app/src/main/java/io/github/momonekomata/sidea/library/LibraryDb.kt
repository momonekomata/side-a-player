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
    val genres: Set<String> = emptySet(),
    val codecs: Set<String> = emptySet(),
    val rates: Set<Int> = emptySet(),
    val bits: Set<Int> = emptySet(),
    val sources: Set<String> = emptySet(),
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val losslessOnly: Boolean = false,
) {
    /** How many filters (not the search) are set, for the Filter button. */
    val count get() = listOf(genres.isNotEmpty(), codecs.isNotEmpty(), rates.isNotEmpty(), bits.isNotEmpty(), sources.isNotEmpty(), yearFrom != null || yearTo != null, losslessOnly).count { it }
}

/** Everything the filter sheet can offer, from what's actually in the Library. */
data class LibFacets(val genres: List<Pair<String, Int>>, val codecs: List<String>, val rates: List<Int>, val bits: List<Int>, val years: IntRange?)

class LibraryDb(context: Context) : SQLiteOpenHelper(context, "library.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tracks (
                id TEXT PRIMARY KEY, source TEXT NOT NULL, folder TEXT, folderName TEXT, file TEXT, size INTEGER,
                title TEXT, artist TEXT, albumArtist TEXT, album TEXT, genre TEXT, year INTEGER, track INTEGER, disc INTEGER,
                duration INTEGER, codec TEXT, rate INTEGER, bits INTEGER, channels INTEGER,
                albumBy TEXT, haystack TEXT, probed INTEGER, seen INTEGER, added INTEGER)""",
        )
        db.execSQL("CREATE INDEX t_source ON tracks(source)")
        db.execSQL("CREATE INDEX t_albumBy ON tracks(albumBy COLLATE NOCASE)")
        db.execSQL("CREATE INDEX t_album ON tracks(album COLLATE NOCASE)")
        db.execSQL("CREATE INDEX t_genre ON tracks(genre COLLATE NOCASE)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

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
                    put("haystack", listOf(t.title, t.artist, t.albumArtist, t.album, t.genre, t.file, t.folderName, t.codec, t.year?.toString().orEmpty()).joinToString(" ").lowercase())
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
        channels = getInt(18),
    )

    private val trackCols = "id, source, folder, folderName, file, size, title, artist, albumArtist, album, genre, year, track, disc, duration, codec, rate, bits, channels"

    /** Songs matching [f], in title order, or album order when [album] is given. */
    fun tracks(f: LibFilter, artist: String? = null, album: String? = null, genre: String? = null, source: String? = null, limit: Int = 20_000): List<LibTrack> {
        val extra = buildList {
            artist?.let { add("albumBy = ? COLLATE NOCASE" to it) }
            album?.let { add("(albumBy || char(1) || album) = ? COLLATE NOCASE" to it) }
            genre?.let { if (it == UNKNOWN_GENRE) add("(genre IS NULL OR genre = '')" to null) else add("genre = ? COLLATE NOCASE" to it) }
            source?.let { add("source = ?" to it) }
        }
        val (w, a) = where(f, extra)
        val order = if (album != null) "disc, track, file COLLATE NOCASE" else "title COLLATE NOCASE, artist COLLATE NOCASE"
        return readableDatabase.rawQuery("SELECT $trackCols FROM tracks $w ORDER BY $order LIMIT $limit", a).use { c ->
            buildList { while (c.moveToNext()) add(c.track()) }
        }
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
            "SELECT album, albumBy, min(year), count(*), min(id), max(rate), max(bits), max(codec) FROM tracks $w " +
                "GROUP BY albumBy COLLATE NOCASE, album COLLATE NOCASE ORDER BY $order", a,
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(LibAlbum(c.getString(0).orEmpty(), c.getString(1).orEmpty(), if (c.isNull(2)) null else c.getInt(2), c.getInt(3), c.getString(4), c.getInt(5), if (c.isNull(6)) null else c.getInt(6), c.getString(7).orEmpty()))
                }
            }
        }
    }

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
        val genres = db.rawQuery("SELECT genre, count(*) FROM tracks GROUP BY genre COLLATE NOCASE ORDER BY count(*) DESC", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0).orEmpty().ifBlank { UNKNOWN_GENRE } to c.getInt(1)) }
        }
        fun strings(sql: String) = db.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) c.getString(0)?.takeIf { it.isNotBlank() }?.let(::add) } }
        fun ints(sql: String) = db.rawQuery(sql, null).use { c -> buildList { while (c.moveToNext()) if (!c.isNull(0)) add(c.getInt(0)) } }
        val years = db.rawQuery("SELECT min(year), max(year) FROM tracks WHERE year IS NOT NULL", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getInt(0)..c.getInt(1) else null
        }
        return LibFacets(
            genres,
            strings("SELECT DISTINCT codec FROM tracks ORDER BY codec"),
            ints("SELECT DISTINCT rate FROM tracks WHERE rate > 0 ORDER BY rate"),
            ints("SELECT DISTINCT bits FROM tracks WHERE bits IS NOT NULL ORDER BY bits"),
            years,
        )
    }
}
