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
 * Finds lyrics: the song's own (its tags, a .lrc next to it; timed ones first) and, unless those
 * are timed, the online sources that are switched on: LRCLIB (open, timed lyrics), Musixmatch
 * (official API with your key), and lyrics.ovh (open, plain lyrics). Its own plain lyrics and
 * timed ones online: see [pick]. Online results, including "not found", are cached on the phone.
 */
class LyricsRepository(context: Context) {
    private val dir = File(context.filesDir, "lyrics").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * [force] ignores a remembered "not found", for the Retry button. [prefer] is the choice
     * made for this song between its own plain lyrics and timed ones found online ("own" or
     * "online"); null decides by itself (see [pick]).
     */
    suspend fun find(q: LyricsQuery, online: Boolean, sources: LyricsSources = LyricsSources(), force: Boolean = false, prefer: String? = null, why: (String) -> Unit = {}): Lyrics? = withContext(Dispatchers.IO) {
        val tags = q.embedded?.let { Lrc.parse(it, "file tags") }
        val file = runCatching { q.sidecar?.invoke() }.getOrNull()?.let { Lrc.parse(it, ".lrc file") }
        // The song's own lyrics (its tags or its .lrc), timed ones first.
        val local = listOfNotNull(tags, file).let { l -> l.firstOrNull { it.synced } ?: l.firstOrNull() }
        if (!online || q.title.isBlank()) return@withContext local
        // Even beside its own timed lyrics, the online ones are looked at (once; they're kept on
        // the phone): a file can carry another song's lyrics, and then there's a switch to put
        // it right.
        val found = lookUp(q, sources, force, wantTimed = local != null, why) ?: return@withContext local
        if (local == null) return@withContext found
        pick(local, found, prefer)
    }

    /**
     * The song's own lyrics, or the timed ones found online for it. The same words: its own when
     * they're timed already, else the online ones (the same lyrics, now in time with the song).
     * Different words (the wrong song online, lyrics you wrote yourself, or a file tagged with
     * another song's): its own, with the online ones to switch to. [prefer] is a choice made with
     * that switch, and overrides all this.
     */
    private fun pick(own: Lyrics, online: Lyrics, prefer: String?): Lyrics {
        val same = LyricsMatch.sameWords(own.lines.map { it.text }, online.lines.map { it.text })
        // Its own timed lyrics, and online the same: nothing to choose between.
        if (same && own.synced && prefer == null) return own
        val useOnline = when (prefer) {
            "online" -> true
            "own" -> false
            else -> same
        }
        return if (useOnline) online.copy(alternative = own) else own.copy(alternative = online)
    }

    /**
     * Lyrics from the online sources (or what they gave before), timed ones only if [wantTimed].
     * When there are none, [why] hears the reason if it's more than "they have none".
     */
    private fun lookUp(q: LyricsQuery, sources: LyricsSources, force: Boolean, wantTimed: Boolean, why: (String) -> Unit): Lyrics? {
        val cache = File(dir, sha1(q.key) + ".json")
        val legacy = File(dir, sha1(q.key) + ".lrc")
        // A miss depends on which sources were asked; switching one on asks again.
        val miss = File(dir, sha1(q.key + sources.signature) + ".none")
        readCache(cache, legacy)?.let { if (!wantTimed || it.synced) return it }
        val providers = providers(sources)
        if (providers.isEmpty()) {
            why("Every online lyrics source is switched off in Settings → Lyrics.")
            return null
        }
        if (!force && miss.exists() && System.currentTimeMillis() - miss.lastModified() < MISS_TTL_MS) {
            why("Nothing was found for it earlier today. Retry asks again.")
            return null
        }

        var trouble = false
        val problems = ArrayList<String>()
        for (p in providers) {
            val text = try {
                p.find(q)
            } catch (e: Exception) {
                Log.w(TAG, "${p.name} lookup failed", e)
                trouble = true
                problems += "${p.name}: ${reason(e)}"
                null
            } ?: continue
            val parsed = Lrc.parse(text, p.name) ?: continue
            // Plain lyrics are no use beside the song's own plain ones: the next source may have timed.
            if (wantTimed && !parsed.synced) continue
            cache.writeText(JSONObject().put("source", p.name).put("text", text).toString())
            miss.delete()
            return parsed
        }
        // Only remember a miss for complete metadata and a clean run; apps often send the
        // artist a moment later, and network trouble isn't "not found".
        if (!trouble && q.artist.isNotBlank() && q.durationMs > 0) miss.writeText("")
        if (problems.isNotEmpty()) why("Couldn't ask " + problems.joinToString("; ") + ".")
        return null
    }

    /** An error as a person would put it: no connection, a name that won't resolve, a timeout. */
    private fun reason(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "the phone can't find its address (no internet, or a Private DNS / ad blocker stops it)"
        is java.net.SocketTimeoutException -> "no answer in time"
        is java.net.ConnectException -> "no connection (${e.message})"
        is javax.net.ssl.SSLException -> "secure connection failed (${e.message})"
        else -> e.message ?: e.javaClass.simpleName
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

    // One request failing (a 400 for an odd title, a dropped connection) mustn't skip the rest;
    // only when every request failed is it trouble rather than "not there".
    private var failure: Exception? = null
    private var answered = false

    private fun ask(url: HttpUrl): String? = try {
        client.getText(url).also { answered = true }
    } catch (e: Exception) {
        if (failure == null) failure = e
        null
    }

    override fun find(q: LyricsQuery): String? {
        failure = null
        answered = false
        lookFor(q)?.let { return it }
        if (!answered) failure?.let { throw it }
        return null
    }

    private fun lookFor(q: LyricsQuery): String? {
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
            ask(get)?.let { body -> pick(JSONObject(body))?.let { return it } }
        }
        val title = LyricsMatch.cleanTitle(q.title)
        val artist = LyricsMatch.primaryArtist(q.artist)
        val searches = listOf(
            base.newBuilder().addPathSegments("api/search").addQueryParameter("track_name", title)
                .apply { if (artist.isNotBlank()) addQueryParameter("artist_name", artist) }.build(),
            base.newBuilder().addPathSegments("api/search").addQueryParameter("q", "$artist $title".trim()).build(),
        )
        for (url in searches) {
            val results = ask(url)?.let { JSONArray(it) } ?: continue
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
