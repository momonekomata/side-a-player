package io.github.momonekomata.sidea.sources

/**
 * What's inside an audio file, for the Library's filters: [codec] as people say it ("FLAC",
 * "ALAC", "DSD", "MP3"…), [bits] null for lossy formats and 1 for DSD, [durationMs] 0 if unknown.
 */
data class AudioFormat(val codec: String, val sampleRate: Int, val bits: Int?, val channels: Int, val durationMs: Long) {
    val lossless: Boolean get() = codec in LOSSLESS
    /** DSD64, DSD128…, from the rate as a multiple of 44.1 kHz. */
    val dsdRate: Int? get() = if (codec == "DSD" && sampleRate > 0) sampleRate / 44_100 else null

    companion object {
        val LOSSLESS = setOf("FLAC", "ALAC", "WAV", "AIFF", "DSD")
    }
}

/**
 * Reads the format from the file's headers only (a few kilobytes, or the MP4 box tree), so it's
 * as cheap over a NAS as reading the tags.
 */
object FormatProbe {
    fun probe(ra: RandomAccess, fileName: String = ""): AudioFormat? = runCatching {
        var head = ra.readUpTo(0, 12)
        if (head.size < 12) return null
        // An ID3 tag in front: skip it (MP3, and some taggers put one before FLAC or MP4).
        var start = 0L
        if (head.ascii(0, 3) == "ID3") {
            val h = ra.readFully(0, 10)
            start = 10L + h.syncsafe(6) + if (h[5].toInt() and 0x10 != 0) 10 else 0
            head = ra.readUpTo(start, 12)
            if (head.size < 12) return null
        }
        when {
            head.ascii(0, 4) == "fLaC" -> flac(ra, start)
            head.ascii(4, 4) == "ftyp" -> mp4(ra, start)
            head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WAVE" -> wav(ra)
            head.ascii(0, 4) == "FORM" && (head.ascii(8, 4) == "AIFF" || head.ascii(8, 4) == "AIFC") -> aiff(ra)
            head.ascii(0, 4) == "DSD " -> dsf(ra)
            head.ascii(0, 4) == "FRM8" -> dff(ra)
            head.ascii(0, 4) == "OggS" -> ogg(ra)
            (head.u8(0) == 0xFF && head.u8(1) and 0xE0 == 0xE0) || fileName.endsWith(".mp3", true) -> mp3(ra, start)
            else -> null
        }
    }.getOrNull()

    private fun flac(ra: RandomAccess, at: Long): AudioFormat {
        // STREAMINFO is always the first block: rate 20 bits, channels-1 3, bits-1 5, samples 36.
        val b = ra.readFully(at + 8, 34)
        val rate = (b.u8(10) shl 12) or (b.u8(11) shl 4) or (b.u8(12) shr 4)
        val channels = ((b.u8(12) shr 1) and 7) + 1
        val bits = (((b.u8(12) and 1) shl 4) or (b.u8(13) shr 4)) + 1
        val samples = ((b.u8(13) and 0xF).toLong() shl 32) or b.u32be(14)
        return AudioFormat("FLAC", rate, bits, channels, if (rate > 0) samples * 1000 / rate else 0)
    }

    private fun wav(ra: RandomAccess): AudioFormat? {
        var pos = 12L
        var rate = 0
        var channels = 0
        var bits = 0
        var bytesPerSec = 0L
        while (true) {
            val h = ra.readUpTo(pos, 8)
            if (h.size < 8) return null
            val id = h.ascii(0, 4)
            val len = h.u32le(4)
            when (id) {
                "fmt " -> {
                    val f = ra.readFully(pos + 8, 16)
                    channels = f.u8(2) or (f.u8(3) shl 8)
                    rate = f.u32le(4).toInt()
                    bytesPerSec = f.u32le(8)
                    bits = f.u8(14) or (f.u8(15) shl 8)
                }
                "data" -> {
                    // 0 or 0xFFFFFFFF from streaming writers: the length isn't known.
                    val ms = if (bytesPerSec > 0 && len in 1 until 0xFFFFFFFFL) len * 1000 / bytesPerSec else 0
                    return AudioFormat("WAV", rate, bits, channels, ms)
                }
            }
            pos += 8 + len + (len and 1)
        }
    }

