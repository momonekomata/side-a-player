package io.github.momonekomata.sidea.audio

import kotlin.math.sqrt

/**
 * Plays faster or slower with the pitch kept (WSOLA, the way SoundTouch does it). The input is
 * cut into 40 ms pieces taken [speed]× as far apart as they're laid down; each one starts where
 * it best lines up with the end of the last (searched over 15 ms) and joins it with an 8 ms
 * crossfade. Works on interleaved float frames, so it runs on Side A's own output paths,
 * the USB driver included.
 */
class TimeStretch(sampleRate: Int, private val channels: Int, @Volatile var speed: Float) {
    private val seq = (sampleRate * 0.040).toInt()
    private val overlap = (sampleRate * 0.008).toInt().coerceAtLeast(16)
    private val seek = (sampleRate * 0.015).toInt()
    // Above 48 kHz the best spot is looked for on every n-th frame; it's the same music.
    private val stride = (sampleRate / 48_000).coerceAtLeast(1)

    private var input = FloatArray(0)
    private var inFrames = 0
    private val mid = FloatArray(overlap * channels)
    private var haveMid = false
    private var carry = 0.0
    // Where in the input the audio after [mid] begins, for [flush].
    private var afterMid = 0
    private var out = FloatArray(0)

    /** Stretches [frames] frames of [src]; the result is the first returned-count frames of [output]. */
    fun process(src: FloatArray, frames: Int): Int {
        append(src, frames)
        var outFrames = 0
        val laid = seq - overlap
        while (true) {
            val skipExact = speed * laid + carry
            val skip = skipExact.toInt()
            if (inFrames < seek + seq || inFrames < skip) break
            if (out.size < (outFrames + laid) * channels) out = out.copyOf((outFrames + laid) * channels * 2)
            val off = if (haveMid) bestOffset() else 0
            var o = outFrames * channels
            var from: Int
            if (haveMid) {
                for (i in 0 until overlap) {
                    val w = i.toFloat() / overlap
                    val base = (off + i) * channels
                    for (c in 0 until channels) out[o++] = mid[i * channels + c] * (1f - w) + input[base + c] * w
                }
                from = off + overlap
            } else {
                from = off
            }
            val body = off + seq - overlap - from
            System.arraycopy(input, from * channels, out, o, body * channels)
            outFrames += laid
            System.arraycopy(input, (off + seq - overlap) * channels, mid, 0, overlap * channels)
            haveMid = true
            carry = skipExact - skip
            afterMid = off + seq - skip
            drop(skip)
        }
        return outFrames
    }

    /** The buffer [process] and [flush] write to. */
    val output: FloatArray get() = out

    /** What's still inside at the end of the music, unstretched; leaves the stretcher empty. */
    fun flush(): Int {
        val from = if (haveMid) afterMid.coerceIn(0, inFrames) else 0
        val rest = inFrames - from
        val tail = (if (haveMid) overlap else 0) + rest
        if (out.size < tail * channels) out = FloatArray(tail * channels)
        var o = 0
        if (haveMid) {
            System.arraycopy(mid, 0, out, 0, overlap * channels)
            o = overlap * channels
        }
        System.arraycopy(input, from * channels, out, o, rest * channels)
        reset()
        return tail
    }

    /** Forgets everything, for a seek. */
    fun reset() {
        inFrames = 0
        haveMid = false
        carry = 0.0
        afterMid = 0
    }

    /** Where in the first [seek] frames the input best continues [mid]: normalised cross-correlation. */
    private fun bestOffset(): Int {
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        var i = 0
        while (i < seek) {
            var corr = 0.0
            var energy = 1e-12
            var k = 0
            while (k < overlap) {
                var a = 0f
                var b = 0f
                val mi = k * channels
                val ii = (i + k) * channels
                for (c in 0 until channels) {
                    a += mid[mi + c]
                    b += input[ii + c]
                }
                corr += a * b
                energy += b * b
                k += stride
            }
            val score = corr / sqrt(energy)
            if (score > bestScore) {
                bestScore = score
                best = i
            }
            i += stride
        }
        return best
    }

    private fun append(src: FloatArray, frames: Int) {
        val need = (inFrames + frames) * channels
        if (input.size < need) input = input.copyOf(need * 2)
        System.arraycopy(src, 0, input, inFrames * channels, frames * channels)
        inFrames += frames
    }

    private fun drop(n: Int) {
        System.arraycopy(input, n * channels, input, 0, (inFrames - n) * channels)
        inFrames -= n
    }
}
