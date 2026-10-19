package io.github.momonekomata.sidea.audio

import io.github.momonekomata.sidea.sources.RandomAccess
import io.github.momonekomata.sidea.sources.ascii
import io.github.momonekomata.sidea.sources.readFully
import io.github.momonekomata.sidea.sources.readUpTo
import io.github.momonekomata.sidea.sources.u32be
import io.github.momonekomata.sidea.sources.u32le
import io.github.momonekomata.sidea.sources.u64be
import io.github.momonekomata.sidea.sources.u64le
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Raw 1-bit DSD from a .dsf or .dff file, handed out as per-channel byte runs with the
 * earliest bit in each byte's MSB.
 */
class DsdStream private constructor(
    private val ra: RandomAccess,
    val channels: Int,
    /** Bits per second per channel: 2 822 400 for DSD64. */
    val dsdRate: Int,
    /** Bytes of DSD per channel. */
    val bytesPerChannel: Long,
    private val dataStart: Long,
    /** DSF: bytes per channel per block (4096). DFF: 1, byte-interleaved. */
    private val blockSize: Int,
    private val lsbFirst: Boolean,
) {
    val durationUs: Long get() = bytesPerChannel * 8 * 1_000_000 / dsdRate
    val formatName: String get() = "DSD${dsdRate / 44_100}"

    /** Next byte (per channel) to hand out. */
    private var cursor = 0L

    fun seekToUs(us: Long) {
        cursor = (us * dsdRate / 8 / 1_000_000).coerceIn(0, bytesPerChannel)
        // Keep DoP frames and DSF blocks aligned.
        cursor -= cursor % 2
    }

    /**
     * Fills `out[ch]` with up to [maxBytes] bytes per channel and returns how many, 0 at the end.
     * Reads one DSF block group or one DFF run at a time.
     */
    fun read(out: Array<ByteArray>, maxBytes: Int): Int {
        val left = bytesPerChannel - cursor
        if (left <= 0) return 0
        val n: Int
        if (blockSize > 1) {
            val block = cursor / blockSize
            val inBlock = (cursor % blockSize).toInt()
            n = minOf(maxBytes.toLong(), (blockSize - inBlock).toLong(), left).toInt()
            for (ch in 0 until channels) {
                val at = dataStart + (block * channels + ch) * blockSize + inBlock
                val bytes = ra.readFully(at, n)
                if (lsbFirst) for (i in 0 until n) out[ch][i] = REVERSE[bytes[i].toInt() and 0xFF] else System.arraycopy(bytes, 0, out[ch], 0, n)
            }
        } else {
            n = minOf(maxBytes.toLong(), left).toInt()
            val bytes = ra.readFully(dataStart + cursor * channels, n * channels)
            for (i in 0 until n) for (ch in 0 until channels) out[ch][i] = bytes[i * channels + ch]
        }
        cursor += n
        return n
    }

    fun close() = ra.close()

    companion object {
        private val REVERSE = ByteArray(256) { b -> (Integer.reverse(b) ushr 24).toByte() }

        fun isDsd(head: ByteArray) = head.ascii(0, 4) == "DSD " || head.ascii(0, 4) == "FRM8"

        fun open(ra: RandomAccess): DsdStream {
            val head = ra.readFully(0, 4)
            return when (head.ascii(0, 4)) {
                "DSD " -> openDsf(ra)
                "FRM8" -> openDff(ra)
                else -> error("Not a DSD file")
            }
        }

        private fun openDsf(ra: RandomAccess): DsdStream {
            val dsd = ra.readFully(0, 28)
            var pos = dsd.u64le(4) // 28
            val fmt = ra.readFully(pos, 52)
            require(fmt.ascii(0, 4) == "fmt ") { "Broken DSF header" }
            val channels = fmt.u32le(24).toInt()
            val rate = fmt.u32le(28).toInt()
            val bits = fmt.u32le(32).toInt()
            val samples = fmt.u64le(36) // bits per channel
            val block = fmt.u32le(44).toInt()
            pos += fmt.u64le(4)
            val data = ra.readFully(pos, 12)
            require(data.ascii(0, 4) == "data") { "DSF data chunk missing" }
            return DsdStream(ra, channels, rate, samples / 8, pos + 12, block, lsbFirst = bits == 1)
        }

        private fun openDff(ra: RandomAccess): DsdStream {
            var channels = 2
            var rate = 2_822_400
            var pos = 16L // past FRM8 header and form type 'DSD '
            while (true) {
                val h = ra.readUpTo(pos, 12)
                require(h.size == 12) { "DFF without sound data" }
                val id = h.ascii(0, 4)
                val len = h.u64be(4)
                when (id) {
                    "PROP" -> {
                        var p = pos + 16 // past 'SND '
                        val end = pos + 12 + len
                        while (p + 12 <= end) {
                            val c = ra.readFully(p, 12)
                            val cLen = c.u64be(4)
                            when (c.ascii(0, 4)) {
                                "FS  " -> rate = ra.readFully(p + 12, 4).u32be(0).toInt()
                                "CHNL" -> channels = ra.readFully(p + 12, 2).let { ((it[0].toInt() and 0xFF) shl 8) or (it[1].toInt() and 0xFF) }
                                "CMPR" -> require(ra.readFully(p + 12, 4).ascii(0, 4) == "DSD ") { "DST-compressed DFF isn't supported" }
                            }
                            p += 12 + cLen + (cLen and 1)
                        }
                    }
                    "DSD " -> return DsdStream(ra, channels, rate, len / channels, pos + 12, blockSize = 1, lsbFirst = false)
                    "DST " -> error("DST-compressed DFF isn't supported")
                }
                pos += 12 + len + (len and 1)
            }
        }
    }
}

