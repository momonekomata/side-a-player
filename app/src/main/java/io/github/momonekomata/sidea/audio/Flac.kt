package io.github.momonekomata.sidea.audio

import io.github.momonekomata.sidea.sources.RandomAccess
import io.github.momonekomata.sidea.sources.ascii
import io.github.momonekomata.sidea.sources.readFully
import io.github.momonekomata.sidea.sources.readUpTo
import io.github.momonekomata.sidea.sources.syncsafe
import java.io.EOFException
import java.io.IOException

/** Reads bits MSB-first from a [RandomAccess], fetching 64 KB at a time. */
internal class BitReader(private val ra: RandomAccess, start: Long) {
    private val buf = ByteArray(1 shl 16)
    private var bufStart = start
    private var bufLen = 0
    private var bufPos = 0
    private var cache = 0L
    private var bits = 0

    /** Absolute position of the next whole byte not yet consumed. */
    val bytePosition: Long get() = bufStart + bufPos - bits / 8

    fun seek(pos: Long) {
        bufStart = pos; bufLen = 0; bufPos = 0; cache = 0; bits = 0
    }

    private fun nextByte(): Int {
        if (bufPos >= bufLen) {
            bufStart += bufLen
            bufPos = 0
            bufLen = ra.readAt(bufStart, buf, 0, buf.size).coerceAtLeast(0)
            if (bufLen == 0) throw EOFException()
        }
        return buf[bufPos++].toInt() and 0xFF
    }

    private fun need(n: Int) {
        while (bits < n) {
            cache = (cache shl 8) or nextByte().toLong()
            bits += 8
        }
    }

    fun readBits(n: Int): Long {
        if (n == 0) return 0
        need(n)
        bits -= n
        return (cache ushr bits) and ((1L shl n) - 1)
    }

    fun readSigned(n: Int): Long {
        if (n == 0) return 0
        val v = readBits(n)
        return (v shl (64 - n)) shr (64 - n)
    }

    fun readUnary(): Int {
        var count = 0
        while (true) {
            if (bits == 0) need(8)
            val top = cache shl (64 - bits)
            if (top == 0L) {
                count += bits
                bits = 0
                continue
            }
            val lz = java.lang.Long.numberOfLeadingZeros(top)
            count += lz
            bits -= lz + 1
            return count
        }
    }

    fun alignToByte() {
        bits -= bits % 8
    }
}

/**
 * A FLAC decoder in plain Kotlin. Android's own needs its extractor to recognise the file, which
 * fails for files with an ID3 tag glued to the front (common with some taggers) and on some
 * phones for 32-bit or >192 kHz FLAC. This one outputs the integer samples themselves, so the
 * bit-perfect path never goes through float.
 */
class FlacStream(private val ra: RandomAccess) {
    val sampleRate: Int
    val channels: Int
    val bitsPerSample: Int
    val totalSamples: Long
    private val minBlock: Int
    private val maxBlock: Int
    private val firstFrame: Long
    private val seekPoints = mutableListOf<Pair<Long, Long>>() // sample, byte offset from firstFrame
    private val reader: BitReader

    private var decoded = Array(0) { LongArray(0) }

    init {
        var start = 0L
        val head = ra.readFully(0, 10)
        if (head.ascii(0, 3) == "ID3") start = 10L + head.syncsafe(6) + (if (head[5].toInt() and 0x10 != 0) 10 else 0)
        // Some files have padding between the tag and the stream; look a little further.
        val probe = ra.readUpTo(start, 4096)
        val at = String(probe, Charsets.ISO_8859_1).indexOf("fLaC")
        if (at < 0) throw IOException("Not a FLAC stream")
        var pos = start + at + 4
        var info: ByteArray? = null
        while (true) {
            val h = ra.readFully(pos, 4)
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            when (type) {
                0 -> info = ra.readFully(pos + 4, len)
                3 -> {
                    val t = ra.readFully(pos + 4, len)
                    for (i in 0 until len / 18) {
                        val o = i * 18
                        val sample = be(t, o, 8)
                        if (sample != -1L) seekPoints += sample to be(t, o + 8, 8)
                    }
                }
            }
            pos += 4 + len
            if (last) break
        }
        val si = info ?: throw IOException("FLAC without STREAMINFO")
        minBlock = be(si, 0, 2).toInt()
        maxBlock = be(si, 2, 2).toInt()
        val packed = be(si, 10, 8)
        sampleRate = (packed ushr 44).toInt()
        channels = ((packed ushr 41) and 7).toInt() + 1
        bitsPerSample = ((packed ushr 36) and 31).toInt() + 1
        totalSamples = packed and 0xFFFFFFFFFL
        firstFrame = pos
        reader = BitReader(ra, firstFrame)
    }

