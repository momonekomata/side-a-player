package io.github.momonekomata.sidea.audio

import io.github.momonekomata.sidea.sources.RandomAccess
import io.github.momonekomata.sidea.sources.ascii
import io.github.momonekomata.sidea.sources.readFully
import io.github.momonekomata.sidea.sources.readUpTo
import io.github.momonekomata.sidea.sources.u32be
import io.github.momonekomata.sidea.sources.u64be
import java.io.IOException

/**
 * Apple Lossless in an MP4/M4A file, decoded in-app (many phones have no ALAC decoder). The
 * demuxer reads the sample tables of the first sound track; the decoder follows Apple's
 * open-source reference (adaptive Golomb residuals, adaptive FIR prediction, stereo
 * matrixing, shifted low bytes) and gives the exact integer samples back.
 */
/** The MP4's audio isn't ALAC; [fourcc] says what it is (mp4a = AAC, fLaC, Opus, ...). */
class NotAlacException(val fourcc: String) : IOException("Not ALAC ($fourcc)")

class AlacStream(private val ra: RandomAccess) {
    val sampleRate: Int
    val channels: Int
    val bitDepth: Int
    val totalSamples: Long
    private val frameLength: Int
    private val pb: Int
    private val mb: Int
    private val kb: Int
    private val offsets: LongArray
    private val sizes: IntArray
    /** First PCM sample of each MP4 sample (= ALAC frame). */
    private val starts: LongArray
    private var next = 0

    val format: SampleFormat
        get() = when {
            bitDepth <= 16 -> SampleFormat.S16
            bitDepth <= 24 -> SampleFormat.S24_PACKED
            else -> SampleFormat.S32
        }

    init {
        val end = ra.size.takeIf { it > 0 } ?: throw IOException("Unknown file size")
        val moov = child(0, end, "moov") ?: throw IOException("No moov atom (fragmented MP4 isn't supported)")
        var stbl: Pair<Long, Long>? = null
        var timescale = 0L
        var duration = 0L
        forEachChild(moov.first, moov.second) { type, s, e ->
            if (type != "trak" || stbl != null) return@forEachChild
            val mdia = child(s, e, "mdia") ?: return@forEachChild
            val hdlr = child(mdia.first, mdia.second, "hdlr") ?: return@forEachChild
            if (ra.readFully(hdlr.first + 8, 4).ascii(0, 4) != "soun") return@forEachChild
            child(mdia.first, mdia.second, "mdhd")?.let { mdhd ->
                val h = ra.readFully(mdhd.first, 32)
                if (h[0].toInt() == 1) { timescale = h.u32be(20); duration = h.u64be(24) } else { timescale = h.u32be(12); duration = h.u32be(16) }
            }
            val minf = child(mdia.first, mdia.second, "minf") ?: return@forEachChild
            stbl = child(minf.first, minf.second, "stbl")
        }
        val table = stbl ?: throw IOException("No sound track")
        // stsd → 'alac' sample entry → 'alac' config box.
        val stsd = child(table.first, table.second, "stsd") ?: throw IOException("No sample description")
        val entry = ra.readFully(stsd.first + 8, 8)
        val fourcc = entry.ascii(4, 4)
        if (fourcc != "alac") throw NotAlacException(fourcc.trim())
        val entryStart = stsd.first + 8
        val entryLen = entry.u32be(0).toInt().coerceIn(36, 4096)
        // The config box sits after the sound sample entry, whose length depends on its version
        // (0, 1 or 2, and some writers wrap it in 'wave'), so look for it rather than assume.
        val body = ra.readFully(entryStart, entryLen)
        var at = -1
        for (i in 28..body.size - 36) {
            if (body.ascii(i + 4, 4) == "alac" && body.u32be(i) >= 36) { at = i; break }
        }
        if (at < 0) throw IOException("ALAC config missing")
        val c = body.copyOfRange(at + 12, at + 36) // past size, type, version/flags
        frameLength = c.u32be(0).toInt()
        bitDepth = c[5].toInt() and 0xFF
        pb = c[6].toInt() and 0xFF
        mb = c[7].toInt() and 0xFF
        kb = c[8].toInt() and 0xFF
        channels = c[9].toInt() and 0xFF
        sampleRate = c.u32be(20).toInt()
        if (channels !in 1..2) throw IOException("Only mono and stereo ALAC are supported")
        if (bitDepth !in listOf(16, 20, 24, 32)) throw IOException("Unsupported ALAC bit depth $bitDepth")

        val sz = readSizes(table)
        sizes = sz
        offsets = readOffsets(table, sz.size)
        starts = readStarts(table, sz.size)
        totalSamples = if (timescale.toInt() == sampleRate && duration > 0) duration
        else starts.lastOrNull()?.let { it + frameLength } ?: 0
    }

