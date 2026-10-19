package io.github.momonekomata.sidea.sources

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Cover art per track: the picture embedded in the file first, then the folder's cover image
 * (cover.jpg, folder.jpg, front.jpg and friends). Decoded at most 1024 px on a side and kept
 * in a small memory cache.
 */
object ArtworkStore {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val _loaded = MutableStateFlow(0)
    /** Bumps whenever a new cover lands in the cache, for the notification to pick it up. */
    val loaded: StateFlow<Int> = _loaded.asStateFlow()
    private val gate = Semaphore(2)

    fun cached(track: Track): Bitmap? = cache.get(track.id)

    suspend fun load(context: Context, track: Track): Bitmap? {
        cache.get(track.id)?.let { return it }
        if (track.id in missing) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                // A null tag source result means "not readable yet" (FTPS before caching).
                val ra = runCatching { track.tagSource?.invoke(context) }.getOrNull()
                val embedded = ra?.let { r ->
                    runCatching { r.use { PictureReader.read(it)?.data } }
                        .onFailure { Log.w("SideA.Art", "bad picture in ${track.id}", it) }
                        .getOrNull()
                }
                val bytes = embedded ?: runCatching { track.folderArt?.invoke(context) }.getOrNull()
                val bmp = bytes?.let { decode(it, 1024) }
                when {
                    bmp != null -> { cache.put(track.id, bmp); _loaded.update { it + 1 } }
                    ra != null || track.tagSource == null -> missing += track.id
                }
                bmp
            }
        }
    }

    fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

private val COVER_NAMES = listOf("cover", "folder", "front", "albumart", "album", "albumartsmall")
private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")

/** Picks the folder's cover image from its file names, best name first. */
fun pickFolderCover(names: List<String>): String? {
    val images = names.filter { it.substringAfterLast('.', "").lowercase() in IMAGE_EXT }
    for (wanted in COVER_NAMES) {
        images.firstOrNull { it.substringBeforeLast('.').lowercase().startsWith(wanted) }?.let { return it }
    }
    return images.singleOrNull() // a lone image in an album folder is almost always the cover
}
