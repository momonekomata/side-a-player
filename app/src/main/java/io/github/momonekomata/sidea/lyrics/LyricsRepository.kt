package io.github.momonekomata.sidea.lyrics

import android.content.Context
import android.util.Log
import io.github.momonekomata.sidea.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
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

/** Which online sources to ask, in this order. */
data class LyricsSources(
    val lrclib: Boolean = true,
    /** The user's own key from developer.musixmatch.com; blank means skip Musixmatch. */
    val musixmatchKey: String = "",
    val lyricsOvh: Boolean = true,
) {
    val signature get() = "l=$lrclib,m=${musixmatchKey.isNotBlank()},o=$lyricsOvh"
}

/** One online lyrics service. Returns LRC or plain text, null for "not found"; throws on trouble. */
private interface LyricsProvider {
    val name: String
    fun find(q: LyricsQuery): String?
}

/**
 * Finds lyrics in this order: the file's tags, a .lrc next to it, then the online sources that
 * are switched on: LRCLIB (open, timed lyrics), Musixmatch (official API with your key), and
 * lyrics.ovh (open, plain lyrics). Online results, including "not found", are cached on the phone.
 */
class LyricsRepository(context: Context) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /** [force] ignores a remembered "not found", for the Retry button. */
    suspend fun find(q: LyricsQuery, online: Boolean, sources: LyricsSources = LyricsSources(), force: Boolean = false): Lyrics? = withContext(Dispatchers.IO) {
        q.embedded?.let { Lrc.parse(it, "file tags") }?.let { return@withContext it }
        runCatching { q.sidecar?.invoke() }.getOrNull()?.let { Lrc.parse(it, ".lrc file") }?.let { return@withContext it }
        if (!online || q.title.isBlank()) return@withContext null
        val cache = File(dir, sha1(q.key) + ".json")
        val legacy = File(dir, sha1(q.key) + ".lrc")
        // A miss depends on which sources were asked; switching one on asks again.
        val miss = File(dir, sha1(q.key + sources.signature) + ".none")
        readCache(cache, legacy)?.let { return@withContext it }
        if (!force && miss.exists() && System.currentTimeMillis() - miss.lastModified() < MISS_TTL_MS) return@withContext null

        var trouble = false
        for (p in providers(sources)) {
            val text = try {
                p.find(q)
            } catch (e: Exception) {
                Log.w(TAG, "${p.name} lookup failed", e)
                trouble = true
                null
            } ?: continue
            val parsed = Lrc.parse(text, p.name) ?: continue
            cache.writeText(JSONObject().put("source", p.name).put("text", text).toString())
            miss.delete()
            return@withContext parsed
        }
        // Only remember a miss for complete metadata and a clean run; apps often send the
        // artist a moment later, and network trouble isn't "not found".
        if (!trouble && q.artist.isNotBlank() && q.durationMs > 0) miss.writeText("")
        null
    }

    private fun readCache(cache: File, legacy: File): Lyrics? {
        if (cache.exists()) {
            val o = runCatching { JSONObject(cache.readText()) }.getOrNull() ?: return null
            return Lrc.parse(o.optString("text"), o.optString("source", "LRCLIB"))
        }
        if (legacy.exists()) return Lrc.parse(legacy.readText(), "LRCLIB")
        return null
    }

    private fun providers(s: LyricsSources) = buildList {
        if (s.lrclib) add(Lrclib(client))
        if (s.musixmatchKey.isNotBlank()) add(Musixmatch(client, s.musixmatchKey.trim()))
        if (s.lyricsOvh) add(LyricsOvh(client))
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "SideA.Lyrics"
        private const val MISS_TTL_MS = 24L * 3600 * 1000
    }
}

/** Body on 2xx, null on 404, throws otherwise. Identifies the app, as these services ask. */
private fun OkHttpClient.getText(url: HttpUrl): String? {
    val req = Request.Builder().url(url)
        .header("User-Agent", "SideA/${BuildConfig.VERSION_NAME} (https://github.com/momonekomata/side-a-player)")
        .build()
    newCall(req).execute().use { resp ->
        if (resp.code == 404) return null
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from ${url.host}")
        return resp.body!!.string()
    }
}

private fun JSONObject.optStringOrNull(k: String) = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }

/** lrclib.net: open database with timed lyrics, no account. Tries cleaned titles, then search. */
private class Lrclib(private val client: OkHttpClient) : LyricsProvider {
    override val name = "LRCLIB"
    private val base = "https://lrclib.net/".toHttpUrl()

