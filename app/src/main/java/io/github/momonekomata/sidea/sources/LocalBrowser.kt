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
        val out = mutableListOf<Entry>()
        context.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val name = c.getString(1) ?: continue
                val mime = c.getString(2)
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    out += Entry.Folder(name, id)
                } else if (isAudioFile(name) || mime?.startsWith("audio/") == true) {
                    val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    out += Entry.Song(
                        name,
                        Track(
                            id = uri.toString(),
                            title = titleFromFileName(name),
                            album = path.substringAfterLast('/'),
                            sizeBytes = c.getLong(3),
                            opener = { PlayableSource.Content(uri) },
                        ),
                    )
                }
            }
        }
        out.sortedForBrowsing()
    }
}
