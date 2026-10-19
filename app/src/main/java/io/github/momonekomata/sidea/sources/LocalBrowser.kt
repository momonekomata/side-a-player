package io.github.momonekomata.sidea.sources

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import io.github.momonekomata.sidea.audio.PlayableSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Browses a folder the user picked with the system folder picker (Storage Access Framework).
 * Works for internal storage, SD cards and USB drives without broad storage permissions.
 * Paths here are document IDs inside the picked tree.
 */
class LocalBrowser(private val context: Context, private val treeUri: Uri) : Browser {
    override val title = "Local"
    override val rootPath: String = DocumentsContract.getTreeDocumentId(treeUri)

    override suspend fun list(path: String): List<Entry> = withContext(Dispatchers.IO) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, path)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        data class Row(val id: String, val name: String, val mime: String?, val size: Long)
        val rows = mutableListOf<Row>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) rows += Row(c.getString(0), c.getString(1) ?: continue, c.getString(2), c.getLong(3))
        }
        val lrc = rows.filter { isLrcFile(it.name) }.associate { lrcKey(it.name) to it.id }
        rows.mapNotNull { r ->
            if (r.mime == DocumentsContract.Document.MIME_TYPE_DIR) return@mapNotNull Entry.Folder(r.name, r.id)
            if (!isAudioFile(r.name) && r.mime?.startsWith("audio/") != true) return@mapNotNull null
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, r.id)
            val lrcUri = lrc[lrcKey(r.name)]?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }
            Entry.Song(
                r.name,
                Track(
                    id = uri.toString(),
                    title = titleFromFileName(r.name),
                    album = path.substringAfterLast('/').substringAfterLast(':'),
                    sizeBytes = r.size,
                    opener = { PlayableSource.Content(uri) },
                    tagSource = { ctx -> PlayableSource.Content(uri).openRandomAccess(ctx) },
                    lyricsFile = lrcUri?.let { u ->
                        { ctx -> ctx.contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
                    },
                ),
            )
        }.sortedForBrowsing()
    }
}