    // ---- atoms ----

    private inline fun forEachChild(from: Long, to: Long, f: (String, Long, Long) -> Unit) {
        var pos = from
        while (pos + 8 <= to) {
            val h = ra.readUpTo(pos, 16)
            if (h.size < 8) return
            var len = h.u32be(0)
            var hdr = 8
            if (len == 1L && h.size >= 16) { len = h.u64be(8); hdr = 16 }
            if (len == 0L) len = to - pos
            if (len < hdr) return
            f(h.ascii(4, 4), pos + hdr, pos + len)
            pos += len
        }
    }

    private fun child(from: Long, to: Long, type: String): Pair<Long, Long>? {
        forEachChild(from, to) { t, s, e -> if (t == type) return s to e }
        return null
    }

    private fun readSizes(stbl: Pair<Long, Long>): IntArray {
        val stsz = child(stbl.first, stbl.second, "stsz") ?: throw IOException("No sample sizes")
        val h = ra.readFully(stsz.first, 12)
        val fixed = h.u32be(4).toInt()
        val count = h.u32be(8).toInt()
        if (fixed != 0) return IntArray(count) { fixed }
        val b = ra.readFully(stsz.first + 12, count * 4)
        return IntArray(count) { b.u32be(it * 4).toInt() }
    }

    private fun readOffsets(stbl: Pair<Long, Long>, samples: Int): LongArray {
        val co64 = child(stbl.first, stbl.second, "co64")
        val stco = co64 ?: child(stbl.first, stbl.second, "stco") ?: throw IOException("No chunk offsets")
        val n = ra.readFully(stco.first + 4, 4).u32be(0).toInt()
        val w = if (co64 != null) 8 else 4
        val b = ra.readFully(stco.first + 8, n * w)
        val chunks = LongArray(n) { if (w == 8) b.u64be(it * 8) else b.u32be(it * 4) }
        val stsc = child(stbl.first, stbl.second, "stsc") ?: throw IOException("No sample-to-chunk table")
        val m = ra.readFully(stsc.first + 4, 4).u32be(0).toInt()
        val t = ra.readFully(stsc.first + 8, m * 12)
        val out = LongArray(samples)
        var s = 0
        for (e in 0 until m) {
            val first = t.u32be(e * 12).toInt() - 1
            val per = t.u32be(e * 12 + 4).toInt()
            val last = if (e + 1 < m) t.u32be((e + 1) * 12).toInt() - 1 else n
            for (ch in first until last) {
                var off = chunks[ch]
                repeat(per) {
                    if (s < samples) { out[s] = off; off += sizes[s]; s++ }
                }
            }
        }
        return out
    }

    private fun readStarts(stbl: Pair<Long, Long>, samples: Int): LongArray {
        val out = LongArray(samples)
        val stts = child(stbl.first, stbl.second, "stts")
        if (stts == null) {
            for (i in 0 until samples) out[i] = i.toLong() * frameLength
            return out
        }
        val n = ra.readFully(stts.first + 4, 4).u32be(0).toInt()
        val b = ra.readFully(stts.first + 8, n * 8)
        var s = 0
        var at = 0L
        for (e in 0 until n) {
            val count = b.u32be(e * 8)
            val delta = b.u32be(e * 8 + 4)
            for (k in 0 until count) {
                if (s >= samples) break
                out[s++] = at
                at += delta
            }
        }
        return out
    }

    // ---- decoding ----

    private val bufU = IntArray(frameLength.coerceAtLeast(1))
    private val bufV = IntArray(frameLength.coerceAtLeast(1))
    private val extraU = IntArray(frameLength.coerceAtLeast(1))
    private val extraV = IntArray(frameLength.coerceAtLeast(1))
    private val err = IntArray(frameLength.coerceAtLeast(1))

