package io.github.momonekomata.sidea

import android.content.Context
import android.content.SharedPreferences
import io.github.momonekomata.sidea.audio.EqBand
import io.github.momonekomata.sidea.audio.EqConfig
import io.github.momonekomata.sidea.audio.FilterType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Prefs(
    val gapless: Boolean = true,
    /** Send DSD as DoP when a bit-perfect USB path exists; otherwise DSD is converted to PCM. */
    val dop: Boolean = false,
    /** Look lyrics up online when the file and folder have none; the sources follow. */
    val onlineLyrics: Boolean = true,
    val lrclib: Boolean = true,
    val lyricsOvh: Boolean = true,
    /** Lyrics found online are saved as a .lrc beside the song. */
    val saveLyrics: Boolean = true,
    /** Japanese lyrics: readings over the kanji, and a romaji line under each line. */
    val furigana: Boolean = true,
    val romaji: Boolean = false,
    /** The user's own key from developer.musixmatch.com; blank leaves Musixmatch out. */
    val musixmatchKey: String = "",
    val eq: EqConfig = EqConfig(),
    /** Drive USB DACs with Side A's own driver when Android can't go bit-perfect. */
    val exclusiveUsb: Boolean = true,
    /** Volume while the USB driver drives the DAC (0..1). 1 is bit-perfect; the volume keys turn it down. */
    val usbVolume: Float = 1f,
    /** How the app looks; the user picks it in Settings. */
    val theme: AppTheme = AppTheme.WALKMAN,
    /** What plays when the queue's last song ends (repeat off). Shuffle orders the songs inside each folder. */
    val folderEnd: FolderEnd = FolderEnd.STOP,
)

/** Settings → When a folder ends. */
enum class FolderEnd(val label: String, val note: String) {
    STOP("Stop", "Playing stops after the folder's last song."),
    NEXT("Next folder", "The folder after it in the Library plays on, in folder order. With shuffle on, each folder's songs play shuffled."),
    RANDOM("Random folder", "A folder picked at random plays on. With shuffle on, its songs play shuffled."),
}

/** The looks to choose from in Settings → Theme. */
enum class AppTheme(val label: String, val note: String) {
    WALKMAN("Walkman", "The tape as it is, in its window on the blue body."),
    GLASS("Glass deck", "The tape behind the smoked glass of a cassette deck's door, in a brushed-metal frame."),
    STEREO("Stereo", "The tape page is the front of a blue stereo cassette player: WALKDECK on the lid, the tape turning in its window, the keys down the silver side and play on the orange button on top."),
}

object Settings {
    private lateinit var sp: SharedPreferences
    private val _prefs = MutableStateFlow(Prefs())
    val prefs: StateFlow<Prefs> = _prefs.asStateFlow()
    val current: Prefs get() = _prefs.value

    fun init(context: Context) {
        if (::sp.isInitialized) return
        sp = context.applicationContext.getSharedPreferences("settings", 0)
        _prefs.value = Prefs(
            gapless = sp.getBoolean("gapless", true),
            dop = sp.getBoolean("dop", false),
            onlineLyrics = sp.getBoolean("onlineLyrics", true),
            lrclib = sp.getBoolean("lrclib", true),
            lyricsOvh = sp.getBoolean("lyricsOvh", true),
            saveLyrics = sp.getBoolean("saveLyrics", true),
            furigana = sp.getBoolean("furigana", true),
            romaji = sp.getBoolean("romaji", false),
            musixmatchKey = sp.getString("musixmatchKey", "").orEmpty(),
            exclusiveUsb = sp.getBoolean("exclusiveUsb", true),
            // A new key, so everyone starts at 100 % (bit-perfect) once, whatever the old 30 % saved.
            usbVolume = sp.getFloat("usbVolume100", 1f),
            theme = runCatching { AppTheme.valueOf(sp.getString("theme", null) ?: "") }.getOrDefault(AppTheme.WALKMAN),
            // From 0.4.64's two switches: next folder (without shuffle) wins, then the random one.
            folderEnd = runCatching { FolderEnd.valueOf(sp.getString("folderEnd", null) ?: "") }.getOrElse {
                when {
                    sp.getBoolean("nextFolder", false) -> FolderEnd.NEXT
                    sp.getBoolean("shuffleNextFolder", false) -> FolderEnd.RANDOM
                    else -> FolderEnd.STOP
                }
            },
            eq = runCatching { sp.getString("eq", null)?.let { eqFromJson(JSONObject(it)) } }.getOrNull() ?: EqConfig(),
        )
    }

    fun update(f: (Prefs) -> Prefs) {
        val p = f(_prefs.value)
        _prefs.value = p
        sp.edit()
            .putBoolean("gapless", p.gapless)
            .putBoolean("dop", p.dop)
            .putBoolean("onlineLyrics", p.onlineLyrics)
            .putBoolean("lrclib", p.lrclib)
            .putBoolean("lyricsOvh", p.lyricsOvh)
            .putBoolean("saveLyrics", p.saveLyrics)
            .putBoolean("furigana", p.furigana)
            .putBoolean("romaji", p.romaji)
            .putString("musixmatchKey", p.musixmatchKey)
            .putBoolean("exclusiveUsb", p.exclusiveUsb)
            .putFloat("usbVolume100", p.usbVolume)
            .putString("theme", p.theme.name)
            .putString("folderEnd", p.folderEnd.name)
            .putString("eq", eqToJson(p.eq).toString())
            .apply()
    }

    private fun eqToJson(c: EqConfig) = JSONObject()
        .put("enabled", c.enabled).put("preampDb", c.preampDb).put("autoPreamp", c.autoPreamp)
        .put("bands", JSONArray(c.bands.map {
            JSONObject().put("type", it.type.name).put("freq", it.freqHz).put("gain", it.gainDb).put("q", it.q).put("on", it.enabled)
        }))

    private fun eqFromJson(o: JSONObject): EqConfig {
        val arr = o.optJSONArray("bands") ?: JSONArray()
        return EqConfig(
            enabled = o.optBoolean("enabled"),
            preampDb = o.optDouble("preampDb", 0.0),
            autoPreamp = o.optBoolean("autoPreamp", true),
            bands = (0 until arr.length()).map { i ->
                val b = arr.getJSONObject(i)
                EqBand(FilterType.valueOf(b.getString("type")), b.getDouble("freq"), b.getDouble("gain"), b.getDouble("q"), b.optBoolean("on", true))
            },
        )
    }
}