/**
 * DSD → PCM at 176.4 kHz: a linear-phase low-pass FIR evaluated on whole DSD bytes through
 * lookup tables (each byte position gets a 256-entry table of its 8 taps' contribution), then
 * decimated. The filter is flat to ~25 kHz and down by ~64 kHz, so DSD's ultrasonic noise stays
 * out of the audio band. Output is float at unity gain: a 50 % modulated "0 dB" DSD signal lands
 * at -6 dBFS, leaving headroom for SACD's allowed overshoot.
 */
class DsdToPcm(dsdRate: Int, private val channels: Int) {
    val outputRate = 176_400
    /** DSD bytes consumed per output sample. */
    private val step = dsdRate / outputRate / 8
    private val groups = 24 * step // filter length 192·step taps
    private val tables: Array<FloatArray>
    private val history = Array(channels) { ByteArray(groups) }

    init {
        require(step >= 2 && dsdRate % (outputRate * 8) == 0) { "Unsupported DSD rate $dsdRate" }
        val taps = groups * 8
        val fc = 44_000.0 / dsdRate // cutoff as a fraction of the DSD rate
        val h = DoubleArray(taps) { i ->
            val m = i - (taps - 1) / 2.0
            val sinc = if (m == 0.0) 2 * fc else sin(2 * PI * fc * m) / (PI * m)
            // Blackman window.
            val w = 0.42 - 0.5 * cos(2 * PI * i / (taps - 1)) + 0.08 * cos(4 * PI * i / (taps - 1))
            sinc * w
        }
        val sum = h.sum()
        tables = Array(groups) { g ->
            FloatArray(256) { b ->
                var acc = 0.0
                for (bit in 0 until 8) {
                    val one = (b shr (7 - bit)) and 1 == 1
                    acc += h[g * 8 + bit] / sum * (if (one) 1.0 else -1.0)
                }
                acc.toFloat()
            }
        }
    }

    init { reset() }

    fun reset() = history.forEach { it.fill(0x69) } // DSD idle pattern, averages to silence

    /**
     * Converts [count] bytes per channel into interleaved float PCM, written little-endian into
     * [out]. Returns bytes written.
     */
    fun process(input: Array<ByteArray>, count: Int, out: ByteArray): Int {
        var o = 0
        var i = 0
        while (i + step <= count) {
            for (ch in 0 until channels) {
                val hist = history[ch]
                System.arraycopy(hist, step, hist, 0, groups - step)
                System.arraycopy(input[ch], i, hist, groups - step, step)
                var acc = 0f
                for (g in 0 until groups) acc += tables[g][hist[g].toInt() and 0xFF]
                val bits = java.lang.Float.floatToRawIntBits(acc.coerceIn(-1f, 1f))
                out[o++] = bits.toByte(); out[o++] = (bits shr 8).toByte(); out[o++] = (bits shr 16).toByte(); out[o++] = (bits shr 24).toByte()
            }
            i += step
        }
        return o
    }

    fun outputBytesFor(count: Int) = count / step * channels * 4
}

/**
 * DSD over PCM (DoP v1.1): each 24-bit PCM sample carries 16 DSD bits under a marker byte that
 * alternates 0x05/0xFA, at a sixteenth of the DSD rate. The DAC recognises the markers and
 * plays native DSD. Only valid over a bit-perfect path; through a mixer it is just noise.
 */
class DopPacker(private val channels: Int) {
    private var markerHigh = false

    fun reset() { markerHigh = false }

    /** Packs [count] bytes per channel (even) into interleaved 24-bit little-endian PCM. */
    fun process(input: Array<ByteArray>, count: Int, out: ByteArray): Int {
        var o = 0
        var i = 0
        while (i + 1 < count) {
            val marker = if (markerHigh) 0xFA.toByte() else 0x05
            for (ch in 0 until channels) {
                out[o++] = input[ch][i + 1] // later bits, lowest byte
                out[o++] = input[ch][i]
                out[o++] = marker
            }
            markerHigh = !markerHigh
            i += 2
        }
        return o
    }

    fun outputBytesFor(count: Int) = count / 2 * channels * 3
}
