package io.github.momonekomata.sidea.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class TimeStretchTest {
    private val rate = 48_000

    private fun sine(seconds: Double, hz: Double, channels: Int): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n * channels) { i -> (0.5 * sin(2 * PI * hz * (i / channels) / rate)).toFloat() }
    }

    private fun run(speed: Float, src: FloatArray, channels: Int): FloatArray {
        val ts = TimeStretch(rate, channels, speed)
        val out = ArrayList<Float>()
        val chunk = 4096
        var at = 0
        val frames = src.size / channels
        while (at < frames) {
            val n = minOf(chunk, frames - at)
            val produced = ts.process(src.copyOfRange(at * channels, (at + n) * channels), n)
            for (k in 0 until produced * channels) out += ts.output[k]
            at += n
        }
        val tail = ts.flush()
        for (k in 0 until tail * channels) out += ts.output[k]
        return out.toFloatArray()
    }

    /** Zero crossings per second of the first channel: the pitch, which must not move. */
    private fun pitch(x: FloatArray, channels: Int): Double {
        var crossings = 0
        val frames = x.size / channels
        // Skip the edges, where the stretcher starts and stops.
        val from = rate / 10
        val to = frames - rate / 10
        for (i in from + 1 until to) if ((x[(i - 1) * channels] < 0) != (x[i * channels] < 0)) crossings++
        return crossings / 2.0 / ((to - from).toDouble() / rate)
    }

    @Test
    fun doubleSpeedHalvesTheLengthAndKeepsThePitch() {
        val src = sine(4.0, 440.0, 2)
        val out = run(2f, src, 2)
        assertEquals(2.0, out.size / 2.0 / rate, 0.05)
        assertEquals(440.0, pitch(out, 2), 4.0)
    }

    @Test
    fun halfSpeedDoublesTheLengthAndKeepsThePitch() {
        val src = sine(2.0, 1000.0, 1)
        val out = run(0.5f, src, 1)
        assertEquals(4.0, out.size.toDouble() / rate, 0.08)
        assertEquals(1000.0, pitch(out, 1), 8.0)
    }

    @Test
    fun normalSpeedKeepsEverySample() {
        val src = sine(1.0, 300.0, 2)
        val out = run(1f, src, 2)
        assertEquals(src.size, out.size)
    }
}
