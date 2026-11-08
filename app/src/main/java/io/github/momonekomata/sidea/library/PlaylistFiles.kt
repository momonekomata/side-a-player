package io.github.momonekomata.sidea.library

import android.content.Context
import android.util.Log
import io.github.momonekomata.sidea.sources.Favorite
import io.github.momonekomata.sidea.sources.Playlist
import io.github.momonekomata.sidea.sources.Playlists
import java.text.Normalizer
import java.util.UUID

/** One song in a playlist file: where the file says it is, and the names and length it gives it. */
data class ListEntry(val path: String, val artist: String = "", val title: String = "", val seconds: Int = -1)

/** A playlist file as read: its own name for itself (#PLAYLIST, if any) and its songs. */
data class ListFile(val name: String?, val entries: List<ListEntry>)

/**
 * Playlist files: M3U (.m3u, and .m3u8, its UTF-8 form) and PLS, read and written. A song in one
 * is found in the Library by its file name and as much of its folder path as matches, so a
 * playlist made on a computer (D:\Music\Artist\Album\01.flac), on another phone
 * (/storage/emulated/0/Music/…) or relative to where it sits (../Album/01.flac) all find the
 * Library's copy. Failing that, by the artist and title the file gives it.
 */
object PlaylistFiles {
    private const val TAG = "WALKDECK.Playlists"

    // ---- reading ----

