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

    /**
     * The cached file when it's complete, else a stream fed by [fetch] while it downloads, or by
     * [ranged] in parallel pieces when the server takes range requests.
     */
    fun open(file: File, size: Long, ranged: RangeFetch? = null, fetch: ((ByteArray, Int) -> Boolean) -> Unit): PlayableSource {
        if (size > 0 && file.length() == size) {
            file.setLastModified(System.currentTimeMillis())
            return PlayableSource.File(file.path)
        }
        trim(file.parentFile!!, keep = file)
        return PlayableSource.Stream(GrowingFileDataSource(file, size, fetch, ranged))
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
    private val ranged: RangeFetch? = null,
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
                    val r = ranged
                    val inPieces = r != null && expectedSize > 2 * PIECE && try {
                        downloadInPieces(r)
                        true
                    } catch (e: RangesUnsupported) {
                        false
                    }
                    if (!inPieces) downloadInOne()
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

    private fun downloadInOne() {
        RandomAccessFile(partial, "rw").use { out ->
            var pos = 0L
            fetch(sink@{ buf, n ->
                if (closed) return@sink false
                out.seek(pos)
                out.write(buf, 0, n)
                pos += n
                synchronized(lock) { written = maxOf(written, pos); lock.notifyAll() }
                true
            })
        }
    }

    /**
     * [WORKERS] connections, each taking the next [PIECE] in order, so the piece playback needs
     * next is always among those coming in. Over Wi-Fi, TLS or a busy NAS one connection often
     * tops out well below what the link can do; several together get much closer. Playback may
     * read up to the first piece that isn't complete, plus what that piece already has.
     */
    private fun downloadInPieces(fetchRange: RangeFetch) {
        val pieces = ((expectedSize + PIECE - 1) / PIECE).toInt()
        val got = LongArray(pieces)
        val next = java.util.concurrent.atomic.AtomicInteger(0)
        val errors = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        fun advance() {
            var k = (written / PIECE).toInt()
            var end = written
            while (k < pieces) {
                val start = k * PIECE
                val size = minOf(PIECE, expectedSize - start)
                end = start + got[k]
                if (got[k] < size) break
                k++
            }
            written = maxOf(written, minOf(end, expectedSize))
            lock.notifyAll()
        }
        val workers = (0 until WORKERS).map {
            Thread({
                try {
                    RandomAccessFile(partial, "rw").use { out ->
                        while (!closed && errors.get() == null) {
                            val i = next.getAndIncrement()
                            if (i >= pieces) break
                            val start = i * PIECE
                            val end = minOf(expectedSize, start + PIECE)
                            var attempt = 0
                            while (!closed) {
                                // A dropped connection resumes the piece where it stopped.
                                val from = start + synchronized(lock) { got[i] }
                                if (from >= end) break
                                try {
                                    var pos = from
                                    fetchRange(from, end) sink@{ buf, n ->
                                        if (closed) return@sink false
                                        val take = minOf(n.toLong(), end - pos).toInt()
                                        out.seek(pos)
                                        out.write(buf, 0, take)
                                        pos += take
                                        synchronized(lock) { got[i] = pos - start; advance() }
                                        pos < end
                                    }
                                    if (!closed && pos < end) throw IOException("The NAS ended a piece early")
                                } catch (e: RangesUnsupported) {
                                    throw e
                                } catch (e: IOException) {
                                    if (++attempt >= 3) throw e
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    errors.compareAndSet(null, t)
                    synchronized(lock) { lock.notifyAll() }
                }
            }, "SideA-download-$it").also { it.start() }
        }
        workers.forEach { it.join() }
        errors.get()?.let { throw it }
    }

    private var stallFrom = 0L
    private var stallTarget = 0L

    override val refillProgress: Float
        get() = synchronized(lock) {
            if (stallTarget <= stallFrom) 0f else ((written - stallFrom).toFloat() / (stallTarget - stallFrom)).coerceIn(0f, 1f)
        }

    /** How fast the NAS delivers, for the Buffering line. */
    override val downloadBytesPerSecond: Long
        get() {
            val ns = System.nanoTime() - startedNs
            return if (startedNs > 0 && ns > 500_000_000L) (synchronized(lock) { written } * 1e9 / ns).toLong() else 0
        }

    override fun getSize(): Long = expectedSize

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (expectedSize in 0..position) return -1
        val end = if (expectedSize < 0) Long.MAX_VALUE else expectedSize // unknown size: wait for the end
        val want = minOf(position + size, end)
        synchronized(lock) {
            if (written < want && !done && !closed) {
                val target = minOf(end, refillTarget(want))
                stallFrom = written
                stallTarget = target
                val listener = onStall
                listener?.invoke(true)
                try {
                    var ticks = 0
                    while (!done && !closed && written < target) {
                        lock.wait(200)
                        // Once a second, so the Buffering line's numbers move.
                        if (++ticks % 5 == 0) listener?.invoke(true)
                    }
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
private const val QUICK_SECONDS = 3
private const val AHEAD_SECONDS = 10

/**
 * Bytes a downloading file should hold before playing on from [want]. While the download
 * is new or clearly outpaces playback, [QUICK_SECONDS] of audio: a song starts almost at once,
 * even a DSD one, which needs several times the bytes of PCM for the same seconds. When it only
 * just keeps up, [AHEAD_SECONDS]; slower than playback, enough that the rest arrives before
 * playback gets there. The rate is a guess from the size until the engine knows it.
 */
internal fun refillTarget(want: Long, size: Long, playRate: Long, written: Long, elapsedNs: Long): Long {
    // Before the engine knows the rate, assume a four-minute song.
    val rate = playRate.takeIf { it > 0 } ?: if (size > 0) size / 240 else 0
    val downloadRate = if (elapsedNs > 1_000_000_000L) (written * 1_000_000_000.0 / elapsedNs).toLong() else 0
    val seconds = if (downloadRate == 0L || downloadRate >= rate * 6 / 5) QUICK_SECONDS else AHEAD_SECONDS
    val ahead = want + maxOf(MIN_AHEAD, rate * seconds)
    if (size <= 0 || rate <= 0 || downloadRate <= 0 || downloadRate >= rate) return ahead
    val secondsLeft = (size - want).toDouble() / rate
    val inTime = size - (secondsLeft * downloadRate).toLong()
    return maxOf(ahead, inTime)
}

/** Fetches bytes from start until end (exclusive) of a file into the sink, which returns false to stop. */
typealias RangeFetch = (start: Long, end: Long, sink: (ByteArray, Int) -> Boolean) -> Unit

/** The server sent the whole file for a range request: download it in one go instead. */
class RangesUnsupported : IOException("The server ignores range requests")

private const val PIECE = 4L * 1024 * 1024
private const val WORKERS = 4

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
