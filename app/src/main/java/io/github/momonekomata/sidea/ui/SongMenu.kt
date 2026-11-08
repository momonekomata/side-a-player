package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.momonekomata.sidea.MainViewModel
import io.github.momonekomata.sidea.SongMenu
import io.github.momonekomata.sidea.sources.TagStore
import io.github.momonekomata.sidea.sources.displayArtist
import io.github.momonekomata.sidea.sources.displayTitle

/** What a long-press on a song offers: favourite, play next, add to queue, add to a playlist. */
@Composable
fun SongMenuDialog(vm: MainViewModel, menu: SongMenu) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val tags by TagStore.tags.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val t = menu.track
    val done = vm::closeSongMenu

    if (naming) {
        PlaylistNameDialog(onCreate = { vm.newPlaylist(it, t); done() }, onDismiss = { naming = false })
        return
    }
    AlertDialog(
        onDismissRequest = done,
        title = {
            Column {
                Text(t.displayTitle(tags), maxLines = 2, overflow = TextOverflow.Ellipsis)
                t.displayArtist(tags)?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, color = Retro.Muted, maxLines = 1) }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (!picking) {
                    val fav = favorites.any { it.id == t.id }
                    MenuItem(if (fav) "♥" else "♡", if (fav) "Remove from favourites" else "Add to favourites") { vm.toggleFavorite(t); done() }
                    // As Apple Music names them: right after this song, or at the end of the queue.
                    MenuItem("⏭", "Play Next") { vm.playNext(t); done() }
                    MenuItem("≡", "Play Last") { vm.addToQueue(t); done() }
                    if (menu.queueIndex >= 0) MenuItem("✕", "Remove from Queue") { io.github.momonekomata.sidea.audio.Player.removeAt(menu.queueIndex); done() }
                    MenuItem("▤", "Add to playlist…") { picking = true }
                    menu.playlistId?.let { id -> MenuItem("✕", "Remove from this playlist") { vm.removeFromPlaylist(id, menu.index); done() } }
                } else {
                    playlists.forEach { p ->
                        MenuItem("▤", "${p.name} · ${p.songs.size}") { vm.addToPlaylist(p.id, t); done() }
                    }
                    MenuItem("+", "New playlist…") { naming = true }
                }
            }
        },
        confirmButton = { TextButton(if (picking) ({ picking = false }) else done) { Text(if (picking) "Back" else "Close") } },
    )
}

/** What a long-press on an album, an artist or a genre offers: the song menu, for all its songs. */
@Composable
fun GroupMenuDialog(vm: MainViewModel, menu: io.github.momonekomata.sidea.GroupMenu) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    var naming by remember { mutableStateOf(false) }
    val songs = menu.tracks
    val done = vm::closeGroupMenu

    if (naming && songs != null) {
        PlaylistNameDialog(onCreate = { vm.newPlaylist(it, songs); done() }, onDismiss = { naming = false })
        return
    }
    AlertDialog(
        onDismissRequest = done,
        title = {
            Column {
                Text(menu.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val count = songs?.let { if (it.size == 1) "1 song" else "${it.size} songs" }
                Text(listOfNotNull(menu.subtitle.takeIf { it.isNotBlank() }, count).joinToString(" · "), fontSize = 13.sp, color = Retro.Muted, maxLines = 1)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                when {
                    songs == null -> Text("Finding its songs…", fontSize = 14.sp, color = Retro.Muted)
                    songs.isEmpty() -> Text("None of its songs can be played right now.", fontSize = 14.sp, color = Retro.Muted)
                    !picking -> {
                        val fav = songs.all { s -> favorites.any { it.id == s.id } }
                        MenuItem(if (fav) "♥" else "♡", if (fav) "Remove from favourites" else "Add to favourites") { vm.toggleFavorites(songs); done() }
                        MenuItem("⏭", "Play Next") { vm.playNext(songs); done() }
                        MenuItem("≡", "Play Last") { vm.addToQueue(songs); done() }
                        MenuItem("▤", "Add to playlist…") { picking = true }
                    }
                    else -> {
                        playlists.forEach { p ->
                            MenuItem("▤", "${p.name} · ${p.songs.size}") { vm.addToPlaylist(p.id, songs); done() }
                        }
                        MenuItem("+", "New playlist…") { naming = true }
                    }
                }
            }
        },
        confirmButton = { TextButton(if (picking) ({ picking = false }) else done) { Text(if (picking) "Back" else "Close") } },
    )
}

@Composable
private fun MenuItem(mark: String, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(mark, color = Retro.Orange, fontFamily = Retro.Mono, modifier = Modifier.width(30.dp))
        Text(label, fontSize = 15.sp)
    }
}

@Composable
fun PlaylistNameDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") }) },
        confirmButton = { TextButton({ onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
