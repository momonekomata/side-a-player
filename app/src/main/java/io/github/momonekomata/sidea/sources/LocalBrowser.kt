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
        val coverUri = pickFolderCover(rows.map { it.name })?.let { name ->
            DocumentsContract.buildDocumentUriUsingTree(treeUri, rows.first { it.name == name }.id)
        }
        val folderArt: ((Context) -> ByteArray)? = coverUri?.let { u -> { ctx -> ctx.contentResolver.openInputStream(u)!!.use { it.readBytes() } } }
        rows.mapNotNull { r ->
            if (r.mime == DocumentsContract.Document.MIME_TYPE_DIR) return@mapNotNull Entry.Folder(r.name, r.id)
            if (!isAudioFile(r.name) && r.mime?.startsWith("audio/") != true) return@mapNotNull null
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, r.id)
            val lrcUri = lrc[lrcKey(r.name)]?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }
            Entry.Song(
                r.name,
                track(uri, r.name, r.size, path, path.substringAfterLast('/').substringAfterLast(':')).copy(
                    lyricsFile = lrcUri?.let { u ->
                        { ctx -> ctx.contentResolver.openInputStream(u)!!.use { it.readBytes().toString(Charsets.UTF_8) } }
                    },
                    folderArt = folderArt,
                ),
            )
        }.sortedForBrowsing()
    }

    private fun track(uri: Uri, name: String, size: Long, folder: String, album: String) = Track(
        id = uri.toString(),
        title = titleFromFileName(name),
        album = album,
        sizeBytes = size,
        opener = { PlayableSource.Content(uri) },
        tagSource = { ctx -> PlayableSource.Content(uri).openRandomAccess(ctx) },
        lyricsWriter = { ctx, text -> writeLrc(ctx, folder, lrcName(name), text) },
    )

    /** Needs write access to the folder, which the system folder picker grants. */
    private fun writeLrc(ctx: Context, folder: String, name: String, text: String) {
        // Replace a .lrc that's there (plain lyrics giving way to timed ones) rather than add "Song (1).lrc".
        val doc = existingChild(ctx, folder, name) ?: run {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, folder)
            // octet-stream, so the provider keeps ".lrc" instead of adding ".txt".
            DocumentsContract.createDocument(ctx.contentResolver, parent, "application/octet-stream", name)
                ?: throw java.io.IOException("Can't create $name")
        }
        ctx.contentResolver.openOutputStream(doc, "wt")!!.use { it.write(text.toByteArray()) }
    }

    private fun existingChild(ctx: Context, folder: String, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folder)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1).equals(name, ignoreCase = true)) return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
            }
        }
        return null
    }

    override fun songFromIndex(id: String, name: String, size: Long, folderPath: String, folderName: String): Track =
        track(Uri.parse(id), name, size, folderPath, folderName)
}
