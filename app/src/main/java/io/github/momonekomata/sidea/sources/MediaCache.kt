package io.github.momonekomata.sidea.sources

import android.content.Context
import android.media.MediaDataSource
import io.github.momonekomata.sidea.audio.PlayableSource
import io.github.momonekomata.sidea.audio.Refilling
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
 * downloaded part wait for it, so playback starts after the first few megabytes.
 *
 * When playback catches up with the download, it waits until a good stretch is in (or, on a
 * link slower than the song, enough that the rest arrives in time) instead of carrying on at
 * the download's pace: one clean pause rather than stutter. Downloads run one at a time, so
 * the next song's prefetch never splits the bandwidth with the song that's playing.
 */
class GrowingFileDataSource(
    private val file: File,
    private val expectedSize: Long,
    private val fetch: ((ByteArray, Int) -> Boolean) -> Unit,
) : MediaDataSource(), Refilling {
    private val lock = Object()
    private var written = 0L
    private var done = false
    private var failure: Throwable? = null
    @Volatile var closed = false
        private set
    @Volatile private var startedNs = 0L
    private val partial = File(file.path + ".part")
    private val reader: RandomAccessFile

    @Volatile override var bytesPerSecond = 0L
    @Volatile override var onStall: ((Boolean) -> Unit)? = null

    init {
        partial.delete()
        RandomAccessFile(partial, "rw").close()
        reader = RandomAccessFile(partial, "r")
        Downloads.enqueue(this)
        Thread({
            try {
                Downloads.awaitTurn(this)
                if (!closed) {
                    startedNs = System.nanoTime()
                    RandomAccessFile(partial, "rw").use { out ->
                        fetch(sink@{ buf, n ->
                            if (closed) return@sink false
                            out.write(buf, 0, n)
                            synchronized(lock) { written += n; lock.notifyAll() }
                            true
                        })
                    }
                    if (!closed) partial.renameTo(file)
                }
            } catch (t: Throwable) {
                failure = t
            } finally {
                Downloads.finished(this)
                synchronized(lock) { done = true; lock.notifyAll() }
            }
        }, "SideA-download").start()
    }

    override fun getSize(): Long = expectedSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (expectedSize in 0..position) return -1
        val end = if (expectedSize < 0) Long.MAX_VALUE else expectedSize // unknown size: wait for the end
        val want = minOf(position + size, end)
        synchronized(lock) {
            if (written < want && !done && !closed) {
                val target = minOf(end, refillTarget(want))
                val listener = onStall
                listener?.invoke(true)
                try {
                    while (!done && !closed && written < target) lock.wait(200)
                } finally {
                    listener?.invoke(false)
                }
            }
            failure?.let { if (written <= position) throw IOException("Download failed", it) }
            if (written <= position) return -1
        }
        val n = minOf(size.toLong(), written - position).toInt()
        // The reader's file handle survives the rename on Linux, so this stays valid.
        reader.seek(position)
        return reader.read(buffer, offset, n)
    }

    /** How far the download should get before playback goes on from [want]. Holds [lock]. */
    private fun refillTarget(want: Long): Long =
        refillTarget(want, expectedSize, bytesPerSecond, written, (System.nanoTime() - startedNs).takeIf { startedNs > 0 } ?: 0L)

    override fun close() {
        closed = true
        synchronized(lock) { lock.notifyAll() }
        Downloads.wake()
        runCatching { reader.close() }
    }
}

private const val MIN_AHEAD = 2L * 1024 * 1024
private const val AHEAD_SECONDS = 10

/**
 * Bytes a downloading file should hold before playing on from [want]: [AHEAD_SECONDS] of
 * audio past it (a guess from the size until the engine knows the rate), or, if the download
 * runs slower than playback, enough that the rest arrives before playback gets there.
 */
internal fun refillTarget(want: Long, size: Long, playRate: Long, written: Long, elapsedNs: Long): Long {
    // Before the engine knows the rate, assume a four-minute song.
    val rate = playRate.takeIf { it > 0 } ?: if (size > 0) size / 240 else 0
    val ahead = want + maxOf(MIN_AHEAD, rate * AHEAD_SECONDS)
    val downloadRate = if (elapsedNs > 1_000_000_000L) (written * 1_000_000_000.0 / elapsedNs).toLong() else 0
    if (size <= 0 || rate <= 0 || downloadRate <= 0 || downloadRate >= rate) return ahead
    val secondsLeft = (size - want).toDouble() / rate
    val inTime = size - (secondsLeft * downloadRate).toLong()
    return maxOf(ahead, inTime)
}

/** The order downloads run in: one at a time, oldest first; closed ones step aside. */
private object Downloads {
    private val lock = Object()
    private val queue = ArrayList<GrowingFileDataSource>()

    fun enqueue(s: GrowingFileDataSource) = synchronized(lock) { queue += s }

    fun awaitTurn(s: GrowingFileDataSource) = synchronized(lock) {
        while (!s.closed && queue.firstOrNull { !it.closed } !== s) lock.wait(500)
    }

    fun finished(s: GrowingFileDataSource) = synchronized(lock) {
        queue.remove(s)
        lock.notifyAll()
    }

    fun wake() = synchronized(lock) { lock.notifyAll() }
}
