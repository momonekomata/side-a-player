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
)

typealias TrackOpener = suspend (Context) -> PlayableSource

/** One row in a folder listing. */
sealed interface Entry {
    val name: String
    data class Folder(override val name: String, val path: String) : Entry
    data class Song(override val name: String, val track: Track) : Entry
}

/** A browsable tree: the local music folder or a NAS share. */
interface Browser {
    val title: String
    val rootPath: String
    suspend fun list(path: String): List<Entry>
}

private val AUDIO_EXTENSIONS = setOf(
    "flac", "wav", "wave", "aif", "aiff", "aifc", "alac", "m4a", "mp3", "ogg", "oga", "opus", "aac", "dsf", "dff",
)

fun isAudioFile(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS

fun isLrcFile(name: String) = name.endsWith(".lrc", ignoreCase = true)

/** "Song.flac" and "Song.lrc" pair up; case doesn't matter. */
fun lrcKey(name: String) = name.substringBeforeLast('.').lowercase()

/** "03 - Artist - Title.flac" → "Title"-ish; good enough until tags are read. */
fun titleFromFileName(name: String): String {
    val base = name.substringBeforeLast('.')
    return base.replace(Regex("^\\d{1,3}[ ._-]+"), "").ifBlank { base }
}

/** Folders first, then songs, both in natural-ish order so "2" sorts before "10". */
fun List<Entry>.sortedForBrowsing(): List<Entry> {
    val key = { e: Entry -> e.name.lowercase().replace(Regex("\\d+")) { it.value.padStart(6, '0') } }
    return filterIsInstance<Entry.Folder>().sortedBy(key) + filterIsInstance<Entry.Song>().sortedBy(key)
}
