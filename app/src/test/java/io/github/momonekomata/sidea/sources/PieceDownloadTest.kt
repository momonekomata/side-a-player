package io.github.momonekomata.sidea.sources

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.io.File
import java.io.IOException
import kotlin.random.Random

class PieceDownloadTest {
    private val data = Random(7).nextBytes(13 * 1024 * 1024 + 12345)

    private fun readAll(src: GrowingFileDataSource): ByteArray {
        val out = ByteArray(data.size)
        var pos = 0
        while (pos < out.size) {
            val n = src.readAt(pos.toLong(), out, pos, minOf(300_000, out.size - pos))
            if (n <= 0) break
            pos += n
        }
        src.close()
        return out
    }

    private fun file(name: String) = File.createTempFile(name, ".bin").also { it.delete() }

    @Test
    fun parallelPiecesArriveIntactDespiteDroppedConnections() {
        var drops = 0
        val src = GrowingFileDataSource(file("pieces"), data.size.toLong(), fetch = { error("not used") }, ranged = r@{ from, to, sink ->
            var pos = from.toInt()
            val rnd = Random(from)
            while (pos < to) {
                val n = minOf(rnd.nextInt(1, 200_000), (to - pos).toInt())
                // Every so often a connection dies mid-piece; the piece must resume, not restart wrong.
                if (rnd.nextInt(40) == 0 && synchronized(this) { drops++ < 5 }) throw IOException("dropped")
                if (!sink(data.copyOfRange(pos, pos + n), n)) return@r
                pos += n
            }
        })
        assertArrayEquals(data, readAll(src))
    }

    @Test
    fun aServerWithoutRangesFallsBackToOneDownload() {
        val src = GrowingFileDataSource(file("whole"), data.size.toLong(), fetch = f@{ sink ->
            var pos = 0
            while (pos < data.size) {
                val n = minOf(65_536, data.size - pos)
                if (!sink(data.copyOfRange(pos, pos + n), n)) return@f
                pos += n
            }
        }, ranged = { _, _, _ -> throw RangesUnsupported() })
        assertArrayEquals(data, readAll(src))
    }
}
