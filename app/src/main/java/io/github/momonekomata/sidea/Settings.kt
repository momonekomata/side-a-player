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
    /** The user's own key from developer.musixmatch.com; blank leaves Musixmatch out. */
    val musixmatchKey: String = "",
    val eq: EqConfig = EqConfig(),
    /** Drive USB DACs with Side A's own driver when Android can't go bit-perfect. */
    val exclusiveUsb: Boolean = true,
    /** Volume while the USB driver drives the DAC (0..1). 1 is bit-perfect; the volume keys turn it down. */
    val usbVolume: Float = 1f,
)

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
            musixmatchKey = sp.getString("musixmatchKey", "").orEmpty(),
            exclusiveUsb = sp.getBoolean("exclusiveUsb", true),
            // A new key, so everyone starts at 100 % (bit-perfect) once, whatever the old 30 % saved.
            usbVolume = sp.getFloat("usbVolume100", 1f),
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
            .putString("musixmatchKey", p.musixmatchKey)
            .putBoolean("exclusiveUsb", p.exclusiveUsb)
            .putFloat("usbVolume100", p.usbVolume)
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
