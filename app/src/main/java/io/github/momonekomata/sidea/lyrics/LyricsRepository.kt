package io.github.momonekomata.sidea.lyrics

import android.content.Context
import android.util.Log
import io.github.momonekomata.sidea.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** What is known about the song whose lyrics are wanted. */
data class LyricsQuery(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    /** Lyrics found in the file's own tags. */
    val embedded: String? = null,
    /** Reads a matching .lrc next to the file, if there is one. Blocking; called off the main thread. */
    val sidecar: (() -> String?)? = null,
) {
    val key: String get() = "${artist.lowercase().trim()}|${title.lowercase().trim()}|${durationMs / 2000}"
}

/**
 * Finds lyrics in this order: the file's tags, a .lrc next to it, then LRCLIB (lrclib.net, a
 * free open lyrics database with synced lyrics, no account needed). Online results, including
 * "not found", are cached on the phone so each song is looked up once.
 */
class LyricsRepository(context: Context) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun find(q: LyricsQuery, online: Boolean): Lyrics? = withContext(Dispatchers.IO) {
        q.embedded?.let { Lrc.parse(it, "file tags") }?.let { return@withContext it }
        runCatching { q.sidecar?.invoke() }.getOrNull()?.let { Lrc.parse(it, ".lrc file") }?.let { return@withContext it }
        if (!online || q.title.isBlank()) return@withContext null
        val cache = File(dir, sha1(q.key) + ".lrc")
        val miss = File(dir, sha1(q.key) + ".none")
        if (cache.exists()) return@withContext Lrc.parse(cache.readText(), "LRCLIB")
        if (miss.exists() && System.currentTimeMillis() - miss.lastModified() < MISS_TTL_MS) return@withContext null
        val text = try {
            lookup(q)
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB lookup failed", e)
            return@withContext null // network trouble isn't a "not found"; try again next time
        }
        if (text == null) {
            miss.writeText("")
            null
        } else {
            cache.writeText(text)
            miss.delete()
            Lrc.parse(text, "LRCLIB")
        }
    }

    /** Exact match first; if that misses, a search with the closest duration. */
    private fun lookup(q: LyricsQuery): String? {
        val seconds = q.durationMs / 1000
        val get = BASE.toHttpUrl().newBuilder().addPathSegments("api/get")
            .addQueryParameter("track_name", q.title)
            .addQueryParameter("artist_name", q.artist)
            .apply {
                if (q.album.isNotBlank()) addQueryParameter("album_name", q.album)
                if (seconds > 0) addQueryParameter("duration", seconds.toString())
            }
            .build()
        request(get.toString())?.let { body -> pick(JSONObject(body))?.let { return it } }

        val search = BASE.toHttpUrl().newBuilder().addPathSegments("api/search")
            .addQueryParameter("track_name", q.title)
            .apply { if (q.artist.isNotBlank()) addQueryParameter("artist_name", q.artist) }
            .build()
        val results = request(search.toString())?.let { JSONArray(it) } ?: return null
        val candidates = (0 until results.length()).map { results.getJSONObject(it) }
            .filter { seconds == 0L || abs(it.optDouble("duration", 0.0) - seconds) <= 3 }
            .sortedByDescending { !it.isNull("syncedLyrics") }
        return candidates.firstNotNullOfOrNull { pick(it) }
    }

    private fun pick(o: JSONObject): String? {
        if (o.optBoolean("instrumental")) return "[00:00.00]♪ Instrumental ♪"
        return o.optStringOrNull("syncedLyrics") ?: o.optStringOrNull("plainLyrics")
    }

    private fun JSONObject.optStringOrNull(k: String) = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }

    /** Body on 200, null on 404, throws otherwise. */
    private fun request(url: String): String? {
        val req = Request.Builder().url(url)
            // LRCLIB asks clients to identify themselves.
            .header("User-Agent", "SideA/${BuildConfig.VERSION_NAME} (https://github.com/momonekomata/side-a-player)")
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) throw java.io.IOException("LRCLIB HTTP ${resp.code}")
            return resp.body!!.string()
        }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "SideA.Lyrics"
        private const val BASE = "https://lrclib.net/"
        private const val MISS_TTL_MS = 7L * 24 * 3600 * 1000
    }
}
