package io.github.momonekomata.sidea.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EqTest {
    @Test
    fun `peak filter hits its gain at the centre and stays flat far away`() {
        for (rate in listOf(44_100, 96_000, 192_000)) {
            val b = Biquad.design(EqBand(FilterType.PEAK, 1000.0, 6.0, 1.0), rate)
            assertEquals(6.0, b.responseDb(1000.0, rate), 0.01)
            assertEquals(0.0, b.responseDb(20.0, rate), 0.1)
        }
    }

    @Test
    fun `shelves reach their gain on the shelf side`() {
        val low = Biquad.design(EqBand(FilterType.LOW_SHELF, 100.0, -4.0, 0.71), 48_000)
        assertEquals(-4.0, low.responseDb(20.0, 48_000), 0.2)
        assertEquals(0.0, low.responseDb(10_000.0, 48_000), 0.1)
        val high = Biquad.design(EqBand(FilterType.HIGH_SHELF, 8000.0, 3.0, 0.71), 48_000)
        assertEquals(3.0, high.responseDb(20_000.0, 48_000), 0.3)
    }

    @Test
    fun `processor matches the designed response on a real sine`() {
        val rate = 96_000
        val p = EqProcessor(rate, 1)
        p.configure(EqConfig(enabled = true, bands = listOf(EqBand(FilterType.PEAK, 1000.0, 6.0, 1.0)), autoPreamp = false))
        val n = rate / 2
        val x = FloatArray(n) { (0.25 * sin(2 * PI * 1000.0 * it / rate)).toFloat() }
        p.process(x, n)
        val tail = x.drop(n / 2)
        val rms = sqrt(tail.sumOf { (it * it).toDouble() } / tail.size)
        assertEquals(6.0, 20 * log10(rms / (0.25 / sqrt(2.0))), 0.05)
    }

    @Test
    fun `auto preamp undoes the biggest boost`() {
        val c = EqConfig(enabled = true, bands = listOf(EqBand(FilterType.PEAK, 100.0, 5.0, 1.0), EqBand(FilterType.PEAK, 5000.0, -3.0, 1.0)))
        assertEquals(-5.0, c.effectivePreampDb, 0.1)
    }

    @Test
    fun `flat or disabled eq is not active`() {
        assertFalse(EqConfig(enabled = true, bands = listOf(EqBand(gainDb = 0.0))).isActive)
        assertFalse(EqConfig(enabled = false, bands = listOf(EqBand(gainDb = 3.0))).isActive)
        assertTrue(EqConfig(enabled = true, bands = listOf(EqBand(FilterType.LOW_PASS, 15000.0))).isActive)
    }

    @Test
    fun `autoeq text imports`() {
        val c = AutoEqParser.parse(
            """
            Preamp: -6.2 dB
            Filter 1: ON LSC Fc 105 Hz Gain 5.5 dB Q 0.70
            Filter 2: ON PK Fc 2110 Hz Gain -3.1 dB Q 1.62
            Filter 3: OFF HSC Fc 10000 Hz Gain 2.0 dB Q 0.70
            Filter 4: ON LPQ Fc 18000 Hz
            """.trimIndent(),
        )!!
        assertEquals(-6.2, c.preampDb, 1e-9)
        assertFalse(c.autoPreamp)
        assertEquals(listOf(FilterType.LOW_SHELF, FilterType.PEAK, FilterType.HIGH_SHELF, FilterType.LOW_PASS), c.bands.map { it.type })
        assertEquals(2110.0, c.bands[1].freqHz, 1e-9)
        assertEquals(1.62, c.bands[1].q, 1e-9)
        assertFalse(c.bands[2].enabled)
        assertEquals(0.71, c.bands[3].q, 1e-9)
    }
}