    /** The PCM container these samples go in without loss. */
    val format: SampleFormat = when {
        bitsPerSample <= 16 -> SampleFormat.S16
        bitsPerSample <= 24 -> SampleFormat.S24_PACKED
        else -> SampleFormat.S32
    }

    private class Header(val blockSize: Int, val channelMode: Int, val bps: Int, val firstSample: Long)

    /** Decodes the next frame into interleaved little-endian PCM in [format]; null at the end. */
    fun nextFrame(): PcmChunk? {
        val h = try {
            readHeader(reader)
        } catch (_: EOFException) {
            return null
        } ?: return null
        val n = h.blockSize
        if (decoded.size != channels || decoded[0].size < n) decoded = Array(channels) { LongArray(maxOf(n, maxBlock)) }
        for (ch in 0 until channels) {
            val extra = when {
                h.channelMode == 8 && ch == 1 -> 1 // left/side: side has one more bit
                h.channelMode == 9 && ch == 0 -> 1
                h.channelMode == 10 && ch == 1 -> 1
                else -> 0
            }
            subframe(reader, decoded[ch], n, h.bps + extra)
        }
        reader.alignToByte()
        reader.readBits(16) // CRC-16; a damaged frame shows up as noise, as in every player
        decorrelate(h.channelMode, n)
        return PcmChunk(pack(n, h.bps), n * channels * format.bytesPerSample, h.firstSample * 1_000_000 / sampleRate)
    }

    private fun readHeader(r: BitReader): Header? {
        val sync = r.readBits(14)
        if (sync != 0x3FFEL) throw IOException("Lost FLAC frame sync")
        r.readBits(1)
        val variable = r.readBits(1) == 1L
        val bsCode = r.readBits(4).toInt()
        val srCode = r.readBits(4).toInt()
        val chMode = r.readBits(4).toInt()
        val ssCode = r.readBits(3).toInt()
        r.readBits(1)
        val number = readUtf8Number(r)
        val blockSize = when (bsCode) {
            1 -> 192
            in 2..5 -> 576 shl (bsCode - 2)
            6 -> r.readBits(8).toInt() + 1
            7 -> r.readBits(16).toInt() + 1
            in 8..15 -> 256 shl (bsCode - 8)
            else -> throw IOException("Bad FLAC block size")
        }
        when (srCode) {
            12 -> r.readBits(8)
            13, 14 -> r.readBits(16)
        }
        r.readBits(8) // CRC-8
        val bps = when (ssCode) {
            0 -> bitsPerSample
            1 -> 8; 2 -> 12; 4 -> 16; 5 -> 20; 6 -> 24; 7 -> 32
            else -> throw IOException("Bad FLAC sample size")
        }
        val first = if (variable) number else number * minBlock
        return Header(blockSize, chMode, bps, first)
    }

    private fun readUtf8Number(r: BitReader): Long {
        val b0 = r.readBits(8).toInt()
        var len = 0
        while (len < 7 && (b0 shl len) and 0x80 != 0) len++
        if (len == 0) return b0.toLong()
        var v = (b0 and (0x7F ushr len)).toLong()
        repeat(len - 1) { v = (v shl 6) or (r.readBits(8) and 0x3F) }
        return v
    }

