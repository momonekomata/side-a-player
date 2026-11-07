package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.momonekomata.sidea.BrowseState
import io.github.momonekomata.sidea.MainViewModel
import io.github.momonekomata.sidea.library.LibFilter
import io.github.momonekomata.sidea.library.Library
import io.github.momonekomata.sidea.library.LibrarySource
import io.github.momonekomata.sidea.sources.Entry
import io.github.momonekomata.sidea.sources.NasServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Settings → Music sources: what the Library is built from. Folders on the phone are picked with
 * Android's folder picker; a NAS or cloud server is added here, then opened to choose which of its
 * folders go in. Everything is played from the Library.
 */
@Composable
fun SourcesScreen(vm: MainViewModel, pickLibraryFolder: () -> Unit, onDismiss: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sources by Library.sources.collectAsStateWithLifecycle()
    val scan by Library.scan.collectAsStateWithLifecycle()
    val version by Library.version.collectAsStateWithLifecycle()
    val counts by produceState(emptyMap<String, Int>(), version) {
        value = withContext(Dispatchers.IO) { runCatching { Library.db.sourceCounts(LibFilter()) }.getOrDefault(emptyMap()) }
    }
    var editing by remember { mutableStateOf<NasServer?>(null) }
    var adding by remember { mutableStateOf(false) }
    var chosen by remember { mutableStateOf<LibrarySource?>(null) }
    val nas = ui.nas

    Dialog(
        // Back inside a server's folders goes up a folder; at the list, it closes.
        onDismissRequest = { if (nas != null) vm.up() else onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Column(Modifier.fillMaxSize().background(Retro.Background).safeDrawingPadding().padding(horizontal = 12.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("MUSIC SOURCES", color = Retro.OnShell, fontFamily = Retro.Logo, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = 20.sp, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = Retro.Muted) }
            }
            val card = Modifier.weight(1f).fillMaxWidth().padding(bottom = 12.dp)
            if (nas != null) {
                FolderPicker(vm, nas, card)
            } else {
                val scanning = if (scan.running) "scanning ${scan.source} · ${scan.songs} songs" else "${plural(sources.size, "folder")} · ${plural(ui.nasServers.size, "server")}"
                JCard("Sources", scanning, card) {
                    item(key = "h:lib") { SectionLabel("In the Library") }
                    if (sources.isEmpty()) item(key = "none") { JCardNote("Nothing yet. Add a folder on the phone, or a folder on a NAS or in the cloud below; everything inside it is scanned in.") }
                    sources.forEach { src ->
                        item(key = "s:" + src.id) {
                            JCardRow(
                                if (src.isLocal) "PH" else "NET", src.name, "${plural(counts[src.id] ?: 0, "song")} · ${sourceState(src)}",
                                action = "Rescan" to { Library.scan(src.id) },
                                onClick = { chosen = src },
                            )
                        }
                    }
                    item(key = "add:phone") { JCardRow("＋", "Add a folder on the phone", "SD card and USB drives too", onClick = pickLibraryFolder) }
                    if (sources.isNotEmpty()) item(key = "rescan") { JCardRow("↻", "Rescan everything", onClick = Library::scanAll) }

                    item(key = "h:nas") { SectionLabel("NAS & cloud") }
                    ui.nasServers.forEach { s ->
                        item(key = "n:" + s.id) {
                            val signedOut = s.kind.cloud && s.refreshToken.isBlank()
                            val detail = if (s.kind.cloud) s.kind.label + if (signedOut) " · not signed in" else " · tap to pick folders" else "${s.kind.label} · ${s.address}"
                            JCardRow(s.kind.name.take(3), s.name, detail, action = "Edit" to { editing = s }, onClick = { if (signedOut) editing = s else vm.openServer(s) })
                        }
                    }
                    item(key = "add:nas") { JCardRow("＋", "Add a NAS or cloud storage", "SMB, SFTP, FTP(S), WebDAV, Drive, OneDrive, Dropbox", onClick = { adding = true }) }
                    item(key = "tip") {
                        JCardNote("Tap a server to open it and add its music folders. Tap a Library folder to remove it. Sources are rescanned on start when they're half a day old.")
                    }
                }
            }
        }

        if (adding) NasServerDialog(null, ui.nasServers, onSave = { vm.saveServer(it); adding = false }, onSignIn = { vm.signInCloud(it); adding = false }, onDelete = {}, onDismiss = { adding = false })
        editing?.let { s ->
            NasServerDialog(s, ui.nasServers, onSave = { vm.saveServer(it); editing = null }, onSignIn = { vm.signInCloud(it); editing = null }, onDelete = { vm.deleteServer(it); editing = null }, onDismiss = { editing = null })
        }
        chosen?.let { src ->
            AlertDialog(
                onDismissRequest = { chosen = null },
                title = { Text(src.name) },
                text = { Text("${plural(counts[src.id] ?: 0, "song")} · ${sourceState(src)}.\n\nRemoving it takes its songs out of the Library; favourites and playlists keep them for when it's back. The files themselves aren't touched.") },
                confirmButton = { TextButton({ Library.remove(src.id); chosen = null }) { Text("Remove from Library") } },
                dismissButton = { TextButton({ chosen = null }) { Text("Cancel") } },
            )
        }
    }
}

private fun sourceState(src: LibrarySource) = when {
    src.lastError != null -> "couldn't scan: ${src.lastError}"
    src.lastScan == 0L -> "not scanned yet"
    else -> "scanned ${agoLabel(src.lastScan)}"
}

/** A server's folders, to choose one for the Library. Songs are listed only to show what's there. */
@Composable
private fun FolderPicker(vm: MainViewModel, state: BrowseState, modifier: Modifier) {
    val sources by Library.sources.collectAsStateWithLifecycle()
    val inLibrary = remember(sources, state.currentPath) { vm.inLibrary() }
    val ready = !state.loading && state.error == null
    val songs = state.entries.count { it is Entry.Song }
    val folders = state.entries.size - songs
    JCard(state.currentName, if (ready) "${plural(songs, "song")} · ${plural(folders, "folder")} · ${state.browser.title}" else state.browser.title, modifier) {
        item(key = "up") {
            if (state.path.size > 1) JCardRow("‹", "Back to ${state.path[state.path.size - 2].first}", onClick = vm::up)
            else JCardRow("‹", "All sources", onClick = vm::closeServer)
        }
        when {
            state.loading -> item(key = "reading") { JCardNote("Reading…") }
            // A folder that couldn't be read can't be added either.
            state.error != null -> item(key = "error") { JCardNote(state.error) { CardButton("Try again", vm::retry) } }
            else -> {
                item(key = "add") {
                    if (inLibrary) JCardRow("✓", "In the Library", "with everything inside it", onClick = {})
                    else JCardRow("＋", "Add this folder to the Library", "scans it and every folder inside it", onClick = vm::addToLibrary)
                }
                state.entries.forEach { e ->
                    when (e) {
                        is Entry.Folder -> item(key = "f:" + e.path) { JCardRow("▸", e.name, "folder", onClick = { vm.openFolder(e) }) }
                        is Entry.Song -> item(key = "s:" + e.track.id) { JCardRow("♪", e.name, onClick = {}) }
                    }
                }
                if (state.entries.isEmpty()) item(key = "empty") { JCardNote("No folders or music files here.") }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        color = Retro.Orange, fontFamily = Retro.Mono, fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp),
    )
}
