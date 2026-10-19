package io.github.momonekomata.sidea

import android.content.Context
import io.github.momonekomata.sidea.sources.Browser
import io.github.momonekomata.sidea.sources.Entry
import io.github.momonekomata.sidea.sources.Tags
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Every song and folder under one folder, as of [atMs]; [folders] is how many were read. */
class SearchIndex(val items: List<Found>, val atMs: Long, val folders: Int)

/**
 * Search indexes kept on the phone, one per folder searched from, so a NAS share is read once,
 * not on every search. Folders are stored once each (name, path, parent) and items point at
 * theirs; songs keep the title, artist and album from their tags, so they're found by those
 * straight away next time.
 */
class SearchIndexStore(context: Context) {
    private val dir = File(context.filesDir, "search").apply { mkdirs() }

    private fun file(key: String) =
        File(dir, MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")

    fun save(key: String, index: SearchIndex, tags: Map<String, Tags>) {
        val dirs = JSONArray()
        val ids = HashMap<List<Pair<String, String>>, Int>()
        fun dirOf(crumbs: List<Pair<String, String>>): Int = ids.getOrPut(crumbs) {
            val parent = if (crumbs.size > 1) dirOf(crumbs.dropLast(1)) else -1
            dirs.put(JSONArray().put(crumbs.last().first).put(crumbs.last().second).put(parent))
            dirs.length() - 1
        }
        val items = JSONArray()
        for (f in index.items) {
            val o = JSONObject().put("d", dirOf(f.crumbs))
            when (val e = f.entry) {
                is Entry.Folder -> o.put("k", "f")
                is Entry.Song -> {
                    val t = tags[e.track.id]
                    o.put("k", "s").put("n", e.name).put("id", e.track.id).put("s", e.track.sizeBytes)
                    (t?.title ?: e.track.title.takeIf { it != e.name }).let { if (!it.isNullOrBlank()) o.put("t", it) }
                    (t?.artist ?: e.track.artist).let { if (it.isNotBlank()) o.put("a", it) }
                    (t?.album ?: e.track.album).let { if (it.isNotBlank()) o.put("al", it) }
                }
            }
            items.put(o)
        }
        val json = JSONObject().put("at", index.atMs).put("folders", index.folders).put("dirs", dirs).put("items", items)
        val tmp = File(file(key).path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(file(key))
    }

    /** The saved index for [key], its songs rebuilt through [browser]; null if there's none. */
    fun load(key: String, browser: Browser): SearchIndex? {
        val f = file(key)
        if (!f.exists()) return null
        val json = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return null
        val dirs = json.getJSONArray("dirs")
        val crumbsOf = HashMap<Int, List<Pair<String, String>>>()
        fun crumbs(i: Int): List<Pair<String, String>> = crumbsOf.getOrPut(i) {
            val d = dirs.getJSONArray(i)
            val parent = d.getInt(2)
            (if (parent >= 0) crumbs(parent) else emptyList()) + (d.getString(0) to d.getString(1))
        }
        val items = json.getJSONArray("items")
        val out = ArrayList<Found>(items.length())
        for (k in 0 until items.length()) {
            val o = items.getJSONObject(k)
            val c = crumbs(o.getInt("d"))
            if (o.getString("k") == "f") {
                out += Found(Entry.Folder(c.last().first, c.last().second), c, c.last().first.lowercase())
                continue
            }
            val name = o.getString("n")
            val folder = c.last()
            val track = browser.songFromIndex(o.getString("id"), name, o.optLong("s", -1), folder.second, folder.first) ?: continue
            val title = o.optString("t")
            val artist = o.optString("a")
            val album = o.optString("al")
            val t = track.copy(
                title = title.ifBlank { track.title },
                artist = artist.ifBlank { track.artist },
                album = album.ifBlank { track.album },
            )
            out += Found(Entry.Song(name, t), c, listOf(name, folder.first, title, artist, album).joinToString(" ").lowercase())
        }
        return SearchIndex(out, json.optLong("at"), json.optInt("folders"))
    }
}
