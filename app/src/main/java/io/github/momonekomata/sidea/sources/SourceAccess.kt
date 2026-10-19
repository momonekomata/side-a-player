package io.github.momonekomata.sidea.sources

import android.content.Context
import android.media.MediaDataSource
import io.github.momonekomata.sidea.audio.PlayableSource
import java.io.File
import java.io.FileInputStream

class MediaDataSourceAccess(private val source: MediaDataSource) : RandomAccess {
    override val size get() = source.size
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int) =
        source.readAt(position, buffer, offset, length)
    override fun close() = source.close()
}

/** Opens any playable source for positioned reads. Streams are taken over, not copied. */
fun PlayableSource.openRandomAccess(context: Context): RandomAccess = when (this) {
    is PlayableSource.File -> openFileAccess(File(path))
    is PlayableSource.Content -> {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Can't open $uri")
        val stream = FileInputStream(pfd.fileDescriptor)
        ChannelAccess(stream.channel) { stream.close(); pfd.close() }
    }
    is PlayableSource.Stream -> MediaDataSourceAccess(dataSource)
}
