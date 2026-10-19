package io.github.momonekomata.sidea.sources

import android.content.Context
import android.net.Uri
import io.github.momonekomata.sidea.audio.PlayableSource
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** A favourite song: enough to show it, and to find it again wherever it lives. */
data class Favorite(val id: String, val title: String, val artist: String, val album: String, val sizeBytes: Long)

/** Favourites, newest first, kept on the phone. */
class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("favorites", 0)

    fun all(): List<Favorite> = JSONArray(prefs.getString("list", "[]")).favorites()

    fun save(list: List<Favorite>) {
        prefs.edit().putString("list", list.toJson().toString()).apply()
    }
}

/** A playlist of songs from Local and the NAS, in the order they were added. */
data class Playlist(val id: String, val name: String, val songs: List<Favorite>)

class PlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("playlists", 0)

    fun all(): List<Playlist> {
        val arr = JSONArray(prefs.getString("list", "[]"))
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Playlist(o.getString("id"), o.optString("name"), o.optJSONArray("songs")?.favorites().orEmpty())
        }
    }

    fun save(list: List<Playlist>) {
        val arr = JSONArray(list.map { JSONObject().put("id", it.id).put("name", it.name).put("songs", it.songs.toJson()) })
        prefs.edit().putString("list", arr.toString()).apply()
    }
}

private fun JSONArray.favorites(): List<Favorite> = (0 until length()).map {
    val o = getJSONObject(it)
    Favorite(o.getString("id"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optLong("size", -1))
}

private fun List<Favorite>.toJson() = JSONArray(map {
    JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("album", it.album).put("size", it.sizeBytes)
})

/**
 * Turns a favourite back into a playable track: a local file (the picked folder's access
 * persists), a WebDAV URL (found on the saved server it belongs to), or an FTPS path.
 * Returns null when its server was removed.
 */
object TrackResolver {
    fun resolve(context: Context, f: Favorite, servers: List<NasServer>): Track? {
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