    private fun aiff(ra: RandomAccess): AudioFormat? {
        var pos = 12L
        while (true) {
            val h = ra.readUpTo(pos, 8)
            if (h.size < 8) return null
            val len = h.u32be(4)
            if (h.ascii(0, 4) == "COMM") {
                val c = ra.readFully(pos + 8, 18)
                val channels = (c.u8(0) shl 8) or c.u8(1)
                val frames = c.u32be(2)
                val bits = (c.u8(6) shl 8) or c.u8(7)
                val rate = extended80(c, 8).toInt()
                return AudioFormat("AIFF", rate, bits, channels, if (rate > 0) frames * 1000 / rate else 0)
            }
            pos += 8 + len + (len and 1)
        }
    }

    /** AIFF's sample rate is an 80-bit IEEE extended float. */
    private fun extended80(b: ByteArray, at: Int): Double {
        val exp = ((b.u8(at) and 0x7F) shl 8) or b.u8(at + 1)
        val mantissa = b.u64be(at + 2)
        if (exp == 0 && mantissa == 0L) return 0.0
        return (mantissa ushr 11).toDouble() * Math.pow(2.0, (exp - 16383 - 52).toDouble())
    }

    private fun dsf(ra: RandomAccess): AudioFormat {
        // "DSD " chunk (28 bytes), then "fmt ": channels at +24, rate +28, bits +32, samples +36.
        val f = ra.readFully(28, 52)
        val channels = f.u32le(24).toInt()
        val rate = f.u32le(28).toInt()
        val samples = f.u64le(36)
        return AudioFormat("DSD", rate, 1, channels, if (rate > 0) samples * 1000 / rate else 0)
    }

    private fun dff(ra: RandomAccess): AudioFormat? {
        // FRM8 → PROP("SND ") holds FS (rate) and CHNL (channels); DSD's size gives the length.
        var pos = 16L
        var rate = 0
        var channels = 2
        var dsdBytes = 0L
        val end = 12 + ra.readFully(4, 8).u64be(0)
        while (pos + 12 <= end) {
            val h = ra.readUpTo(pos, 12)
            if (h.size < 12) break
            val id = h.ascii(0, 4)
            val len = h.u64be(4)
            when (id) {
                "PROP" -> {
                    var p = pos + 16
                    val propEnd = pos + 12 + len
                    while (p + 12 <= propEnd) {
                        val c = ra.readFully(p, 12)
                        val cid = c.ascii(0, 4)
                        val clen = c.u64be(4)
                        if (cid == "FS  ") rate = ra.readFully(p + 12, 4).u32be(0).toInt()
                        if (cid == "CHNL") channels = ra.readFully(p + 12, 2).let { (it.u8(0) shl 8) or it.u8(1) }
                        p += 12 + clen + (clen and 1)
                    }
                }
                "DSD " -> dsdBytes = len
            }
            pos += 12 + len + (len and 1)
        }
        if (rate == 0) return null
        val ms = if (channels > 0) dsdBytes * 8 * 1000 / (rate.toLong() * channels) else 0
        return AudioFormat("DSD", rate, 1, channels, ms)
    }

