package io.github.momonekomata.sidea.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class FilterType(val label: String, val short: String, val hasGain: Boolean) {
    PEAK("Peak", "PK", true),
    LOW_SHELF("Low shelf", "LSC", true),
    HIGH_SHELF("High shelf", "HSC", true),
    LOW_PASS("Low pass", "LPQ", false),
    HIGH_PASS("High pass", "HPQ", false),
}

data class EqBand(
    val type: FilterType = FilterType.PEAK,
    val freqHz: Double = 1000.0,
    val gainDb: Double = 0.0,
    val q: Double = 0.71,
    val enabled: Boolean = true,
)

data class EqConfig(
    val enabled: Boolean = false,
    val bands: List<EqBand> = emptyList(),
    val preampDb: Double = 0.0,
    /** Lower the level by the biggest boost so the EQ can't clip. */
    val autoPreamp: Boolean = true,
) {
    val activeBands get() = bands.filter { it.enabled && (!it.type.hasGain || abs(it.gainDb) > 0.01) }

    /** True when the EQ actually changes the signal; a flat or switched-off EQ leaves it bit-perfect. */
    val isActive get() = enabled && (activeBands.isNotEmpty() || abs(effectivePreampDb) > 0.01)

    val effectivePreampDb: Double
        get() = if (autoPreamp) -max(0.0, maxBoostDb()) else preampDb

    /** Highest point of the combined response, sampled across the audio band. */
    fun maxBoostDb(sampleRate: Int = 48_000): Double {
        val bs = activeBands.map { Biquad.design(it, sampleRate) }
        if (bs.isEmpty()) return 0.0
        return (0..240).maxOf { i ->
            val f = 20.0 * 10.0.pow(i / 80.0) // 20 Hz … 20 kHz
            bs.sumOf { it.responseDb(f, sampleRate) }
        }
    }
}

/** One RBJ "Audio EQ Cookbook" biquad, normalised so a0 = 1. */
class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
    fun responseDb(freqHz: Double, sampleRate: Int): Double {
        val w = 2 * PI * freqHz / sampleRate
        val c1 = cos(w); val s1 = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2; val ni = -(b1 * s1 + b2 * s2)
        val dr = 1 + a1 * c1 + a2 * c2; val di = -(a1 * s1 + a2 * s2)
        val mag2 = (nr * nr + ni * ni) / (dr * dr + di * di)
        return 10 * log10(mag2)
    }

    companion object {
        fun design(band: EqBand, sampleRate: Int): Biquad {
            // Keep the filter below Nyquist even for a 44.1 kHz file and a 20 kHz band.
            val f = band.freqHz.coerceIn(10.0, sampleRate * 0.49)
            val w0 = 2 * PI * f / sampleRate
            val cw = cos(w0)
            val alpha = sin(w0) / (2 * band.q.coerceIn(0.1, 30.0))
            val a = 10.0.pow(band.gainDb / 40)
            val (b, aa) = when (band.type) {
                FilterType.PEAK -> doubleArrayOf(1 + alpha * a, -2 * cw, 1 - alpha * a) to doubleArrayOf(1 + alpha / a, -2 * cw, 1 - alpha / a)
                FilterType.LOW_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    doubleArrayOf(a * ((a + 1) - (a - 1) * cw + k), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - k)) to
                        doubleArrayOf((a + 1) + (a - 1) * cw + k, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - k)
                }
                FilterType.HIGH_SHELF -> {
                    val k = 2 * sqrt(a) * alpha
                    doubleArrayOf(a * ((a + 1) + (a - 1) * cw + k), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - k)) to
                        doubleArrayOf((a + 1) - (a - 1) * cw + k, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - k)
                }
                FilterType.LOW_PASS -> doubleArrayOf((1 - cw) / 2, 1 - cw, (1 - cw) / 2) to doubleArrayOf(1 + alpha, -2 * cw, 1 - alpha)
                FilterType.HIGH_PASS -> doubleArrayOf((1 + cw) / 2, -(1 + cw), (1 + cw) / 2) to doubleArrayOf(1 + alpha, -2 * cw, 1 - alpha)
            }
            val a0 = aa[0]
            return Biquad(b[0] / a0, b[1] / a0, b[2] / a0, aa[1] / a0, aa[2] / a0)
        }
    }
}

/**
 * Runs the EQ on interleaved float PCM at the file's own sample rate, in double precision
 * (direct form I) so low bands at high rates stay stable and quiet. Changing the bands while
 * playing keeps the filter state, so adjustments don't click.
 */
