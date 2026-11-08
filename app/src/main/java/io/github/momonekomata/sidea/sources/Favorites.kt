package io.github.momonekomata.sidea.sources

import android.content.Context
import android.net.Uri
import io.github.momonekomata.sidea.audio.PlayableSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A favourite song: enough to show it, and to find it again wherever it lives. */
data class Favorite(val id: String, val title: String, val artist: String, val album: String, val sizeBytes: Long)

/** Favourites, newest first, kept on the phone; shared by the app and the Library's scan. */
object Favorites {
    private var prefs: android.content.SharedPreferences? = null
    private val _all = kotlinx.coroutines.flow.MutableStateFlow<List<Favorite>>(emptyList())
    val all: kotlinx.coroutines.flow.StateFlow<List<Favorite>> = _all

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("favorites", 0).also { prefs = it }
        _all.value = JSONArray(p.getString("list", "[]")).favorites()
    }

    @Synchronized
    fun update(f: (List<Favorite>) -> List<Favorite>) {
        val next = f(_all.value)
        _all.value = next
        prefs?.edit()?.putString("list", next.toJson().toString())?.apply()
    }
}

/**
 * Songs found again under a new id (their folder renamed or moved on the NAS: the file's
 * address is its id): favourites, playlists and the history follow them. [moves] is old id → new song.
 */
fun followMoves(moves: Map<String, Favorite>) {
    if (moves.isEmpty()) return
    fun move(f: Favorite) = moves[f.id]?.let { n -> f.copy(id = n.id) } ?: f
    Favorites.update { list -> list.map(::move) }
    Playlists.update { list -> list.map { p -> p.copy(songs = p.songs.map(::move)) } }
    PlayHistory.follow(moves)
}

/**
 * A playlist of songs from Local and the NAS, in the order they were added. One found in a
 * Library folder (an .m3u beside the music) has [origin] (where the file is) and [stamp] (what
 * the file was when last read), so a rescan updates it rather than add it again.
 */
data class Playlist(val id: String, val name: String, val songs: List<Favorite>, val origin: String? = null, val stamp: String? = null)

/** The playlists, kept on the phone; shared by the app and the Library's scan (which finds playlist files). */
object Playlists {
    private var prefs: android.content.SharedPreferences? = null
    private val _all = kotlinx.coroutines.flow.MutableStateFlow<List<Playlist>>(emptyList())
    val all: kotlinx.coroutines.flow.StateFlow<List<Playlist>> = _all

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("playlists", 0).also { prefs = it }
        val arr = JSONArray(p.getString("list", "[]"))
        _all.value = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Playlist(
                o.getString("id"), o.optString("name"), o.optJSONArray("songs")?.favorites().orEmpty(),
                o.optString("origin").ifEmpty { null }, o.optString("stamp").ifEmpty { null },
            )
        }
    }

    /** Changes the playlists as [f] says, and keeps the result. */
    @Synchronized
    fun update(f: (List<Playlist>) -> List<Playlist>): List<Playlist> {
        val next = f(_all.value)
        _all.value = next
        val arr = JSONArray(next.map {
            JSONObject().put("id", it.id).put("name", it.name).put("songs", it.songs.toJson())
                .apply { it.origin?.let { o -> put("origin", o) }; it.stamp?.let { s -> put("stamp", s) } }
        })
        prefs?.edit()?.putString("list", arr.toString())?.apply()
        return next
    }

    /** Playlist files whose playlist was deleted: a rescan doesn't bring them back. */
    fun dismissed(): Set<String> = prefs?.getStringSet("dismissed", emptySet()).orEmpty()

    fun dismiss(origin: String) {
        prefs?.edit()?.putStringSet("dismissed", dismissed() + origin)?.apply()
    }
}

internal fun JSONArray.favorites(): List<Favorite> = (0 until length()).map {
    val o = getJSONObject(it)
    Favorite(o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optLong("size", -1))
}

internal fun List<Favorite>.toJson() = JSONArray(map {
    JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("album", it.album).put("size", it.sizeBytes)
})

/**
 * Turns a favourite back into a playable track: a local file (the picked folder's access
 * persists), a WebDAV URL (found on the saved server it belongs to), or an FTPS path.
 * Returns null when its server was removed.
 */
