package io.github.momonekomata.sidea.sources

import java.nio.charset.Charset

data class Tags(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val trackNumber: Int? = null,
    /** Embedded lyrics, plain or LRC-timed. */
    val lyrics: String? = null,
) {
    fun isEmpty() = title == null && artist == null && album == null && lyrics == null
}

/**
 * Reads the tags Side A needs (title, artist, album, track number, lyrics) straight from the
 * file: FLAC/Ogg/Opus Vorbis comments, ID3v2 (MP3, DSF, AIFF, WAV), MP4/M4A atoms, WAV INFO and
 * DSDIFF. Android's MediaMetadataRetriever has no lyrics and doesn't open DSD at all, and this
 * only touches the few kilobytes the tags live in, which matters over a NAS.
 */
object TagReader {
    fun read(ra: RandomAccess): Tags? {
        val head = ra.readUpTo(0, 12)
        if (head.size < 12) return null
        val tags = when {
            head.ascii(0, 4) == "fLaC" -> flac(ra)
            head.ascii(0, 3) == "ID3" -> id3(ra, 0)
            head.ascii(0, 4) == "OggS" -> ogg(ra)
            head.ascii(4, 4) == "ftyp" -> mp4(ra)
            head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WAVE" -> riff(ra)
            head.ascii(0, 4) == "FORM" && (head.ascii(8, 4) == "AIFF" || head.ascii(8, 4) == "AIFC") -> aiff(ra)
            head.ascii(0, 4) == "DSD " -> dsf(ra)
            head.ascii(0, 4) == "FRM8" -> dff(ra)
            else -> null
        }
        return tags?.takeUnless { it.isEmpty() }
    }

    // ---- FLAC ----

    private fun flac(ra: RandomAccess): Tags? {
        var pos = 4L
        while (true) {
            val h = ra.readFully(pos, 4)
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = h.u24be(1)
            if (type == 4) return vorbisComment(ra.readFully(pos + 4, len), 0)
            if (last) return null
            pos += 4 + len
        }
    }

    private fun vorbisComment(b: ByteArray, start: Int): Tags {
        var p = start
        val vendorLen = b.u32le(p).toInt(); p += 4 + vendorLen
        val count = b.u32le(p).toInt(); p += 4
        val fields = mutableMapOf<String, String>()
        repeat(count) {
            if (p + 4 > b.size) return@repeat
            val len = b.u32le(p).toInt(); p += 4
            if (len < 0 || p + len > b.size) return@repeat
            val s = String(b, p, len, Charsets.UTF_8); p += len
            val eq = s.indexOf('=')
            if (eq > 0) fields.putIfAbsent(s.substring(0, eq).uppercase(), s.substring(eq + 1))
        }
        return Tags(
            title = fields["TITLE"],
            artist = fields["ARTIST"],
            album = fields["ALBUM"],
            albumArtist = fields["ALBUMARTIST"] ?: fields["ALBUM ARTIST"],
            trackNumber = fields["TRACKNUMBER"]?.substringBefore('/')?.trim()?.toIntOrNull(),
            lyrics = fields["LYRICS"] ?: fields["UNSYNCEDLYRICS"] ?: fields["SYNCEDLYRICS"],
        )
    }

    // ---- Ogg Vorbis / Opus: the comment packet is the second packet ----

    private fun ogg(ra: RandomAccess): Tags? {
        // Reassemble packets from the first pages; comments rarely span more than a few.
        val packet = java.io.ByteArrayOutputStream()
        var pos = 0L
        var packetsDone = 0
        while (pos < 512 * 1024) {
            val h = ra.readUpTo(pos, 27)
            if (h.size < 27 || h.ascii(0, 4) != "OggS") return null
            val segs = h[26].toInt() and 0xFF
            val table = ra.readFully(pos + 27, segs)
            var dataPos = pos + 27 + segs
            for (i in 0 until segs) {
                val l = table[i].toInt() and 0xFF
                if (packetsDone == 1) packet.write(ra.readFully(dataPos, l))
                dataPos += l
                if (l < 255) {
                    if (packetsDone == 1) {
                        val b = packet.toByteArray()
                        return when {
                            b.size > 7 && b[0].toInt() == 3 && b.ascii(1, 6) == "vorbis" -> vorbisComment(b, 7)
                            b.size > 8 && b.ascii(0, 8) == "OpusTags" -> vorbisComment(b, 8)
                            else -> null
                        }
                    }
                    packetsDone++
                }
            }
            pos = dataPos
        }
        return null
    }

    // ---- ID3v2.2 / 2.3 / 2.4 ----

