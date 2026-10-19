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

    /** [force] ignores a remembered "not found", for the Retry button. */
    suspend fun find(q: LyricsQuery, online: Boolean, force: Boolean = false): Lyrics? = withContext(Dispatchers.IO) {
        q.embedded?.let { Lrc.parse(it, "file tags") }?.let { return@withContext it }
        runCatching { q.sidecar?.invoke() }.getOrNull()?.let { Lrc.parse(it, ".lrc file") }?.let { return@withContext it }
        if (!online || q.title.isBlank()) return@withContext null
        val cache = File(dir, sha1(q.key) + ".lrc")
        val miss = File(dir, sha1(q.key) + ".none")
        if (cache.exists()) return@withContext Lrc.parse(cache.readText(), "LRCLIB")
        if (!force && miss.exists() && System.currentTimeMillis() - miss.lastModified() < MISS_TTL_MS) return@withContext null
        val text = try {
            lookup(q)
        } catch (e: Exception) {
            Log.w(TAG, "LRCLIB lookup failed", e)
            return@withContext null // network trouble isn't a "not found"; try again next time
        }
        if (text == null) {
            // Only remember a miss for complete metadata; apps often send the artist a moment later.
            if (q.artist.isNotBlank() && q.durationMs > 0) miss.writeText("")
            null
        } else {
            cache.writeText(text)
            miss.delete()
            Lrc.parse(text, "LRCLIB")
        }
    }

    /**
     * Exact match first, then cleaned-up titles and the lead artist, then free-text search;
     * search results must be within 3 s of the song's length when it's known.
     */
    private fun lookup(q: LyricsQuery): String? {
        val seconds = q.durationMs / 1000
        for ((i, v) in LyricsMatch.variants(q.title, q.artist).withIndex()) {
            val (title, artist) = v
            if (artist.isBlank()) continue
            val get = BASE.toHttpUrl().newBuilder().addPathSegments("api/get")
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist)
                .apply {
                    // The album only helps the exact try; streaming albums carry "(Deluxe)" and such.
                    if (i == 0 && q.album.isNotBlank()) addQueryParameter("album_name", q.album)
                    if (seconds > 0) addQueryParameter("duration", seconds.toString())
                }
                .build()
            request(get.toString())?.let { body -> pick(JSONObject(body))?.let { return it } }
        }
        val title = LyricsMatch.cleanTitle(q.title)
        val artist = LyricsMatch.primaryArtist(q.artist)
        val searches = listOf(
            BASE.toHttpUrl().newBuilder().addPathSegments("api/search").addQueryParameter("track_name", title)
                .apply { if (artist.isNotBlank()) addQueryParameter("artist_name", artist) }.build(),
            BASE.toHttpUrl().newBuilder().addPathSegments("api/search").addQueryParameter("q", "$artist $title".trim()).build(),
        )
        for (url in searches) {
            val results = request(url.toString())?.let { JSONArray(it) } ?: continue
            val candidates = (0 until results.length()).map { results.getJSONObject(it) }
                .filter { seconds == 0L || abs(it.optDouble("duration", 0.0) - seconds) <= 3 }
                .sortedByDescending { !it.isNull("syncedLyrics") }
            candidates.firstNotNullOfOrNull { pick(it) }?.let { return it }
        }
        return null
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
        private const val MISS_TTL_MS = 24L * 3600 * 1000
    }
}