class EqProcessor(private val sampleRate: Int, private val channels: Int) {
    private var config: EqConfig? = null
    private var filters: Array<Biquad> = emptyArray()
    private var gain = 1.0
    // Per channel, per filter: x1, x2, y1, y2.
    private var state = Array(channels) { DoubleArray(0) }

    fun configure(c: EqConfig) {
        if (c == config) return
        config = c
        val newFilters = c.activeBands.map { Biquad.design(it, sampleRate) }.toTypedArray()
        if (newFilters.size != filters.size) state = Array(channels) { DoubleArray(newFilters.size * 4) }
        filters = newFilters
        gain = 10.0.pow(c.effectivePreampDb / 20)
    }

    fun reset() = state.forEach { it.fill(0.0) }

    /** Processes [frames] frames of [pcm] in place. */
    fun process(pcm: FloatArray, frames: Int) {
        val fs = filters
        for (ch in 0 until channels) {
            val st = state[ch]
            var i = ch
            repeat(frames) {
                var x = pcm[i] * gain
                for (k in fs.indices) {
                    val f = fs[k]
                    val o = k * 4
                    val y = f.b0 * x + f.b1 * st[o] + f.b2 * st[o + 1] - f.a1 * st[o + 2] - f.a2 * st[o + 3]
                    st[o + 1] = st[o]; st[o] = x
                    st[o + 3] = st[o + 2]; st[o + 2] = y
                    x = y
                }
                pcm[i] = x.toFloat()
                i += channels
            }
        }
    }
}

object EqPresets {
    val all: List<Pair<String, List<EqBand>>> = listOf(
        "Flat" to emptyList(),
        "Bass boost" to listOf(EqBand(FilterType.LOW_SHELF, 105.0, 5.0, 0.71)),
        "Warm" to listOf(EqBand(FilterType.LOW_SHELF, 200.0, 2.5, 0.71), EqBand(FilterType.HIGH_SHELF, 8000.0, -2.0, 0.71)),
        "Vocal" to listOf(EqBand(FilterType.PEAK, 250.0, -2.0, 1.0), EqBand(FilterType.PEAK, 2500.0, 2.5, 1.0)),
        "Treble lift" to listOf(EqBand(FilterType.HIGH_SHELF, 6000.0, 3.0, 0.71)),
        "Five-band" to listOf(
            EqBand(FilterType.LOW_SHELF, 80.0, 0.0, 0.71),
            EqBand(FilterType.PEAK, 300.0, 0.0, 1.0),
            EqBand(FilterType.PEAK, 1200.0, 0.0, 1.0),
            EqBand(FilterType.PEAK, 4000.0, 0.0, 1.0),
            EqBand(FilterType.HIGH_SHELF, 10000.0, 0.0, 0.71),
        ),
    )
}

/**
 * Reads the parametric EQ text AutoEQ and Equalizer APO use, e.g.
 * `Preamp: -6.2 dB` / `Filter 1: ON PK Fc 105 Hz Gain -2.5 dB Q 0.70`. Paste a headphone's
 * correction straight from autoeq.app.
 */
object AutoEqParser {
    private val preamp = Regex("""preamp:\s*([+-]?\d+(?:\.\d+)?)\s*db""", RegexOption.IGNORE_CASE)
    private val filter = Regex(
        """filter\s*\d*:\s*(on|off)\s+([a-z]+)\s+fc\s+([\d.]+)\s*hz(?:\s+gain\s+([+-]?[\d.]+)\s*db)?(?:\s+q\s+([\d.]+))?""",
        RegexOption.IGNORE_CASE,
    )

    fun parse(text: String): EqConfig? {
        val bands = text.lines().mapNotNull { line ->
            val m = filter.find(line) ?: return@mapNotNull null
            val type = when (m.groupValues[2].uppercase()) {
                "PK", "PEQ" -> FilterType.PEAK
                "LS", "LSC", "LSQ" -> FilterType.LOW_SHELF
                "HS", "HSC", "HSQ" -> FilterType.HIGH_SHELF
                "LP", "LPQ" -> FilterType.LOW_PASS
                "HP", "HPQ" -> FilterType.HIGH_PASS
                else -> return@mapNotNull null
            }
            EqBand(
                type = type,
                freqHz = m.groupValues[3].toDouble(),
                gainDb = m.groupValues[4].toDoubleOrNull() ?: 0.0,
                q = m.groupValues[5].toDoubleOrNull() ?: 0.71,
                enabled = m.groupValues[1].equals("on", ignoreCase = true),
            )
        }
        if (bands.isEmpty()) return null
        val pre = preamp.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        return EqConfig(enabled = true, bands = bands, preampDb = pre ?: 0.0, autoPreamp = pre == null)
    }
}