    override fun find(q: LyricsQuery): String? {
        val seconds = q.durationMs / 1000
        for ((i, v) in LyricsMatch.variants(q.title, q.artist).withIndex()) {
            val (title, artist) = v
            if (artist.isBlank()) continue
            val get = base.newBuilder().addPathSegments("api/get")
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist)
                .apply {
                    // The album only helps the exact try; streaming albums carry "(Deluxe)" and such.
                    if (i == 0 && q.album.isNotBlank()) addQueryParameter("album_name", q.album)
                    if (seconds > 0) addQueryParameter("duration", seconds.toString())
                }
                .build()
            client.getText(get)?.let { body -> pick(JSONObject(body))?.let { return it } }
        }
        val title = LyricsMatch.cleanTitle(q.title)
        val artist = LyricsMatch.primaryArtist(q.artist)
        val searches = listOf(
            base.newBuilder().addPathSegments("api/search").addQueryParameter("track_name", title)
                .apply { if (artist.isNotBlank()) addQueryParameter("artist_name", artist) }.build(),
            base.newBuilder().addPathSegments("api/search").addQueryParameter("q", "$artist $title".trim()).build(),
        )
        for (url in searches) {
            val results = client.getText(url)?.let { JSONArray(it) } ?: continue
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
}

/**
 * Musixmatch's official API with the user's own key. Timed lyrics (matcher.subtitle.get) need
 * a paid plan; on the free plan matcher.lyrics.get returns part of the lyrics. Their terms ask
 * apps to load the tracking pixel that comes with each result, so it's fetched.
 */
private class Musixmatch(private val client: OkHttpClient, private val key: String) : LyricsProvider {
    override val name = "Musixmatch"
    private val base = "https://api.musixmatch.com/ws/1.1/".toHttpUrl()

    override fun find(q: LyricsQuery): String? {
        val title = LyricsMatch.cleanTitle(q.title)
        val artist = LyricsMatch.primaryArtist(q.artist)
        val seconds = q.durationMs / 1000
        val sub = call("matcher.subtitle.get") {
            addQueryParameter("q_track", title).addQueryParameter("q_artist", artist)
            if (seconds > 0) addQueryParameter("f_subtitle_length", seconds.toString()).addQueryParameter("f_subtitle_length_max_deviation", "3")
        }
        sub?.optJSONObject("subtitle")?.let { s ->
            track(s)
            s.optStringOrNull("subtitle_body")?.let { return it }
        }
        val lyr = call("matcher.lyrics.get") { addQueryParameter("q_track", title).addQueryParameter("q_artist", artist) }
        val l = lyr?.optJSONObject("lyrics") ?: return null
        track(l)
        if (l.optInt("instrumental") == 1) return "[00:00.00]♪ Instrumental ♪"
        val body = l.optStringOrNull("lyrics_body") ?: return null
        // The free plan appends a notice and a number; keep only the lyrics.
        return body.lines().takeWhile { !it.startsWith("*******") }.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }

    /** The response body, or null when Musixmatch has nothing (or the plan doesn't allow it). */
    private fun call(method: String, params: HttpUrl.Builder.() -> Unit): JSONObject? {
        val url = base.newBuilder().addPathSegment(method).apply(params).addQueryParameter("apikey", key).build()
        val msg = JSONObject(client.getText(url) ?: return null).optJSONObject("message") ?: return null
        when (val status = msg.optJSONObject("header")?.optInt("status_code") ?: 0) {
            200 -> return msg.optJSONObject("body")
            404, 402, 403 -> return null // not found, or not on this plan
            401 -> throw IOException("Musixmatch rejected the API key")
            else -> throw IOException("Musixmatch status $status")
        }
    }

    private fun track(o: JSONObject) {
        val pixel = o.optStringOrNull("pixel_tracking_url") ?: return
        runCatching { pixel.toHttpUrl().let { client.getText(it) } }
    }
}

/** lyrics.ovh: free and open, plain lyrics only. */
private class LyricsOvh(private val client: OkHttpClient) : LyricsProvider {
    override val name = "lyrics.ovh"

    override fun find(q: LyricsQuery): String? {
        val artist = LyricsMatch.primaryArtist(q.artist).takeIf { it.isNotBlank() } ?: return null
        val url = "https://api.lyrics.ovh/".toHttpUrl().newBuilder()
            .addPathSegment("v1").addPathSegment(artist).addPathSegment(LyricsMatch.cleanTitle(q.title))
            .build()
        val o = JSONObject(client.getText(url) ?: return null)
        val text = o.optStringOrNull("lyrics") ?: return null
        // Some entries start with a "Paroles de la chanson … par …" header line.
        return text.lines().dropWhile { it.startsWith("Paroles de la chanson") || it.isBlank() }.joinToString("\n").trim().takeIf { it.isNotEmpty() }
    }
}