    private fun subframe(r: BitReader, out: LongArray, n: Int, bpsIn: Int) {
        r.readBits(1)
        val type = r.readBits(6).toInt()
        var wasted = 0
        if (r.readBits(1) == 1L) wasted = r.readUnary() + 1
        val bps = bpsIn - wasted
        when {
            type == 0 -> { val v = r.readSigned(bps); for (i in 0 until n) out[i] = v }
            type == 1 -> for (i in 0 until n) out[i] = r.readSigned(bps)
            type in 8..12 -> fixed(r, out, n, bps, type - 8)
            type >= 32 -> lpc(r, out, n, bps, type - 31)
            else -> throw IOException("Bad FLAC subframe type $type")
        }
        if (wasted > 0) for (i in 0 until n) out[i] = out[i] shl wasted
    }

    private fun fixed(r: BitReader, s: LongArray, n: Int, bps: Int, order: Int) {
        for (i in 0 until order) s[i] = r.readSigned(bps)
        residual(r, s, n, order)
        when (order) {
            1 -> for (i in 1 until n) s[i] += s[i - 1]
            2 -> for (i in 2 until n) s[i] += 2 * s[i - 1] - s[i - 2]
            3 -> for (i in 3 until n) s[i] += 3 * s[i - 1] - 3 * s[i - 2] + s[i - 3]
            4 -> for (i in 4 until n) s[i] += 4 * s[i - 1] - 6 * s[i - 2] + 4 * s[i - 3] - s[i - 4]
        }
    }

    private fun lpc(r: BitReader, s: LongArray, n: Int, bps: Int, order: Int) {
        for (i in 0 until order) s[i] = r.readSigned(bps)
        val precision = r.readBits(4).toInt() + 1
        if (precision == 16) throw IOException("Bad FLAC LPC precision")
        val shift = r.readSigned(5).toInt()
        val coefs = LongArray(order) { r.readSigned(precision) }
        residual(r, s, n, order)
        for (i in order until n) {
            var sum = 0L
            for (j in 0 until order) sum += coefs[j] * s[i - 1 - j]
            s[i] += sum shr shift
        }
    }

    /** Rice-coded residuals, written after the warm-up samples. */
    private fun residual(r: BitReader, s: LongArray, n: Int, order: Int) {
        val method = r.readBits(2).toInt()
        val paramBits = if (method == 0) 4 else 5
        val escape = if (method == 0) 15 else 31
        val partOrder = r.readBits(4).toInt()
        val parts = 1 shl partOrder
        val per = n shr partOrder
        var i = order
        for (p in 0 until parts) {
            val count = if (p == 0) per - order else per
            val param = r.readBits(paramBits).toInt()
            if (param == escape) {
                val raw = r.readBits(5).toInt()
                repeat(count) { s[i++] = r.readSigned(raw) }
            } else {
                repeat(count) {
                    val q = r.readUnary().toLong()
                    val v = (q shl param) or r.readBits(param)
                    s[i++] = (v ushr 1) xor -(v and 1)
                }
            }
        }
    }

    private fun decorrelate(mode: Int, n: Int) {
        if (mode < 8) return
        val a = decoded[0]
        val b = decoded[1]
        when (mode) {
            8 -> for (i in 0 until n) b[i] = a[i] - b[i] // left, side → right
            9 -> for (i in 0 until n) a[i] = a[i] + b[i] // side, right → left
            10 -> for (i in 0 until n) {
                val mid = (a[i] shl 1) or (b[i] and 1)
                val side = b[i]
                a[i] = (mid + side) shr 1
                b[i] = (mid - side) shr 1
            }
        }
    }

    /** Interleaves into [format], left-justifying odd depths (12, 20 bit) so values stay exact. */
    private fun pack(n: Int, bps: Int): ByteArray {
        val bytes = format.bytesPerSample
        val shift = bytes * 8 - bps
        val out = ByteArray(n * channels * bytes)
        var o = 0
        for (i in 0 until n) {
            for (ch in 0 until channels) {
                val v = decoded[ch][i] shl shift
                out[o++] = v.toByte()
                out[o++] = (v shr 8).toByte()
                if (bytes >= 3) out[o++] = (v shr 16).toByte()
                if (bytes == 4) out[o++] = (v shr 24).toByte()
            }
        }
        return out
    }

