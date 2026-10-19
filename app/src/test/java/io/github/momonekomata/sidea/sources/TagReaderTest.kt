package io.github.momonekomata.sidea.sources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class TagReaderTest {
    private class Bytes {
        val out = ByteArrayOutputStream()
        fun ascii(s: String) = apply { out.write(s.toByteArray(Charsets.ISO_8859_1)) }
        fun raw(vararg b: Int) = apply { b.forEach { out.write(it) } }
        fun bytes(b: ByteArray) = apply { out.write(b) }
        fun u32be(v: Long) = raw((v shr 24).toInt() and 0xFF, (v shr 16).toInt() and 0xFF, (v shr 8).toInt() and 0xFF, v.toInt() and 0xFF)
        fun u32le(v: Long) = raw(v.toInt() and 0xFF, (v shr 8).toInt() and 0xFF, (v shr 16).toInt() and 0xFF, (v shr 24).toInt() and 0xFF)
        fun u64le(v: Long) = u32le(v and 0xFFFFFFFFL).u32le(v ushr 32)
        fun syncsafe(v: Int) = raw((v shr 21) and 0x7F, (v shr 14) and 0x7F, (v shr 7) and 0x7F, v and 0x7F)
        fun get(): ByteArray = out.toByteArray()
        val size get() = out.size()
    }

    private fun vorbis(vararg fields: String): ByteArray {
        val b = Bytes().u32le(4).ascii("test").u32le(fields.size.toLong())
        fields.forEach { val f = it.toByteArray(); b.u32le(f.size.toLong()).bytes(f) }
        return b.get()
    }

    @Test
    fun `flac vorbis comment after streaminfo`() {
        val comment = vorbis("TITLE=Can You Love Me Tonight?", "ARTIST=Kaito", "album=Side A", "TRACKNUMBER=3/12", "LYRICS=[00:01.00]hello")
        val file = Bytes().ascii("fLaC")
            .raw(0, 0, 0, 34).bytes(ByteArray(34)) // STREAMINFO
            .raw(0x84, (comment.size shr 16) and 0xFF, (comment.size shr 8) and 0xFF, comment.size and 0xFF).bytes(comment)
            .get()
        val t = TagReader.read(ByteArrayAccess(file))!!
        assertEquals("Can You Love Me Tonight?", t.title)
        assertEquals("Kaito", t.artist)
        assertEquals("Side A", t.album)
        assertEquals(3, t.trackNumber)
        assertEquals("[00:01.00]hello", t.lyrics)
    }

    private fun id3Frame(id: String, data: ByteArray, v4: Boolean): ByteArray {
        val b = Bytes().ascii(id)
        if (v4) b.syncsafe(data.size) else b.u32be(data.size.toLong())
        return b.raw(0, 0).bytes(data).get()
    }

    private fun id3(v4: Boolean, vararg frames: ByteArray): ByteArray {
        val body = Bytes().apply { frames.forEach { bytes(it) } }.get()
        return Bytes().ascii("ID3").raw(if (v4) 4 else 3, 0, 0).syncsafe(body.size).bytes(body).get()
    }

    @Test
    fun `id3v2_3 text frames and utf-16 lyrics`() {
        val title = byteArrayOf(0) + "Plain Title".toByteArray(Charsets.ISO_8859_1)
        val artist = byteArrayOf(1) + "アーティスト".toByteArray(Charsets.UTF_16) // with BOM
        val lyrics = byteArrayOf(1) + "eng".toByteArray() + "".toByteArray(Charsets.UTF_16) + byteArrayOf(0, 0) +
            "line one\nline two".toByteArray(Charsets.UTF_16)
        val file = id3(false, id3Frame("TIT2", title, false), id3Frame("TPE1", artist, false), id3Frame("USLT", lyrics, false)) + ByteArray(64)
        val t = TagReader.read(ByteArrayAccess(file))!!
        assertEquals("Plain Title", t.title)
        assertEquals("アーティスト", t.artist)
        assertEquals("line one\nline two", t.lyrics)
    }

    @Test
    fun `id3v2_4 utf-8 with syncsafe frame sizes`() {
        val file = id3(true, id3Frame("TIT2", byteArrayOf(3) + "สากล 90's".toByteArray(), true), id3Frame("TRCK", byteArrayOf(3) + "7".toByteArray(), true)) + ByteArray(16)
        val t = TagReader.read(ByteArrayAccess(file))!!
        assertEquals("สากล 90's", t.title)
        assertEquals(7, t.trackNumber)
    }

    private fun atom(type: String, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { a, b -> a + b }
        return Bytes().u32be((8 + body.size).toLong()).ascii(type).bytes(body).get()
    }

    private fun dataAtom(value: ByteArray, type: Int = 1) = Bytes().u32be((16 + value.size).toLong()).ascii("data").u32be(type.toLong()).u32be(0).bytes(value).get()

    @Test
    fun `mp4 ilst items`() {
        val ilst = atom(
            "ilst",
            atom("©nam", dataAtom("M4A Song".toByteArray())),
            atom("©ART", dataAtom("Band".toByteArray())),
            atom("trkn", dataAtom(byteArrayOf(0, 0, 0, 5, 0, 10, 0, 0), type = 0)),
            atom("©lyr", dataAtom("la la".toByteArray())),
        )
        val meta = Bytes().u32be((12 + ilst.size).toLong()).ascii("meta").u32be(0).bytes(ilst).get()
        val file = atom("ftyp", "M4A ".toByteArray()) + atom("mdat", ByteArray(100)) + atom("moov", atom("udta", meta))
        val t = TagReader.read(ByteArrayAccess(file))!!
        assertEquals("M4A Song", t.title)
        assertEquals("Band", t.artist)
        assertEquals(5, t.trackNumber)
        assertEquals("la la", t.lyrics)
    }

    @Test
    fun `dsf points at a trailing id3 tag`() {
        val tag = id3(false, id3Frame("TIT2", byteArrayOf(0) + "DSD Track".toByteArray(), false))
        val audio = ByteArray(200)
        val header = Bytes().ascii("DSD ").u64le(28).u64le(0).u64le(28L + audio.size).get()
        val t = TagReader.read(ByteArrayAccess(header + audio + tag))!!
        assertEquals("DSD Track", t.title)
    }

    @Test
    fun `wav list info`() {
        val info = Bytes().ascii("INFO").ascii("INAM").u32le(6).ascii("Wavy\u0000\u0000").ascii("IART").u32le(3).ascii("Me\u0000").raw(0).get()
        val data = Bytes().ascii("data").u32le(4).raw(0, 0, 0, 0).get()
        val list = Bytes().ascii("LIST").u32le(info.size.toLong()).bytes(info).get()
        val body = Bytes().ascii("WAVE").bytes(data).bytes(list).get()
        val file = Bytes().ascii("RIFF").u32le(body.size.toLong()).bytes(body).get()
        val t = TagReader.read(ByteArrayAccess(file))!!
        assertEquals("Wavy", t.title)
        assertEquals("Me", t.artist)
    }

    @Test
    fun `unknown file has no tags`() {
        assertNull(TagReader.read(ByteArrayAccess(ByteArray(64) { 7 })))
    }
}
