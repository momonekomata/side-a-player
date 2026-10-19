package io.github.momonekomata.sidea.sources

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class PictureReaderTest {
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 9, 9)

    private fun flacPictureBlock(type: Int, mime: String, data: ByteArray): ByteArray {
        val b = ByteBuffer.allocate(32 + mime.length + data.size)
        b.putInt(type).putInt(mime.length).put(mime.toByteArray()).putInt(0)
        b.putInt(1).putInt(1).putInt(24).putInt(0).putInt(data.size).put(data)
        return b.array()
    }

    private fun flacBlock(type: Int, last: Boolean, body: ByteArray) =
        byteArrayOf(((if (last) 0x80 else 0) or type).toByte(), (body.size shr 16).toByte(), (body.size shr 8).toByte(), body.size.toByte()) + body

    @Test
    fun `flac prefers the front cover`() {
        val file = "fLaC".toByteArray() + flacBlock(0, false, ByteArray(34)) +
            flacBlock(6, false, flacPictureBlock(4, "image/png", png)) +
            flacBlock(6, true, flacPictureBlock(3, "image/jpeg", jpeg))
        val p = PictureReader.read(ByteArrayAccess(file))!!
        assertEquals("image/jpeg", p.mime)
        assertArrayEquals(jpeg, p.data)
    }

    @Test
    fun `id3 apic`() {
        val frame = byteArrayOf(0) + "image/jpeg".toByteArray() + byteArrayOf(0, 3) + "cover".toByteArray() + byteArrayOf(0) + jpeg
        val header = "APIC".toByteArray() + ByteBuffer.allocate(4).putInt(frame.size).array() + byteArrayOf(0, 0)
        val body = header + frame
        val size = body.size
        val tag = "ID3".toByteArray() + byteArrayOf(3, 0, 0, (size shr 21 and 0x7F).toByte(), (size shr 14 and 0x7F).toByte(), (size shr 7 and 0x7F).toByte(), (size and 0x7F).toByte()) + body
        val p = PictureReader.read(ByteArrayAccess(tag + ByteArray(32)))!!
        assertArrayEquals(jpeg, p.data)
    }

    @Test
    fun `folder cover names`() {
        assertEquals("Cover.jpg", pickFolderCover(listOf("01.flac", "back.jpg", "Cover.jpg", "folder.png")))
        assertEquals("folder.png", pickFolderCover(listOf("01.flac", "folder.png")))
        assertEquals("scan.jpg", pickFolderCover(listOf("01.flac", "scan.jpg")))
        assertEquals(null, pickFolderCover(listOf("01.flac", "a.jpg", "b.jpg")))
    }
}
