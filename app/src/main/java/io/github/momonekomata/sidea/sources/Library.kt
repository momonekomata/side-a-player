package io.github.momonekomata.sidea.sources

import android.content.Context
import io.github.momonekomata.sidea.audio.PlayableSource

/** A playable item from any source. Opening may do network work, so it is a suspend call. */
data class Track(
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val sizeBytes: Long = -1,
    val opener: TrackOpener,
    /** Opens the file for tag reading; null when that would mean downloading it (FTPS). Blocking. */
    val tagSource: ((Context) -> RandomAccess?)? = null,
    /** Reads the .lrc with the same name next to the file, when the folder has one. Blocking. */
    val lyricsFile: ((Context) -> String)? = null,
    /** Reads the folder's cover image (cover.jpg and the like), when there is one. Blocking. */
    val folderArt: ((Context) -> ByteArray)? = null,
    /** Writes lyrics as a .lrc with the same name next to the file. Blocking; throws if it can't. */
    val lyricsWriter: ((Context, String) -> Unit)? = null,
    /** When the file last changed (ms since 1970), as its folder listing says; 0 when it doesn't. */
    val modifiedMs: Long = 0,
    /** Where a downloaded copy is kept on the phone, for songs that are downloaded to play. */
    val cacheFile: ((Context) -> java.io.File)? = null,
)

/** "Tue, 04 Nov 2025 10:12:01 GMT" (WebDAV, HTTP) → ms since 1970; 0 if it isn't one. */
fun parseHttpDate(s: String?): Long = runCatching {
    java.time.ZonedDateTime.parse(s!!.trim(), java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
}.getOrDefault(0L)

/** "2025-11-04T10:12:01Z" (the cloud APIs) → ms since 1970; 0 if it isn't one. */
fun parseIsoTime(s: String?): Long = runCatching { java.time.Instant.parse(s!!.trim()).toEpochMilli() }
    .recoverCatching { java.time.OffsetDateTime.parse(s!!.trim()).toInstant().toEpochMilli() }
    .getOrDefault(0L)

/** "Song.flac" → "Song.lrc". */
fun lrcName(fileName: String) = fileName.substringBeforeLast('.') + ".lrc"

typealias TrackOpener = suspend (Context) -> PlayableSource

/** One row in a folder listing. */
sealed interface Entry {
    val name: String
    data class Folder(override val name: String, val path: String) : Entry
    data class Song(override val name: String, val track: Track) : Entry
    /** A playlist file (.m3u, .m3u8, .pls) in the folder; [read] fetches its bytes. Blocking. */
    data class ListFile(override val name: String, val modifiedMs: Long, val read: (Context) -> ByteArray) : Entry
}

private val PLAYLIST_EXTENSIONS = setOf("m3u", "m3u8", "pls")

/** A playlist file the Library reads: M3U (and its UTF-8 .m3u8) or PLS. */
fun isPlaylistFile(name: String) = !name.startsWith("._") && name.substringAfterLast('.', "").lowercase() in PLAYLIST_EXTENSIONS

/** A browsable tree: the local music folder or a NAS share. */
interface Browser {
    val title: String
    val rootPath: String
    suspend fun list(path: String): List<Entry>

    /**
     * A song again from what a saved search index kept: its [id], file [name], size, and the
     * folder it's in (that folder's path and name). Null if this browser can't place it.
     */
    fun songFromIndex(id: String, name: String, size: Long, folderPath: String, folderName: String): Track?
}

private val AUDIO_EXTENSIONS = setOf(
    "flac", "wav", "wave", "aif", "aiff", "aifc", "alac", "m4a", "mp3", "ogg", "oga", "opus", "aac", "dsf", "dff",
)

fun isAudioFile(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

/** Files that sit with the music but aren't songs, whatever type a server labels them with. */
val NOT_SONG_EXTENSIONS = setOf("m3u", "m3u8", "pls", "cue", "xspf", "wpl", "asx", "lrc", "txt", "log", "nfo", "jpg", "jpeg", "png", "pdf", "accurip", "sfv", "md5", "ffp")

/**
 * Whether a folder entry is a song: an audio file by its name, or one a server says is audio
 * (some name their files oddly). Playlists and cue sheets are labelled audio by servers too
 * (audio/x-mpegurl, audio/x-cue), and macOS leaves "._" twins of every song on a NAS: none of
 * those are songs.
 */
fun isSongFile(name: String, mime: String?): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    if (name.startsWith("._") || ext in NOT_SONG_EXTENSIONS) return false
    if (ext in AUDIO_EXTENSIONS) return true
    val m = mime?.lowercase() ?: return false
    return m.startsWith("audio/") && listOf("mpegurl", "scpls", "cue", "asx", "playlist", "xspf").none { m.contains(it) }
}

fun isLrcFile(name: String) = name.endsWith(".lrc", ignoreCase = true)

/**
 * Folders that hold no music of yours: hidden ones (.thumbnails), and a NAS's own: Synology's
 * recycle bin and snapshots (#recycle, #snapshot) and its system folders (@eaDir thumbnails,
 * @tmp). QNAP's @Recycle starts with @ too.
 */
fun isSystemFolder(name: String) =
    name.startsWith(".") || name.startsWith("@") || name.equals("#recycle", ignoreCase = true) || name.equals("#snapshot", ignoreCase = true)

/** "Song.flac" and "Song.lrc" pair up; case doesn't matter. */
fun lrcKey(name: String) = name.substringBeforeLast('.').lowercase()

/** "03 - Artist - Title.flac" → "Title"-ish; good enough until tags are read. */
fun titleFromFileName(name: String): String {
    val base = name.substringBeforeLast('.')
    return base.replace(Regex("^\\d{1,3}[ ._-]+"), "").ifBlank { base }
}

/** Folders first, then songs, then playlist files, each in natural-ish order so "2" sorts before "10". */
fun List<Entry>.sortedForBrowsing(): List<Entry> {
    val key = { e: Entry -> e.name.lowercase().replace(Regex("\\d+")) { it.value.padStart(6, '0') } }
    return filterIsInstance<Entry.Folder>().sortedBy(key) + filterIsInstance<Entry.Song>().sortedBy(key) + filterIsInstance<Entry.ListFile>().sortedBy(key)
}