    /**
     * Moves to a frame at or before [sample] and returns its first sample; the caller decodes
     * forward and drops the lead-in. Uses the SEEKTABLE when there is one, then bisects over frame
     * headers to within 64 KB, which is a fraction of a second even for 192 kHz.
     */
    fun seekTo(sample: Long): Long {
        var lo = firstFrame
        var loSample = 0L
        seekPoints.lastOrNull { it.first <= sample }?.let { (s, off) -> lo = firstFrame + off; loSample = s }
        var hi = ra.size.takeIf { it > 0 } ?: lo
        while (hi - lo > 65_536) {
            val mid = lo + (hi - lo) / 2
            val f = findFrame(mid)
            if (f == null || f.second > sample) hi = mid else { lo = f.first; loSample = f.second }
        }
        reader.seek(lo)
        return loSample
    }

    /** The first valid frame header at or after [from]: (offset, first sample). */
    private fun findFrame(from: Long): Pair<Long, Long>? {
        val window = ra.readUpTo(from, 256 * 1024)
        for (i in 0 until window.size - 16) {
            if (window[i].toInt() and 0xFF != 0xFF || (window[i + 1].toInt() and 0xFE) != 0xF8) continue
            val candidate = from + i
            val hdrEnd = headerLength(window, i) ?: continue
            if (crc8(window, i, hdrEnd - i) != (window[hdrEnd].toInt() and 0xFF)) continue
            val r = BitReader(ByteWindow(window, from), candidate)
            val h = runCatching { readHeader(r) }.getOrNull() ?: continue
            if (h.bps != bitsPerSample) continue
            return candidate to h.firstSample
        }
        return null
    }

    /** Index of the CRC-8 byte for a header starting at [i], or null if it can't be one. */
    private fun headerLength(b: ByteArray, i: Int): Int? {
        val bsCode = (b[i + 2].toInt() ushr 4) and 15
        val srCode = b[i + 2].toInt() and 15
        val ss = (b[i + 3].toInt() ushr 1) and 7
        if (bsCode == 0 || srCode == 15 || ss == 3 || ss == 7 && bitsPerSample != 32) return null
        var p = i + 4
        val b0 = b[p].toInt() and 0xFF
        var len = 0
        while (len < 7 && (b0 shl len) and 0x80 != 0) len++
        if (len == 1) return null
        p += if (len == 0) 1 else len
        p += when (bsCode) { 6 -> 1; 7 -> 2; else -> 0 }
        p += when (srCode) { 12 -> 1; 13, 14 -> 2; else -> 0 }
        return if (p < b.size) p else null
    }

    private class ByteWindow(private val b: ByteArray, private val base: Long) : RandomAccess {
        override val size get() = base + b.size
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
            val at = position - base
            if (at < 0 || at >= b.size) return -1
            val n = minOf(length.toLong(), b.size - at).toInt()
            System.arraycopy(b, at.toInt(), buffer, offset, n)
            return n
        }
    }

    fun close() = ra.close()

    companion object {
        private fun be(b: ByteArray, at: Int, n: Int): Long {
            var v = 0L
            for (i in 0 until n) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }

        private val CRC8 = IntArray(256) { i ->
            var c = i
            repeat(8) { c = if (c and 0x80 != 0) ((c shl 1) xor 0x07) and 0xFF else (c shl 1) and 0xFF }
            c
        }

        fun crc8(b: ByteArray, at: Int, n: Int): Int {
            var c = 0
            for (k in at until at + n) c = CRC8[c xor (b[k].toInt() and 0xFF)]
            return c
        }

        /** True for "fLaC" at the start, or after an ID3v2 tag. */
        fun sniff(head: ByteArray): Boolean = head.ascii(0, 4) == "fLaC" || head.ascii(0, 3) == "ID3"
    }
}