object TrackResolver {
    fun resolve(context: Context, f: Favorite, servers: List<NasServer>): Track? {
        RemoteIds.parse(f.id)?.let { (serverId, folder, _, name) ->
            val server = servers.firstOrNull { it.id == serverId } ?: return null
            val keep: (NasServer) -> Unit = { s -> NasStore(context).save(s); RemoteStores.update(s) }
            val t = RemoteBrowser(context, server, RemoteStores.get(context, server, keep)).songFromIndex(f.id, name, f.sizeBytes, folder, f.album) ?: return null
            return t.copy(title = f.title.ifBlank { t.title }, artist = f.artist, album = f.album.ifBlank { t.album })
        }
        val base = when {
            f.id.startsWith("content://") -> {
                val uri = Uri.parse(f.id)
                Track(
                    id = f.id,
                    title = f.title,
                    sizeBytes = f.sizeBytes,
                    opener = { PlayableSource.Content(uri) },
                    tagSource = { ctx -> PlayableSource.Content(uri).openRandomAccess(ctx) },
                )
            }
            f.id.startsWith("ftps://") -> {
                val rest = f.id.removePrefix("ftps://")
                val server = servers.firstOrNull { rest.startsWith(it.id) && it.kind == NasKind.FTPS } ?: return null
                FtpsBrowser(context, server).trackFor(rest.removePrefix(server.id), f.sizeBytes)
            }
            else -> {
                val url = f.id.toHttpUrlOrNull() ?: return null
                val dav = servers.filter { it.kind == NasKind.WEBDAV }
                    .map { runCatching { WebDavBrowser(it) }.getOrNull() }
                    .firstOrNull { it?.owns(url) == true } ?: return null
                val name = java.net.URLDecoder.decode(url.encodedPath.substringAfterLast('/'), "UTF-8")
                dav.trackFor(url, name, f.sizeBytes, f.album)
            }
        }
        // Show the saved names until the tags are read again.
        return base.copy(title = f.title.ifBlank { base.title }, artist = f.artist, album = f.album.ifBlank { base.album })
    }
}

/** A song that played, and when. */
data class Played(val song: Favorite, val atMs: Long)

/** The last few hundred songs played, newest first, kept on the phone. */
object PlayHistory {
    private const val LIMIT = 300
    private var prefs: android.content.SharedPreferences? = null
    private val _items = kotlinx.coroutines.flow.MutableStateFlow<List<Played>>(emptyList())
    val items: kotlinx.coroutines.flow.StateFlow<List<Played>> = _items

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.getSharedPreferences("history", 0).also { prefs = it }
        val arr = JSONArray(p.getString("list", "[]"))
        val songs = arr.favorites()
        _items.value = songs.mapIndexed { i, f -> Played(f, arr.getJSONObject(i).optLong("at")) }
    }

    fun record(t: Track) {
        val tags = TagStore.tags.value
        val f = Favorite(t.id, t.displayTitle(tags), t.displayArtist(tags).orEmpty(), t.displayAlbum(tags).orEmpty(), t.sizeBytes)
        val now = System.currentTimeMillis()
        // Playing the same song again straight away (repeat one, a restart) is one entry.
        val rest = _items.value.let { if (it.firstOrNull()?.song?.id == t.id) it.drop(1) else it }
        val next = (listOf(Played(f, now)) + rest).take(LIMIT)
        _items.value = next
        save(next)
    }

    fun clear() {
        _items.value = emptyList()
        save(emptyList())
    }

    /** Songs found again under a new id after a rescan (see [followMoves]). */
    fun follow(moves: Map<String, Favorite>) {
        if (prefs == null || _items.value.none { it.song.id in moves }) return
        val next = _items.value.map { p -> moves[p.song.id]?.let { n -> p.copy(song = p.song.copy(id = n.id)) } ?: p }
        _items.value = next
        save(next)
    }

    private fun save(list: List<Played>) {
        val arr = list.map { it.song }.toJson()
        list.forEachIndexed { i, p -> arr.getJSONObject(i).put("at", p.atMs) }
        prefs?.edit()?.putString("list", arr.toString())?.apply()
    }
}

/** The favourites' JSON form, for other places that keep songs (the saved queue). */
object FavoritesJson {
    fun List<Favorite>.json(): JSONArray = toJson()
    fun JSONArray.list(): List<Favorite> = favorites()
}
