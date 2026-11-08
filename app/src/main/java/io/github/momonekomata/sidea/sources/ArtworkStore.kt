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

    /** Small covers for list rows: a few hundred fit in memory, and they're kept on the phone too. */
    private val thumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private const val THUMB_PX = 160
    @Volatile private var thumbDir: java.io.File? = null

    fun cached(track: Track): Bitmap? = cache.get(track.id)

    fun cachedThumb(id: String): Bitmap? = thumbs.get(id)

    /** The file changed (a new cover embedded, say): its picture is read again when next wanted. */
    fun forget(id: String) {
        cache.remove(id)
        thumbs.remove(id)
        missing.remove(id)
        thumbDir?.let { runCatching { thumbFile(it, id).delete() } }
    }

    /** The cover's bytes (embedded, else the folder's), and whether the file could be read at all. */
    private fun readPicture(context: Context, track: Track): Pair<ByteArray?, Boolean> {
        // A null tag source result means "not readable yet" (FTPS before caching).
        val ra = runCatching { track.tagSource?.invoke(context) }.getOrNull()
        val embedded = ra?.let { r ->
            runCatching { r.use { PictureReader.read(it)?.data } }
                .onFailure { Log.w("SideA.Art", "bad picture in ${track.id}", it) }
                .getOrNull()
        }
        val bytes = embedded ?: runCatching { track.folderArt?.invoke(context) }.getOrNull()
        return bytes to (ra != null || track.tagSource == null)
    }

    suspend fun load(context: Context, track: Track): Bitmap? {
        cache.get(track.id)?.let { return it }
        if (track.id in missing) return null
        return withContext(Dispatchers.IO) {
            gate.withPermit {
                val (bytes, readable) = readPicture(context, track)
                val bmp = bytes?.let { decode(it, 1024) }
                when {
                    bmp != null -> { cache.put(track.id, bmp); _loaded.update { it + 1 } }
                    readable -> missing += track.id
                }
                bmp
            }
        }
    }

    private fun thumbFile(dir: java.io.File, id: String): java.io.File {
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
        return java.io.File(dir, "$hash.jpg")
    }

    /**
     * A small cover for a list row. From the phone's copy when there is one, so a long list
     * scrolls without going back to the NAS; otherwise read once and kept.
     */
    suspend fun thumb(context: Context, track: Track): Bitmap? {
        thumbs.get(track.id)?.let { return it }
        if (track.id in missing) return null
        return withContext(Dispatchers.IO) {
            val dir = thumbDir ?: java.io.File(context.cacheDir, "thumbs").also { it.mkdirs(); thumbDir = it }
            val file = thumbFile(dir, track.id)
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.let { thumbs.put(track.id, it); return@withContext it }
            // The big one's here already (the grid, the deck): scaled down from it.
            val big = cache.get(track.id)
            val bmp = if (big != null) {
                scaled(big, THUMB_PX)
            } else gate.withPermit {
                val (bytes, readable) = readPicture(context, track)
                bytes?.let { decode(it, THUMB_PX)?.let { b -> scaled(b, THUMB_PX) } }.also { if (it == null && readable) missing += track.id }
            } ?: return@withContext null
            thumbs.put(track.id, bmp)
            runCatching { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
            bmp
        }
    }

    /** [b] at most [side] px on its longer side. */
    private fun scaled(b: Bitmap, side: Int): Bitmap {
        val longer = maxOf(b.width, b.height)
        if (longer <= side) return b
        val f = side.toFloat() / longer
        return Bitmap.createScaledBitmap(b, (b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1), true)
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