    /** The file's text: UTF-8 when it is (and .m3u8 always is), else the Windows code page old .m3u files use. */
    fun decode(bytes: ByteArray): String {
        val b = if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) bytes.copyOfRange(3, bytes.size) else bytes
        return runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(b)).toString()
        }.getOrElse { String(b, charset("windows-1252")) }
    }

    fun parse(fileName: String, text: String): ListFile =
        if (fileName.endsWith(".pls", ignoreCase = true) || text.trimStart().startsWith("[playlist]", ignoreCase = true)) parsePls(text) else parseM3u(text)

    fun parseM3u(text: String): ListFile {
        var name: String? = null
        val out = ArrayList<ListEntry>()
        var info: Triple<Int, String, String>? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> {}
                line.startsWith("#EXTINF:", ignoreCase = true) -> info = extinf(line.substring(8))
                line.startsWith("#PLAYLIST:", ignoreCase = true) -> name = line.substring(10).trim().ifEmpty { null }
                line.startsWith("#") -> {}
                else -> {
                    val (secs, artist, title) = info ?: Triple(-1, "", "")
                    out += ListEntry(line, artist, title, secs)
                    info = null
                }
            }
        }
        return ListFile(name, out)
    }

    /** "123,Artist - Title" (and "123 tvg-id=…,Title") → (seconds, artist, title). */
    private fun extinf(s: String): Triple<Int, String, String> {
        val comma = s.indexOf(',')
        val secs = (if (comma >= 0) s.substring(0, comma) else s).trim().takeWhile { it == '-' || it.isDigit() }.toIntOrNull() ?: -1
        val shown = if (comma >= 0) s.substring(comma + 1).trim() else ""
        val dash = shown.indexOf(" - ")
        return if (dash > 0) Triple(secs, shown.substring(0, dash).trim(), shown.substring(dash + 3).trim()) else Triple(secs, "", shown)
    }

    fun parsePls(text: String): ListFile {
        val files = sortedMapOf<Int, String>()
        val titles = HashMap<Int, String>()
        val lengths = HashMap<Int, Int>()
        val key = Regex("^(File|Title|Length)(\\d+)$", RegexOption.IGNORE_CASE)
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            val m = key.matchEntire(line.substring(0, eq).trim()) ?: continue
            val n = m.groupValues[2].toInt()
            val v = line.substring(eq + 1).trim()
            when (m.groupValues[1].lowercase()) {
                "file" -> files[n] = v
                "title" -> titles[n] = v
                "length" -> v.toIntOrNull()?.let { lengths[n] = it }
            }
        }
        return ListFile(null, files.map { (n, path) ->
            val shown = titles[n].orEmpty()
            val dash = shown.indexOf(" - ")
            if (dash > 0) ListEntry(path, shown.substring(0, dash).trim(), shown.substring(dash + 3).trim(), lengths[n] ?: -1)
            else ListEntry(path, "", shown, lengths[n] ?: -1)
        })
    }

    // ---- paths ----

    /** "%20" and the like → the characters (as UTF-8); a "+" stays a "+", as file names have them. */
    private fun percentDecode(s: String): String {
        if (!Regex("%[0-9A-Fa-f]{2}").containsMatchIn(s)) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && s.substring(i + 1, i + 3).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                out.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return out.toString("UTF-8")
    }

    /** Whether [path] says where it is on its own (a drive, the root, a URL), rather than from the playlist's folder. */
    fun isAbsolute(path: String) = path.startsWith("/") || path.startsWith("\\") || Regex("^[A-Za-z]:").containsMatchIn(path) || path.contains("://")

    /**
     * A path's folders and file name, as written (decoded), whatever its form: a URL (its path),
     * a Windows or Unix path, or a phone folder's id ("primary:Music/Album").
     */
    fun rawSegments(path: String): List<String> {
        var p = path.trim()
        val scheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").find(p)
        if (scheme != null) {
            p = p.substring(scheme.range.last + 1)
            // file:///storage/… has no host; http://host/music/… does.
            p = if (p.startsWith("/")) p else p.substringAfter('/', "")
        }
        p = percentDecode(p)
        return p.split('/', '\\', ':').filter { it.isNotEmpty() && it != "." }
    }

    /** For comparing: case, accents' encoding (macOS NAS shares keep them decomposed) and width don't matter. */
    fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase()

    /** [entry] from the folder [base] (its segments), "..", absolute paths and all; normalized for comparing. */
    fun resolve(base: List<String>?, entry: String): List<String> {
        val start = if (base == null || isAbsolute(entry)) emptyList() else base
        val out = ArrayList(start.map(::norm))
        for (s in rawSegments(entry)) {
            if (s == "..") { if (out.isNotEmpty()) out.removeAt(out.size - 1) } else out += norm(s)
        }
        return out
    }

    // ---- matching ----

    /** The Library's songs, ready to be looked up by file name, folder and names. */
    class Index(tracks: List<LibTrack>) {
        private class Row(val t: LibTrack, val folder: List<String>)

        private val byFile = HashMap<String, MutableList<Row>>()
        private val byBase = HashMap<String, MutableList<Row>>()
        private val byNames = HashMap<String, MutableList<Row>>()
        private val byTitle = HashMap<String, MutableList<Row>>()

        init {
            for (t in tracks) {
                // A folder id that isn't a path (Google Drive, OneDrive): its name is what there is.
                val folder = if (t.folder.contains('/')) rawSegments(t.folder).map(::norm) else listOf(norm(t.folderName))
                val r = Row(t, folder)
                byFile.getOrPut(norm(t.file)) { ArrayList() } += r
                byBase.getOrPut(norm(t.file.substringBeforeLast('.'))) { ArrayList() } += r
                byNames.getOrPut(names(t.artist.ifBlank { t.albumBy }, t.title)) { ArrayList() } += r
                if (t.albumArtist.isNotBlank() && t.albumArtist != t.artist) byNames.getOrPut(names(t.albumArtist, t.title)) { ArrayList() } += r
                byTitle.getOrPut(norm(t.title)) { ArrayList() } += r
            }
        }

        private fun names(artist: String, title: String) = norm(artist) + "\u0001" + norm(title)

        /**
         * The Library's song for [e]. [base] is the playlist's own folder (its segments) when it's
         * in a Library folder, and [source] that folder's source: relative paths start there, and a
         * song there wins a tie.
         */
        fun match(e: ListEntry, base: List<String>? = null, source: String? = null): LibTrack? {
            val segs = resolve(base, e.path)
            val file = segs.lastOrNull()
            val dirs = segs.dropLast(1)
            fun best(rows: List<Row>?) = rows?.maxWithOrNull(compareBy<Row>({ commonTail(it.folder, dirs) }, { it.t.source == source }))?.t
            if (file != null) {
                best(byFile[file])?.let { return it }
                // The same song in another format (the playlist names the .mp3, the Library has the .flac).
                val stem = file.substringBeforeLast('.')
                byBase[stem]?.filter { commonTail(it.folder, dirs) > 0 || dirs.isEmpty() }?.let { best(it) }?.let { return it }
            }
            if (e.title.isNotBlank()) {
                if (e.artist.isNotBlank()) byNames[names(e.artist, e.title)]?.let { return best(it) }
                else byTitle[norm(e.title)]?.singleOrNull()?.let { return it.t }
            }
            return null
        }

        /** How many folders, from the innermost out, two paths share. */
        private fun commonTail(a: List<String>, b: List<String>): Int {
            var n = 0
            while (n < a.size && n < b.size && a[a.size - 1 - n] == b[b.size - 1 - n]) n++
            return n
        }
    }

    private fun favorite(t: LibTrack) = Favorite(t.id, t.title, t.artist.ifBlank { t.albumArtist }, t.album, t.size)

    /** The playlist's songs that are in the Library, in order, and how many aren't. */
    fun songsOf(file: ListFile, index: Index, base: List<String>? = null, source: String? = null): Pair<List<Favorite>, Int> {
        var missing = 0
        val songs = file.entries.mapNotNull { e -> index.match(e, base, source)?.let(::favorite) ?: run { missing++; null } }
        return songs to missing
    }

    private fun index() = Index(Library.db.tracks(LibFilter(), limit = Int.MAX_VALUE))

    private fun nameOf(file: ListFile, fileName: String) = file.name ?: fileName.substringBeforeLast('.').ifBlank { "Playlist" }

    /** A playlist file picked on the phone, as a new playlist (not yet kept), and how many of its songs aren't in the Library. */
    fun import(fileName: String, bytes: ByteArray): Pair<Playlist, Int> {
        val file = parse(fileName, decode(bytes))
        val (songs, missing) = songsOf(file, index())
        return Playlist(UUID.randomUUID().toString(), nameOf(file, fileName), songs) to missing
    }

    /** A playlist file found in a Library folder by a scan. */
    class Found(val folder: String, val name: String, val modifiedMs: Long, val read: (Context) -> ByteArray)

    /**
     * After a scan of [source]: its playlist files join the playlists, or update the one read from
     * the same file before when the file changed. One deleted in the app stays deleted. A file
     * none of whose songs are in the Library (yet) is tried again next scan.
     */
    fun found(context: Context, source: LibrarySource, files: List<Found>) {
        if (files.isEmpty()) return
        val dismissed = Playlists.dismissed()
        val index by lazy { index() }
        for (f in files) {
            val origin = "${source.id}|${f.folder}|${f.name}"
            if (origin in dismissed) continue
            val bytes = runCatching { f.read(context) }.onFailure { Log.w(TAG, "can't read ${f.name}", it) }.getOrNull() ?: continue
            val stamp = "${bytes.size}:${bytes.contentHashCode()}"
            val had = Playlists.all.value.firstOrNull { it.origin == origin }
            if (had?.stamp == stamp) continue
            val file = parse(f.name, decode(bytes))
            val (songs, _) = songsOf(file, index, rawSegments(f.folder).map(::norm), source.id)
            if (songs.isEmpty()) continue
            Playlists.update { list ->
                if (had != null) list.map { if (it.origin == origin) it.copy(songs = songs, stamp = stamp) else it }
                else list + Playlist(UUID.randomUUID().toString(), nameOf(file, f.name), songs, origin, stamp)
            }
        }
    }

    // ---- writing ----

    /**
     * [p] as an .m3u8. A Library song is written as its path inside its Library folder
     * ("Artist/Album/01 Song.flac"), so other players find it when the file is saved in that
     * folder, and this one finds it wherever it's saved. Other songs keep their address.
     */
    fun export(p: Playlist): String {
        val sources = Library.sources.value.associateBy { it.id }
        val sb = StringBuilder("#EXTM3U\n#PLAYLIST:").append(p.name.replace('\n', ' ')).append('\n')
        for (f in p.songs) {
            val t = runCatching { Library.db.track(f.id) }.getOrNull()
            val secs = t?.durationMs?.takeIf { it > 0 }?.let { (it / 1000).toInt() } ?: -1
            val artist = t?.let { it.artist.ifBlank { it.albumArtist } } ?: f.artist
            val title = t?.title ?: f.title
            sb.append("#EXTINF:").append(secs).append(',')
            sb.append(if (artist.isNotBlank()) "$artist - $title" else title).append('\n')
            sb.append(if (t != null) pathIn(t, sources[t.source]) else f.id).append('\n')
        }
        return sb.toString()
    }

    /** "Artist/Album/01 Song.flac": the song's place inside its Library folder. */
    fun pathIn(t: LibTrack, source: LibrarySource?): String {
        if (!t.folder.contains('/')) return listOf(t.folderName, t.file).filter { it.isNotEmpty() }.joinToString("/")
        val folder = rawSegments(t.folder)
        val root = source?.root?.let(::rawSegments).orEmpty()
        val inside = if (root.isNotEmpty() && folder.size >= root.size && folder.take(root.size).map(::norm) == root.map(::norm)) folder.drop(root.size) else folder
        return (inside + t.file).joinToString("/")
    }
}
