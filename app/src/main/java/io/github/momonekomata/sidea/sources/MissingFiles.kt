package io.github.momonekomata.sidea.sources

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Songs whose file isn't where the Library last found it: moved or renamed on the NAS (or the
 * phone) since the last scan. Noticed when the file itself is opened (for its tags, its cover,
 * or to play it) and the source says it isn't there; a rescan of its source finds it again.
 */
object MissingFiles {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    /** What a deck shows for a song whose file has gone. */
    const val MESSAGE = "This song's file has been moved or renamed since the Library last scanned its folder. Rescan to find it (Library, or Settings → Music sources)."

    /** [t], from opening song [id]'s own file: if it says the file isn't there, the song is marked. Returns whether it was. */
    fun report(id: String, t: Throwable?): Boolean {
        if (!isGone(t)) return false
        _ids.update { it + id }
        return true
    }

    /** The same for an error already turned into text (the player's). */
    fun reportText(id: String, message: String?): Boolean {
        if (message == null || !looksGone(message)) return false
        _ids.update { it + id }
        return true
    }

    /** Songs found again (or gone from the Library) after a rescan. */
    fun forget(which: (String) -> Boolean) = _ids.update { set -> set.filterNot(which).toSet() }

    /** Whether [t] (or what caused it) says the file isn't there. */
    fun isGone(t: Throwable?): Boolean {
        var e = t
        var depth = 0
        while (e != null && depth++ < 6) {
            if (e is java.io.FileNotFoundException || e is java.nio.file.NoSuchFileException) return true
            if (looksGone(e.message.orEmpty())) return true
            e = e.cause
        }
        return false
    }

    /**
     * How each source says "not there": HTTP 404/410 (WebDAV, the clouds; Dropbox's not_found,
     * OneDrive's itemNotFound), SMB's OBJECT_NAME/PATH_NOT_FOUND, SFTP's "No such file", FTP's
     * 550, and Android's for a phone folder ("Failed to determine if … is child of …", "Missing
     * file for …").
     */
    fun looksGone(message: String): Boolean =
        Regex(
            "HTTP 40[4]|HTTP 410|\\b(404|410) Not Found|not_found|itemNotFound|OBJECT_(NAME|PATH)_NOT_FOUND|No such file|^550 |\\b550 .*(not found|No such|failed to open)" +
                "|Missing file for|Failed to determine if .* is child of",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(message)
}
