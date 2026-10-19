package io.github.momonekomata.sidea.sources

import java.util.Base64

/** An embedded cover image, still encoded (JPEG or PNG). */
class Picture(val data: ByteArray, val mime: String)

/**
 * Pulls the front cover out of a file: FLAC PICTURE blocks (and the Vorbis-comment
 * METADATA_BLOCK_PICTURE form), ID3 APIC/PIC (MP3, DSF, AIFF, WAV), and MP4 `covr`.
 * Separate from [TagReader] because pictures are big: they're read for the song playing, not for
 * every row of a folder.
 */
object PictureReader {
    private const val FRONT_COVER = 3

    fun read(ra: RandomAccess): Picture? {
        val head = ra.readUpTo(0, 12)
        if (head.size < 12) return null
        return when {
            head.ascii(0, 4) == "fLaC" -> flac(ra)
            head.ascii(0, 3) == "ID3" -> id3(ra, 0)
            head.ascii(4, 4) == "ftyp" -> mp4(ra)
            head.ascii(0, 4) == "RIFF" && head.ascii(8, 4) == "WAVE" -> chunkId3(ra, littleEndian = true)
            head.ascii(0, 4) == "FORM" -> chunkId3(ra, littleEndian = false)
            head.ascii(0, 4) == "DSD " -> ra.readFully(0, 28).u64le(20).takeIf { it > 0 }?.let { id3(ra, it) }
            else -> null
        }
    }

    private fun flac(ra: RandomAccess): Picture? {
        var pos = 4L
        var fallback: Picture? = null
        while (true) {
            val h = ra.readFully(pos, 4)
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = h.u24be(1)
            when (type) {
                6 -> {
                    val (picType, pic) = flacPicture(ra.readFully(pos + 4, len)) ?: (null to null)
                    if (picType == FRONT_COVER) return pic
                    if (fallback == null) fallback = pic
                }
                4 -> vorbisPicture(ra.readFully(pos + 4, len))?.let { if (fallback == null) fallback = it }
            }
            if (last) return fallback
            pos += 4 + len
        }
    }

    /** FLAC PICTURE block body → (picture type, picture). */
    internal fun flacPicture(b: ByteArray): Pair<Int, Picture>? {
        if (b.size < 32) return null
        var p = 0
        val type = b.u32be(p).toInt(); p += 4
        val mimeLen = b.u32be(p).toInt(); p += 4
        val mime = b.ascii(p, mimeLen); p += mimeLen
        val descLen = b.u32be(p).toInt(); p += 4 + descLen
        p += 16 // width, height, depth, colours
        val dataLen = b.u32be(p).toInt(); p += 4
        if (dataLen <= 0 || p + dataLen > b.size) return null
        return type to Picture(b.copyOfRange(p, p + dataLen), mime)
    }

    private fun vorbisPicture(b: ByteArray): Picture? {
        var p = 0
        p += 4 + b.u32le(p).toInt()
        val count = b.u32le(p).toInt(); p += 4
        repeat(count) {
            if (p + 4 > b.size) return null
            val len = b.u32le(p).toInt(); p += 4
            if (p + len > b.size) return null
            val key = "METADATA_BLOCK_PICTURE="
            if (len > key.length && b.ascii(p, key.length).equals(key, ignoreCase = true)) {
                val decoded = runCatching { Base64.getDecoder().decode(String(b, p + key.length, len - key.length, Charsets.US_ASCII)) }.getOrNull()
                decoded?.let { flacPicture(it) }?.let { return it.second }
            }
            p += len
        }
        return null
    }

    fun id3(ra: RandomAccess, at: Long): Picture? {
        val frames = TagReader.id3Frames(ra, at) ?: return null
        val pics = frames.mapNotNull { (id, d) ->
            when (id) {
                "APIC" -> apic(d)
                "PIC" -> pic22(d)
                else -> null
            }
        }
        return (pics.firstOrNull { it.first == FRONT_COVER } ?: pics.firstOrNull())?.second
    }

    /** APIC: encoding, NUL-terminated MIME, picture type, description (in the encoding), data. */
    private fun apic(d: ByteArray): Pair<Int, Picture>? {
        if (d.size < 4) return null
        val enc = d[0].toInt()
        var p = 1
        while (p < d.size && d[p].toInt() != 0) p++
        val mime = String(d, 1, p - 1, Charsets.ISO_8859_1).ifBlank { "image/jpeg" }
        p++
        if (p >= d.size) return null
        val type = d[p].toInt() and 0xFF; p++
        p = skipText(d, p, enc)
        if (p >= d.size) return null
        return type to Picture(d.copyOfRange(p, d.size), if ('/' in mime) mime else "image/${mime.lowercase()}")
    }

    /** ID3v2.2 PIC: encoding, 3-letter format, picture type, description, data. */
    private fun pic22(d: ByteArray): Pair<Int, Picture>? {
        if (d.size < 6) return null
        val fmt = d.ascii(1, 3).lowercase()
        val type = d[4].toInt() and 0xFF
        val p = skipText(d, 5, d[0].toInt())
        if (p >= d.size) return null
        return type to Picture(d.copyOfRange(p, d.size), if (fmt == "png") "image/png" else "image/jpeg")
    }

    private fun skipText(d: ByteArray, from: Int, enc: Int): Int {
        var p = from
        if (enc == 1 || enc == 2) {
            while (p + 1 < d.size && !(d[p].toInt() == 0 && d[p + 1].toInt() == 0)) p += 2
            return p + 2
        }
        while (p < d.size && d[p].toInt() != 0) p++
        return p + 1
    }

    private fun mp4(ra: RandomAccess): Picture? {
        val moov = TagReader.findAtom(ra, 0, ra.size.takeIf { it > 0 } ?: Long.MAX_VALUE, "moov") ?: return null
        val udta = TagReader.findAtom(ra, moov.first, moov.second, "udta") ?: return null
        val meta = TagReader.findAtom(ra, udta.first, udta.second, "meta") ?: return null
        val ilst = TagReader.findAtom(ra, meta.first + 4, meta.second, "ilst") ?: return null
        val covr = TagReader.findAtom(ra, ilst.first, ilst.second, "covr") ?: return null
        val data = TagReader.findAtom(ra, covr.first, covr.second, "data") ?: return null
        val head = ra.readFully(data.first, 8)
        val type = head.u32be(0).toInt() and 0xFFFFFF
        val bytes = ra.readFully(data.first + 8, (data.second - data.first - 8).toInt())
        return Picture(bytes, if (type == 14) "image/png" else "image/jpeg")
    }

    /** WAV ('id3 ' chunk, little-endian sizes) and AIFF ('ID3 ' chunk, big-endian). */
    private fun chunkId3(ra: RandomAccess, littleEndian: Boolean): Picture? {
        var pos = 12L
        while (true) {
            val h = ra.readUpTo(pos, 8)
            if (h.size < 8) return null
            val len = if (littleEndian) h.u32le(4) else h.u32be(4)
            if (h.ascii(0, 4).equals("id3 ", ignoreCase = true)) return id3(ra, pos + 8)
            pos += 8 + len + (len and 1)
        }
    }
}
