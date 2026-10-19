package io.github.momonekomata.sidea

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class Prefs(
    val gapless: Boolean = true,
    /** Send DSD as DoP when a bit-perfect USB path exists; otherwise DSD is converted to PCM. */
    val dop: Boolean = false,
    /** Look lyrics up on lrclib.net when the file and folder have none. */
    val onlineLyrics: Boolean = true,
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
        )
    }

    fun update(f: (Prefs) -> Prefs) {
        val p = f(_prefs.value)
        _prefs.value = p
        sp.edit()
            .putBoolean("gapless", p.gapless)
            .putBoolean("dop", p.dop)
            .putBoolean("onlineLyrics", p.onlineLyrics)
            .apply()
    }
}
