package io.github.momonekomata.sidea.sources

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Tags read so far, by track id. Titles in the UI fall back to the file name until they arrive. */
object TagStore {
    private val _tags = MutableStateFlow<Map<String, Tags>>(emptyMap())
    val tags: StateFlow<Map<String, Tags>> = _tags.asStateFlow()
    private val done = ConcurrentHashMap.newKeySet<String>()
    // A few at a time keeps a NAS responsive while a big folder is being read.
    private val gate = Semaphore(3)

    suspend fun load(context: Context, track: Track): Tags? {
        _tags.value[track.id]?.let { return it }
        if (track.id in done) return null
        val open = track.tagSource ?: return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                val ra = runCatching { open(context) }
                    .onFailure { Log.w("SideA.Tags", "can't open ${track.id} for tags", it) }
                    .getOrNull()
                    ?: return@withPermit null // not readable yet (e.g. FTPS before caching): try later
                val tags = runCatching { ra.use { TagReader.read(it) } }
                    .onFailure { Log.w("SideA.Tags", "bad tags in ${track.id}", it) }
                    .getOrNull()
                done += track.id
                if (tags != null) _tags.update { it + (track.id to tags) }
                tags
            }
        }
    }
}

fun Track.displayTitle(tags: Map<String, Tags>) = tags[id]?.title?.takeIf { it.isNotBlank() } ?: title
fun Track.displayArtist(tags: Map<String, Tags>) = tags[id]?.artist?.takeIf { it.isNotBlank() } ?: artist
fun Track.displayAlbum(tags: Map<String, Tags>) = tags[id]?.album?.takeIf { it.isNotBlank() } ?: album

/** "Artist - Title" file names are common enough to be worth splitting when tags are missing. */
fun guessArtistTitle(fileTitle: String): Pair<String, String> {
    val parts = fileTitle.split(" - ", limit = 2)
    return if (parts.size == 2) parts[0].trim() to parts[1].trim() else "" to fileTitle
}