    /** Moves to the frame holding [sample]; returns that frame's first sample. */
    fun seekTo(sample: Long): Long {
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (starts[mid] <= sample) lo = mid else hi = mid - 1
        }
        next = lo.coerceAtLeast(0)
        return starts.getOrElse(next) { 0 }
    }

    fun nextFrame(): PcmChunk? {
        if (next >= sizes.size) return null
        val i = next++
        val packet = ra.readFully(offsets[i], sizes[i])
        val r = BitReader(io.github.momonekomata.sidea.sources.ByteArrayAccess(packet), 0, zeroPadEnd = true)
        var n = 0
        var ch = 0
        while (true) {
            val tag = r.readBits(3).toInt()
            when (tag) {
                0, 3 -> { n = element(r, 1, if (ch == 0) bufU else bufV, bufV); ch++ }
                1 -> { n = element(r, 2, bufU, bufV); ch += 2 }
                4 -> { // DSE: skip
                    r.readBits(4)
                    val align = r.readBits(1) == 1L
                    var count = r.readBits(8).toInt()
                    if (count == 255) count += r.readBits(8).toInt()
                    if (align) r.alignToByte()
                    repeat(count) { r.readBits(8) }
                }
                6 -> { // FIL: skip
                    var count = r.readBits(4).toInt()
                    if (count == 15) count += r.readBits(8).toInt() - 1
                    repeat(count) { r.readBits(8) }
                }
                7 -> break
                else -> throw IOException("Unsupported ALAC element $tag")
            }
            if (ch >= channels) break
        }
        return PcmChunk(pack(n), n * channels * format.bytesPerSample, starts[i] * 1_000_000 / sampleRate)
    }

    /** Decodes one SCE (1 channel) or CPE (2 channels) element; returns its sample count. */
    private fun element(r: BitReader, chans: Int, u: IntArray, v: IntArray): Int {
        r.readBits(4) // element instance tag
        r.readBits(12)
        val partial = r.readBits(1) == 1L
        val extraBits = r.readBits(2).toInt() * 8
        val compressed = r.readBits(1) == 0L
        val n = if (partial) r.readBits(32).toInt() else frameLength
        if (n !in 0..frameLength) throw IOException("Bad ALAC frame size $n")
        val bps = bitDepth - extraBits + chans - 1
        var mixShift = 0
        var mixWeight = 0
        var shift = extraBits
        if (compressed) {
            mixShift = r.readBits(8).toInt()
            mixWeight = r.readBits(8).toInt()
            val mode = IntArray(chans)
            val quant = IntArray(chans)
            val mult = IntArray(chans)
            val coefs = Array(chans) { IntArray(32) }
            val order = IntArray(chans)
            for (c in 0 until chans) {
                mode[c] = r.readBits(4).toInt()
                quant[c] = r.readBits(4).toInt()
                mult[c] = r.readBits(3).toInt()
                order[c] = r.readBits(5).toInt()
                for (k in order[c] - 1 downTo 0) coefs[c][k] = r.readSigned(16).toInt()
            }
            if (extraBits > 0) {
                for (i in 0 until n) {
                    extraU[i] = r.readBits(extraBits).toInt()
                    if (chans == 2) extraV[i] = r.readBits(extraBits).toInt()
                }
            }
            for (c in 0 until chans) {
                val out = if (c == 0) u else v
                riceDecode(r, err, n, bps, mult[c] * pb / 4)
                if (mode[c] == 15) predict(err, err, n, bps, coefs[c], 31, 0)
                else if (mode[c] != 0) throw IOException("Unknown ALAC prediction mode ${mode[c]}")
                predict(err, out, n, bps, coefs[c], order[c], quant[c])
            }
        } else {
            for (i in 0 until n) {
                u[i] = r.readSigned(bitDepth).toInt()
                if (chans == 2) v[i] = r.readSigned(bitDepth).toInt()
            }
            shift = 0
        }
        if (chans == 2 && mixWeight != 0) {
            for (i in 0 until n) {
                var a = u[i]
                var b = v[i]
                a -= (b * mixWeight) shr mixShift
                b += a
                u[i] = b
                v[i] = a
            }
        }
        if (shift > 0) {
            for (i in 0 until n) {
                u[i] = (u[i] shl shift) or extraU[i]
                if (chans == 2) v[i] = (v[i] shl shift) or extraV[i]
            }
        }
        return n
    }

    /** Adaptive Golomb-Rice residuals with zero-run escapes, as in Apple's ag_dec.c. */
    private fun riceDecode(r: BitReader, out: IntArray, n: Int, bps: Int, historyMult: Int) {
        var history = mb.toLong()
        var signModifier = 0
        var i = 0
        while (i < n) {
            var k = log2(((history ushr 9) + 3).toInt())
            if (k > kb) k = kb
            var x = scalar(r, k, bps) + signModifier
            signModifier = 0
            out[i] = ((x ushr 1) xor -(x and 1)).toInt()
            // 32-bit unsigned arithmetic, wrap-around included, as the reference does it.
            history = if (x > 0xFFFF) 0xFFFF else (history + x * historyMult - (((history * historyMult) and 0xFFFFFFFFL) shr 9)) and 0xFFFFFFFFL
            if (history < 128 && i + 1 < n) {
                k = (Integer.numberOfLeadingZeros(history.toInt()) - 24 + ((history + 16) shr 6).toInt()).coerceAtMost(kb)
                val block = scalar(r, k, 16).toInt()
                if (block > 0) {
                    if (block >= n - i) throw IOException("ALAC zero run past the frame")
                    for (z in 1..block) out[i + z] = 0
                    i += block
                }
                if (block <= 0xFFFF) signModifier = 1
                history = 0
            }
            i++
        }
    }

    private fun scalar(r: BitReader, k: Int, bps: Int): Long {
        // Up to 9 leading ones; 9 means the value follows raw.
        var x = 0L
        while (x < 9 && r.readBits(1) == 1L) x++
        if (x >= 9) return r.readBits(bps)
        if (k == 1) return x
        val extra = r.peekBits(k)
        x = (x shl k) - x
        return if (extra > 1) {
            r.readBits(k)
            x + extra - 1
        } else {
            r.readBits(k - 1)
            x
        }
    }

    private fun log2(v: Int) = 31 - Integer.numberOfLeadingZeros(v)

    /** Adaptive FIR prediction with sign-LMS coefficient updates (dp_dec.c / ffmpeg's lpc_prediction). */
    private fun predict(e: IntArray, out: IntArray, n: Int, bps: Int, coefs: IntArray, order: Int, quant: Int) {
        if (n <= 0) return
        val sh = 32 - bps
        out[0] = e[0]
        if (order == 0) {
            if (out !== e) System.arraycopy(e, 1, out, 1, n - 1)
            return
        }
        if (order == 31) {
            for (i in 1 until n) out[i] = ((out[i - 1] + e[i]) shl sh) shr sh
            return
        }
        var i = 1
        while (i <= order && i < n) {
            out[i] = ((out[i - 1] + e[i]) shl sh) shr sh
            i++
        }
        val round = if (quant > 0) 1 shl (quant - 1) else 0
        while (i < n) {
            val base = i - order - 1
            val d = out[base]
            var acc = 0
            for (j in 0 until order) acc += (out[base + 1 + j] - d) * coefs[j]
            var residual = e[i]
            out[i] = ((((acc.toLong() + round) shr quant).toInt() + d + residual) shl sh) shr sh
            val sign = Integer.signum(residual)
            if (sign != 0) {
                var j = 0
                while (j < order && residual * sign > 0) {
                    val diff = d - out[base + 1 + j]
                    val s = Integer.signum(diff) * sign
                    coefs[j] -= s
                    residual -= ((diff * s) shr quant) * (j + 1)
                    j++
                }
            }
            i++
        }
    }

    private fun pack(n: Int): ByteArray {
        val bytes = format.bytesPerSample
        val shift = bytes * 8 - bitDepth
        val out = ByteArray(n * channels * bytes)
        var o = 0
        for (i in 0 until n) {
            for (c in 0 until channels) {
                val v = (if (c == 0) bufU[i] else bufV[i]) shl shift
                out[o++] = v.toByte()
                out[o++] = (v shr 8).toByte()
                if (bytes >= 3) out[o++] = (v shr 16).toByte()
                if (bytes == 4) out[o++] = (v shr 24).toByte()
            }
        }
        return out
    }

    fun close() = ra.close()

    companion object {
        /** An MP4 whose first sample description is ALAC; AAC m4a goes to MediaCodec. */
        fun sniff(head: ByteArray) = head.ascii(4, 4) == "ftyp"
    }
}
