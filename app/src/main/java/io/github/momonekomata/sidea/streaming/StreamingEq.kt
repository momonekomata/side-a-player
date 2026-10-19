package io.github.momonekomata.sidea.streaming

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.DynamicsProcessing
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.momonekomata.sidea.audio.Biquad
import io.github.momonekomata.sidea.audio.EqConfig
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Side A's EQ on other apps' audio. Music apps that support system equalisers announce their
 * playback with OPEN_AUDIO_EFFECT_CONTROL_SESSION (Spotify and YouTube Music do; some apps
 * don't); Android then lets another app attach effects to that session. Side A attaches a
 * DynamicsProcessing pre-EQ whose 32 bands follow the parametric curve, plus the preamp.
 * It sits in the app's own audio, before Android's mixer, so it's never bit-perfect.
 */
object StreamingEq {
    private const val TAG = "SideA.StreamingEq"
    private const val BANDS = 32

    private class Attached(val packageName: String, val effect: DynamicsProcessing)

    private val sessions = mutableMapOf<Int, Attached>()
    private var config = EqConfig()
    private var started = false

    /** Packages whose audio carries Side A's EQ right now, for the signal path. */
    val activePackages: Set<String> get() = synchronized(this) { sessions.values.map { it.packageName }.toSet() }

    fun start(context: Context) {
        if (started) return
        started = true
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        ContextCompat.registerReceiver(context.applicationContext, object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
                if (session <= 0) return
                val pkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME).orEmpty()
                when (intent.action) {
                    AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> open(session, pkg)
                    AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> close(session)
                }
            }
        }, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    @Synchronized
    fun setConfig(c: EqConfig) {
        config = c
        sessions.values.forEach { apply(it.effect) }
    }

    @Synchronized
    private fun open(session: Int, pkg: String) {
        if (session in sessions) return
        try {
            val cfg = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION, 2,
                true, BANDS, false, 0, false, 0, true,
            ).build()
            val dp = DynamicsProcessing(0, session, cfg)
            apply(dp)
            sessions[session] = Attached(pkg, dp)
        } catch (e: Exception) {
            Log.w(TAG, "can't attach to session $session of $pkg", e)
        }
    }

    @Synchronized
    private fun close(session: Int) {
        sessions.remove(session)?.effect?.release()
    }

    /** Samples the parametric response at each band's centre; bands are log-spaced 20 Hz – 20 kHz. */
    private fun apply(dp: DynamicsProcessing) {
        val c = config
        dp.setEnabled(c.isActive)
        if (!c.isActive) return
        val rate = 48_000
        val filters = c.activeBands.map { Biquad.design(it, rate) }
        val eq = DynamicsProcessing.Eq(true, true, BANDS)
        for (i in 0 until BANDS) {
            val lo = 20.0 * 1000.0.pow(i.toDouble() / BANDS)
            val hi = 20.0 * 1000.0.pow((i + 1).toDouble() / BANDS)
            val centre = sqrt(lo * hi)
            val gain = filters.sumOf { it.responseDb(centre, rate) }
            eq.setBand(i, DynamicsProcessing.EqBand(true, hi.toFloat(), gain.toFloat()))
        }
        dp.setPreEqAllChannelsTo(eq)
        dp.setInputGainAllChannelsTo(c.effectivePreampDb.toFloat())
    }
}