    /** Frames of the ID3v2 tag at [at], with v2.2 ids mapped to their v2.3 names (PIC stays "PIC"). */
    internal fun id3Frames(ra: RandomAccess, at: Long): List<Pair<String, ByteArray>>? {
        val h = ra.readFully(at, 10)
        if (h.ascii(0, 3) != "ID3") return null
        val major = h[3].toInt()
        val flags = h[5].toInt()
        val size = h.syncsafe(6)
        val body = ra.readUpTo(at + 10, size)
        var p = 0
        if (major >= 3 && flags and 0x40 != 0) { // extended header
            p += if (major == 4) body.syncsafe(0) else 4 + body.u32be(0).toInt()
        }
        val idLen = if (major == 2) 3 else 4
        val hdrLen = if (major == 2) 6 else 10
        val frames = mutableListOf<Pair<String, ByteArray>>()
        while (p + hdrLen <= body.size) {
            val id = body.ascii(p, idLen)
            if (id[0] == '\u0000') break
            val len = when (major) {
                2 -> body.u24be(p + 3)
                4 -> body.syncsafe(p + 4)
                else -> body.u32be(p + 4).toInt()
            }
            val start = p + hdrLen
            if (len <= 0 || start + len > body.size) break
            frames += (V22[id] ?: id) to body.copyOfRange(start, start + len)
            p = start + len
        }
        return frames
    }

    fun id3(ra: RandomAccess, at: Long): Tags? {
        val f = mutableMapOf<String, String>()
        for ((key, data) in id3Frames(ra, at) ?: return null) {
            when {
                key == "USLT" -> f.putIfAbsent(key, uslt(data))
                key.startsWith("T") -> f.putIfAbsent(key, id3Text(data))
            }
        }
        return Tags(
            title = f["TIT2"],
            artist = f["TPE1"],
            album = f["TALB"],
            albumArtist = f["TPE2"],
            trackNumber = f["TRCK"]?.substringBefore('/')?.trim()?.toIntOrNull(),
            lyrics = f["USLT"],
        )
    }

    private val V22 = mapOf("TT2" to "TIT2", "TP1" to "TPE1", "TAL" to "TALB", "TP2" to "TPE2", "TRK" to "TRCK", "ULT" to "USLT")

    internal fun charset(enc: Int): Charset = when (enc) {
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> Charsets.ISO_8859_1
    }

    private fun id3Text(d: ByteArray): String {
        if (d.isEmpty()) return ""
        // v2.4 separates multiple values with NUL; the first is enough here.
        return String(d, 1, d.size - 1, charset(d[0].toInt())).trimEnd('\u0000').substringBefore('\u0000').trim()
    }

    /** USLT: encoding, 3-byte language, NUL-terminated description, then the lyrics. */
    private fun uslt(d: ByteArray): String {
        if (d.size < 5) return ""
        val enc = d[0].toInt()
        val wide = enc == 1 || enc == 2
        var p = 4
        if (wide) {
            while (p + 1 < d.size && !(d[p].toInt() == 0 && d[p + 1].toInt() == 0)) p += 2
            p += 2
        } else {
            while (p < d.size && d[p].toInt() != 0) p++
            p += 1
        }
        if (p >= d.size) return ""
        return String(d, p, d.size - p, charset(enc)).trimEnd('\u0000').removePrefix("\uFEFF").trim()
    }

    // ---- MP4 / M4A ----

    private fun mp4(ra: RandomAccess): Tags? {
        val moov = findAtom(ra, 0, ra.size.takeIf { it > 0 } ?: Long.MAX_VALUE, "moov") ?: return null
        val udta = findAtom(ra, moov.first, moov.second, "udta") ?: return null
        val meta = findAtom(ra, udta.first, udta.second, "meta") ?: return null
        val ilst = findAtom(ra, meta.first + 4, meta.second, "ilst") ?: return null // meta is a full box
        val body = ra.readFully(ilst.first, (ilst.second - ilst.first).toInt())
        val f = mutableMapOf<String, String>()
        var trackNo: Int? = null
        var p = 0
        while (p + 8 <= body.size) {
            val len = body.u32be(p).toInt()
            if (len < 8 || p + len > body.size) break
            val name = String(body, p + 4, 4, Charsets.ISO_8859_1)
            // Each item holds a 'data' atom: size, 'data', type(4), locale(4), value.
            val d = p + 8
            if (d + 16 <= p + len && body.ascii(d + 4, 4) == "data") {
                val dLen = body.u32be(d).toInt()
                val value = body.copyOfRange(d + 16, minOf(d + dLen, p + len))
                if (name == "trkn") {
                    if (value.size >= 4) trackNo = ((value[2].toInt() and 0xFF) shl 8) or (value[3].toInt() and 0xFF)
                } else {
                    f[name] = String(value, Charsets.UTF_8)
                }
            }
            p += len
        }
        return Tags(
            title = f["\u00A9nam"],
            artist = f["\u00A9ART"],
            album = f["\u00A9alb"],
            albumArtist = f["aART"],
            trackNumber = trackNo?.takeIf { it > 0 },
            lyrics = f["\u00A9lyr"],
        )
    }