    private fun mp4(ra: RandomAccess, at: Long): AudioFormat? {
        val size = ra.size.takeIf { it > 0 } ?: Long.MAX_VALUE
        val moov = TagReader.findAtom(ra, at, size, "moov") ?: return null
        var t = moov.first
        // The first track with a sound handler.
        while (true) {
            val trak = TagReader.findAtom(ra, t, moov.second, "trak") ?: return null
            t = trak.second
            val mdia = TagReader.findAtom(ra, trak.first, trak.second, "mdia") ?: continue
            val hdlr = TagReader.findAtom(ra, mdia.first, mdia.second, "hdlr") ?: continue
            if (ra.readFully(hdlr.first + 8, 4).ascii(0, 4) != "soun") continue
            val mdhd = TagReader.findAtom(ra, mdia.first, mdia.second, "mdhd") ?: continue
            val md = ra.readFully(mdhd.first, 32)
            val (scale, dur) = if (md.u8(0) == 1) md.u32be(20) to md.u64be(24) else md.u32be(12) to md.u32be(16)
            val minf = TagReader.findAtom(ra, mdia.first, mdia.second, "minf") ?: continue
            val stbl = TagReader.findAtom(ra, minf.first, minf.second, "stbl") ?: continue
            val stsd = TagReader.findAtom(ra, stbl.first, stbl.second, "stsd") ?: continue
            // stsd: version/flags, count, then the sample entry: size, type, 6+2 bytes, 8 reserved,
            // channels, sample size, 4 bytes, rate as 16.16.
            val e = ra.readFully(stsd.first + 8, 36)
            val type = e.ascii(4, 4)
            val channels = (e.u8(24) shl 8) or e.u8(25)
            val bits = (e.u8(26) shl 8) or e.u8(27)
            var rate = (e.u32be(32) ushr 16).toInt()
            val codec = when (type) {
                "alac" -> "ALAC"
                "mp4a" -> "AAC"
                "fLaC" -> "FLAC"
                "Opus" -> "Opus"
                "ac-3", "ec-3" -> "Dolby"
                else -> type.uppercase().trim()
            }
            // Rates above 65535 don't fit 16.16; the track's own timescale is the rate then.
            if (rate == 0 || (codec == "ALAC" && scale > 65_535)) rate = scale.toInt()
            val ms = if (scale > 0) dur * 1000 / scale else 0
            return AudioFormat(codec, rate, if (codec == "AAC" || codec == "Opus" || codec == "Dolby") null else bits, channels, ms)
        }
    }

    private val MP3_RATES = arrayOf(intArrayOf(44_100, 48_000, 32_000), intArrayOf(22_050, 24_000, 16_000), intArrayOf(11_025, 12_000, 8_000))
    private val MP3_BITRATES_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val MP3_BITRATES_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)

    private fun mp3(ra: RandomAccess, at: Long): AudioFormat? {
        // The first frame header after any ID3 tag (skipping padding).
        val buf = ra.readUpTo(at, 4096)
        var i = 0
        while (i + 4 <= buf.size) {
            if (buf.u8(i) == 0xFF && buf.u8(i + 1) and 0xE0 == 0xE0) {
                val version = (buf.u8(i + 1) shr 3) and 3 // 3 = MPEG1, 2 = MPEG2, 0 = MPEG2.5
                val rateIdx = (buf.u8(i + 2) shr 2) and 3
                val brIdx = buf.u8(i + 2) shr 4
                if (version != 1 && rateIdx != 3 && brIdx in 1..14) {
                    val row = when (version) { 3 -> 0; 2 -> 1; else -> 2 }
                    val rate = MP3_RATES[row][rateIdx]
                    val kbps = if (version == 3) MP3_BITRATES_V1[brIdx] else MP3_BITRATES_V2[brIdx]
                    val channels = if ((buf.u8(i + 3) shr 6) == 3) 1 else 2
                    val bytes = (ra.size - at).coerceAtLeast(0)
                    // From the first frame's bit rate: exact for constant bit rate, close for VBR.
                    val ms = if (kbps > 0 && ra.size > 0) bytes * 8 / kbps else 0
                    return AudioFormat("MP3", rate, null, channels, ms)
                }
            }
            i++
        }
        return null
    }

    private fun ogg(ra: RandomAccess): AudioFormat? {
        // The first page's packet is the codec's identification header.
        val p = ra.readUpTo(0, 128)
        val segments = p.u8(26)
        val body = 27 + segments
        if (p.size < body + 20) return null
        return when {
            p.ascii(body + 1, 6) == "vorbis" -> AudioFormat("Vorbis", p.u32le(body + 12).toInt(), null, p.u8(body + 11), 0)
            p.ascii(body, 8) == "OpusHead" -> AudioFormat("Opus", 48_000, null, p.u8(body + 9), 0)
            p.ascii(body + 1, 4) == "FLAC" -> AudioFormat("FLAC", 0, null, 0, 0)
            else -> null
        }
    }
}
