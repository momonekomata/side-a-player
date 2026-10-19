package io.github.momonekomata.sidea.sources

import android.content.Context
import android.media.MediaDataSource
import io.github.momonekomata.sidea.audio.PlayableSource
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * Songs from a NAS are downloaded once, start to finish in one go, and played while the
 * download runs; finished files stay for replays. One continuous transfer keeps up with even
 * large hi-res files, where reading on demand in pieces stalls whenever a piece is slow.
 */
object MediaCache {
    private const val LIMIT = 2L * 1024 * 1024 * 1024

    fun file(context: Context, key: String, extension: String): File {
        val dir = File(context.cacheDir, "media").apply { mkdirs() }
        val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.$extension")
    }

    /** The cached file when it's complete, else a stream fed by [fetch] while it downloads. */
    fun open(file: File, size: Long, fetch: ((ByteArray, Int) -> Boolean) -> Unit): PlayableSource {
        if (size > 0 && file.length() == size) {
            file.setLastModified(System.currentTimeMillis())
            return PlayableSource.File(file.path)
        }
        trim(file.parentFile!!, keep = file)
        return PlayableSource.Stream(GrowingFileDataSource(file, size, fetch))
    }

    /** Keeps the cache under ~2 GB by dropping the least recently played songs. */
    private fun trim(dir: File, keep: File) {
        val files = dir.listFiles()?.filter { it != keep && !it.name.endsWith(".part") }?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < LIMIT) break
            total -= f.length()
            f.delete()
        }
    }
}

/**
 * A data source over a file that a background thread is still writing. Reads past the
 * downloaded part wait for it, so playback starts after the first few hundred kilobytes.
 */
class GrowingFileDataSource(
    private val file: File,
    private val expectedSize: Long,
    private val fetch: ((ByteArray, Int) -> Boolean) -> Unit,
) : MediaDataSource() {
    private val lock = Object()
    private var written = 0L
    private var done = false
    private var failure: Throwable? = null
    @Volatile private var closed = false
    private val partial = File(file.path + ".part")
    private val reader: RandomAccessFile

    init {
        partial.delete()
        RandomAccessFile(partial, "rw").close()
        reader = RandomAccessFile(partial, "r")
        Thread({
            try {
                RandomAccessFile(partial, "rw").use { out ->
                    fetch(sink@{ buf, n ->
                        if (closed) return@sink false
                        out.write(buf, 0, n)
                        synchronized(lock) { written += n; lock.notifyAll() }
                        true
                    })
                }
                if (!closed) partial.renameTo(file)
            } catch (t: Throwable) {
                failure = t
            } finally {
                synchronized(lock) { done = true; lock.notifyAll() }
            }
        }, "SideA-ftps").start()
    }

    override fun getSize(): Long = expectedSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (expectedSize in 0..position) return -1
        val end = if (expectedSize < 0) Long.MAX_VALUE else expectedSize // unknown size: wait for the end
        synchronized(lock) {
            while (!done && !closed && written < position + size && written < end) lock.wait(200)
            failure?.let { if (written <= position) throw IOException("Download failed", it) }
            if (written <= position) return -1
        }
        val n = minOf(size.toLong(), written - position).toInt()
        // The reader's file handle survives the rename on Linux, so this stays valid.
        reader.seek(position)
        return reader.read(buffer, offset, n)
    }

    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        runCatching { reader.close() }
    }
}