    /** Returns (content start, end) of the first child atom named [type] within [from, to). */
    internal fun findAtom(ra: RandomAccess, from: Long, to: Long, type: String): Pair<Long, Long>? {
        var pos = from
        while (pos + 8 <= to) {
            val h = ra.readUpTo(pos, 16)
            if (h.size < 8) return null
            var len = h.u32be(0)
            var hdr = 8
            if (len == 1L && h.size >= 16) { len = h.u64be(8); hdr = 16 }
            if (len == 0L) len = to - pos // extends to the end
            if (len < hdr) return null
            if (h.ascii(4, 4) == type) return (pos + hdr) to (pos + len)
            pos += len
        }
        return null
    }

    // ---- WAV / AIFF / DSF / DFF: an ID3 chunk or a native one ----

    private fun riff(ra: RandomAccess): Tags? {
        var pos = 12L
        var info: Tags? = null
        while (true) {
            val h = ra.readUpTo(pos, 8)
            if (h.size < 8) break
            val id = h.ascii(0, 4)
            val len = h.u32le(4)
            if (id.equals("id3 ", ignoreCase = true)) id3(ra, pos + 8)?.let { return it }
            if (id == "LIST" && len >= 4 && ra.readFully(pos + 8, 4).ascii(0, 4) == "INFO") {
                info = riffInfo(ra.readFully(pos + 12, (len - 4).toInt()))
            }
            pos += 8 + len + (len and 1)
        }
        return info
    }

    private fun riffInfo(b: ByteArray): Tags {
        val f = mutableMapOf<String, String>()
        var p = 0
        while (p + 8 <= b.size) {
            val id = b.ascii(p, 4)
            val len = b.u32le(p + 4).toInt()
            if (len < 0 || p + 8 + len > b.size) break
            f[id] = String(b, p + 8, len, Charsets.UTF_8).trimEnd('\u0000').trim()
            p += 8 + len + (len and 1)
        }
        return Tags(title = f["INAM"], artist = f["IART"], album = f["IPRD"], trackNumber = f["ITRK"]?.toIntOrNull())
    }

    private fun aiff(ra: RandomAccess): Tags? {
        var pos = 12L
        while (true) {
            val h = ra.readUpTo(pos, 8)
            if (h.size < 8) return null
            val len = h.u32be(4)
            if (h.ascii(0, 4) == "ID3 ") return id3(ra, pos + 8)
            pos += 8 + len + (len and 1)
        }
    }

    /** DSF keeps an ID3v2 tag at the offset stored in its header. */
    private fun dsf(ra: RandomAccess): Tags? {
        val h = ra.readFully(0, 28)
        val ptr = h.u64le(20)
        return if (ptr > 0) id3(ra, ptr) else null
    }

    /** DSDIFF: an (unofficial but common) 'ID3 ' chunk, else DIIN's DITI/DIAR. */
    private fun dff(ra: RandomAccess): Tags? {
        var pos = 16L
        var title: String? = null
        var artist: String? = null
        while (true) {
            val h = ra.readUpTo(pos, 12)
            if (h.size < 12) break
            val id = h.ascii(0, 4)
            val len = h.u64be(4)
            if (id == "ID3 ") id3(ra, pos + 12)?.let { return it }
            if (id == "DIIN") {
                var p = pos + 12
                val end = pos + 12 + len
                while (p + 12 <= end) {
                    val c = ra.readFully(p, 12)
                    val cLen = c.u64be(4)
                    val cid = c.ascii(0, 4)
                    if (cid == "DITI" || cid == "DIAR") {
                        val b = ra.readFully(p + 12, cLen.toInt())
                        val n = b.u32be(0).toInt().coerceAtMost(b.size - 4)
                        val s = String(b, 4, n, Charsets.ISO_8859_1).trim()
                        if (cid == "DITI") title = s else artist = s
                    }
                    p += 12 + cLen + (cLen and 1)
                }
            }
            pos += 12 + len + (len and 1)
        }
        return Tags(title = title, artist = artist)
    }
}

// ---- byte helpers ----

internal fun ByteArray.ascii(at: Int, n: Int) = if (at + n <= size) String(this, at, n, Charsets.ISO_8859_1) else ""
internal fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
internal fun ByteArray.u24be(i: Int) = (u8(i) shl 16) or (u8(i + 1) shl 8) or u8(i + 2)
internal fun ByteArray.u32be(i: Int) = (u8(i).toLong() shl 24) or (u8(i + 1).toLong() shl 16) or (u8(i + 2).toLong() shl 8) or u8(i + 3).toLong()
internal fun ByteArray.u32le(i: Int) = (u8(i + 3).toLong() shl 24) or (u8(i + 2).toLong() shl 16) or (u8(i + 1).toLong() shl 8) or u8(i).toLong()
internal fun ByteArray.u64be(i: Int) = (u32be(i) shl 32) or u32be(i + 4)
internal fun ByteArray.u64le(i: Int) = (u32le(i + 4) shl 32) or u32le(i)
internal fun ByteArray.syncsafe(i: Int) = (u8(i) shl 21) or (u8(i + 1) shl 14) or (u8(i + 2) shl 7) or u8(i + 3)
