package io.github.momonekomata.sidea.sources

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Positioned reads over a file wherever it lives. Used by the tag reader and the DSD decoder. */
interface RandomAccess : Closeable {
    /** Total length, or -1 when unknown. */
    val size: Long

    /** Like MediaDataSource.readAt: returns bytes read, or -1 at the end. */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    override fun close() {}
}

fun RandomAccess.readFully(position: Long, length: Int): ByteArray {
    val out = ByteArray(length)
    var done = 0
    while (done < length) {
        val n = readAt(position + done, out, done, length - done)
        if (n <= 0) throw EOFException("Wanted $length bytes at $position, got $done")
        done += n
    }
    return out
}

/** Reads up to [length] bytes; shorter only at the end of the file. */
fun RandomAccess.readUpTo(position: Long, length: Int): ByteArray {
    val out = ByteArray(length)
    var done = 0
    while (done < length) {
        val n = readAt(position + done, out, done, length - done)
        if (n <= 0) break
        done += n
    }
    return if (done == length) out else out.copyOf(done)
}

class ByteArrayAccess(private val bytes: ByteArray) : RandomAccess {
    override val size get() = bytes.size.toLong()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= bytes.size) return -1
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }
}

class ChannelAccess(private val channel: FileChannel, private val owner: Closeable) : RandomAccess {
    override val size get() = channel.size()
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        channel.read(ByteBuffer.wrap(buffer, offset, length), position)
    override fun close() = owner.close()
}

fun openFileAccess(file: File): RandomAccess {
    val stream = FileInputStream(file)
    return ChannelAccess(stream.channel, stream)
}
