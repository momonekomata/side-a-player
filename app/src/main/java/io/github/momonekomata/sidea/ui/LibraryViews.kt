package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.Image
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.momonekomata.sidea.MainViewModel
import io.github.momonekomata.sidea.audio.QueueState
import io.github.momonekomata.sidea.audio.SignalPaths
import io.github.momonekomata.sidea.library.LibAlbum
import io.github.momonekomata.sidea.library.LibDrill
import io.github.momonekomata.sidea.library.LibFacets
import io.github.momonekomata.sidea.library.INSPECT
import io.github.momonekomata.sidea.library.LibFilter
import io.github.momonekomata.sidea.library.LibSegment
import io.github.momonekomata.sidea.library.LibTrack
import io.github.momonekomata.sidea.library.Library
import io.github.momonekomata.sidea.library.FAVORITES_LIST
import io.github.momonekomata.sidea.sources.ArtworkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "FLAC 24/96", "DSD128", "MP3 44.1": a song's format the way the deck's badge talks. */
fun formatLabel(codec: String, rate: Int, bits: Int?): String {
    // Lossy files have no bit depth of their own.
    val depth = bits?.takeIf { it > 1 && SignalPaths.isLossless(codec) }
    return when {
        codec == "DSD" && rate > 0 -> "DSD${rate / 44_100}"
        rate <= 0 -> codec
        depth != null -> "$codec $depth/${SignalPaths.khz(rate).removeSuffix(" kHz")}"
        else -> "$codec ${SignalPaths.khz(rate).removeSuffix(" kHz")}"
    }
}

/**
 * The Library: every song under the folders added in Settings → Music sources, by artist,
 * album, title or genre, searched and narrowed with Focus (which also holds the favourites and
 * playlists). Search, the views and Focus stay on top.
 */
@Composable
fun LibraryCard(vm: MainViewModel, queue: QueueState, onSources: () -> Unit, modifier: Modifier, swipeTabs: Boolean = true) {
    val model = vm.library
    val ui by model.ui.collectAsStateWithLifecycle()
    val scan by Library.scan.collectAsStateWithLifecycle()
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    var filtering by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<io.github.momonekomata.sidea.sources.Playlist?>(null) }
    val grid by model.gridAlbums.collectAsStateWithLifecycle()
    val top = ui.stack.lastOrNull()
    val view = ui.view
    // Each list (a view, an artist, an album, a Focus list, a search) keeps its scroll position:
    // switching views, Back, and the next start land where you were. The position is put back
    // once that list's rows are in, so a short list still loading can't clamp it to the top.
    val place = model.placeKey(ui.segment, ui.stack, ui.filter)
    val ready = ui.loaded == place
    val list = remember(place, ready) { if (ready) model.scrollFor(place).let { (i, o) -> androidx.compose.foundation.lazy.LazyListState(i, o) } else androidx.compose.foundation.lazy.LazyListState() }
    LaunchedEffect(list, place, ready) {
        if (!ready) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { (i, o) -> model.saveScroll(place, i, o) }
    }
    // The search bar tucks away while scrolling down the list and comes back on scrolling (or
    // pulling) up, so the list gets the room. It stays while there's something searched for. Only
    // the finger moves it: opening an artist or going Back leaves it as it was, so the tabs under
    // it don't jump.
    var searchShown by remember { mutableStateOf(true) }
    val hideSearch = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < -6f) searchShown = false else if (available.y > 6f) searchShown = true
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    val showSearch = searchShown || ui.filter.query.isNotEmpty()

    val (songs, albums, artists) = ui.totals
    val subtitle = when {
        scan.running -> "scanning ${scan.source} · ${scan.folders} folders, ${scan.songs} songs" + if (scan.toRead > 0) " · reading ${scan.read}/${scan.toRead}" else ""
        else -> "${plural(songs, "song")} · ${plural(albums, "album")} · ${plural(artists, "artist")}"
    }
    // Focused on favourites or a playlist: which one, for the line above its songs.
    val focusList = ui.filter.list
    val playlist = playlists.firstOrNull { it.id == focusList }
    val listName = when {
        focusList == null -> null
        focusList == FAVORITES_LIST -> "♥ Favourites"
        else -> playlist?.let { "▤ " + it.name } ?: "▤ Playlist"
    }
    val labels: List<String> = view.artists?.map { it.name } ?: view.albums?.map { it.title } ?: view.tracks?.takeIf { !view.albumOrder && focusList == null }?.map { it.title } ?: view.genres?.map { it.name } ?: emptyList()
    // Rows above the list itself: Back, the list's own line, and Play/Shuffle under an artist or genre.
    // A playlist gets a line above its songs, to delete it; the tab chips say what's shown.
    val showListLine = playlist != null && top == null
    // (No Back row: the system back gesture goes up a level.)
    val above = (if (top is LibDrill.Artist || top is LibDrill.Genre) 1 else 0) + (if (showListLine) 1 else 0)
    val gridded = grid && view.albums != null
    val letters = remember(labels, above, gridded) {
        when {
            labels.size < 20 -> emptyList()
            // Three albums to a row in the grid.
            gridded -> letterIndex(labels, 0).map { (c, i) -> c to above + i / 3 }
            else -> letterIndex(labels, above)
        }
    }

    // Swipe sideways to change tab: Artists · Albums · Tracks · Genres · ♥ (· the open playlist).
    // The card follows the finger a little; a long enough swipe moves one tab and it springs back.
    val tabs: List<() -> Unit> = LibSegment.entries.map { s -> { model.segment(s) } } +
        listOf({ model.showList(FAVORITES_LIST) }) +
        (playlist?.let { p -> listOf({ model.showList(p.id) }) } ?: emptyList())
    val tab = when {
        focusList == FAVORITES_LIST -> LibSegment.entries.size
        playlist != null -> LibSegment.entries.size + 1
        else -> ui.segment.ordinal
    }
    val currentTabs by androidx.compose.runtime.rememberUpdatedState(tabs)
    val currentTab by androidx.compose.runtime.rememberUpdatedState(tab)
    var swipe by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val swipeScope = androidx.compose.runtime.rememberCoroutineScope()
    val swipeFar = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
    val swiping = Modifier
        .graphicsLayer { translationX = swipe * 0.3f }
        .pointerInput(Unit) {
            fun settle() {
                val to = when {
                    swipe <= -swipeFar -> currentTab + 1
                    swipe >= swipeFar -> currentTab - 1
                    else -> currentTab
                }
                if (to != currentTab && to in currentTabs.indices) currentTabs[to]()
                swipeScope.launch { androidx.compose.animation.core.animate(swipe, 0f) { v, _ -> swipe = v } }
            }
            detectHorizontalDragGestures(
                onDragStart = { swipe = 0f },
                onDragEnd = { settle() },
                onDragCancel = { settle() },
            ) { change, dx ->
                swipe += dx
                change.consume()
            }
        }

    // Where a pager owns the sideways swipe (landscape), the tabs are taps only.
    JCard(top?.title ?: "Library", subtitle, modifier.then(if (swipeTabs) swiping else Modifier).nestedScroll(hideSearch), listState = list, letters = letters, pinned = {
        Column {
          androidx.compose.animation.AnimatedVisibility(
              showSearch,
              enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
              exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
          ) {
          Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // A slim search pill rather than a full text field: the list keeps the height.
            androidx.compose.foundation.text.BasicTextField(
                value = ui.filter.query,
                onValueChange = model::search,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Retro.Ink, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(Retro.Orange),
                modifier = Modifier.weight(1f),
                decorationBox = { field ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Retro.CreamShade).padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Search, null, tint = Retro.InkFaint, modifier = Modifier.size(18.dp))
                        Box(Modifier.weight(1f).padding(start = 8.dp)) {
                            if (ui.filter.query.isEmpty()) Text("Search titles, artists, albums, genres…", color = Retro.InkFaint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            field()
                        }
                        if (ui.filter.query.isNotEmpty()) Text("✕", color = Retro.InkFaint, fontSize = 15.sp, modifier = Modifier.clickable { model.search("") }.padding(start = 8.dp))
                    }
                },
            )
            // Albums as covers or as a list: always in sight beside the search while albums show.
            if (view.albums != null || (view.search != null && ui.segment == LibSegment.ALBUMS)) {
                androidx.compose.material3.IconButton(onClick = { model.setGridAlbums(!grid) }, modifier = Modifier.padding(start = 4.dp).size(40.dp)) {
                    androidx.compose.material3.Icon(
                        if (grid) androidx.compose.material.icons.Icons.Filled.ViewList else androidx.compose.material.icons.Icons.Filled.GridView,
                        if (grid) "Show albums as a list" else "Show albums as a grid",
                        tint = Retro.Ink,
                    )
                }
            }
          }
          }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                LibSegment.entries.forEach { s -> Chip(s.label, ui.segment == s && top == null && focusList == null) { model.segment(s) } }
                // Favourites and playlists: tabs of their own, apart from Focus's filters.
                Chip("♥" + "\uFE0E", focusList == FAVORITES_LIST, "Favourites") { model.showList(FAVORITES_LIST) }
                Chip(playlist?.let { "▤ " + it.name.take(10) } ?: "▤", playlist != null, "Playlists") { picking = true }
                Chip(if (ui.filter.count > 0) "Focus · ${ui.filter.count}" else "Focus", ui.filter.count > 0) {
                    model.loadFacets()
                    filtering = true
                }            }
        }
    }) {
        if (showListLine) {
            item(key = "listline") {
                // The list being shown: leave it, or delete a playlist.
                JCardRow(
                    "", listName!!, if (playlist != null) "playlist · long-press a song to take it out" else "tap ♡ on the deck, or long-press a song, to add",
                    action = if (playlist != null) "Delete" to { deleting = playlist } else null,
                    onClick = model::closeList,
                )
            }
        }
        if (songs == 0 && !scan.running && top == null) {
            item(key = "empty") {
                JCardNote("Point the Library at your music: add a folder on the phone, or on a NAS or in the cloud, in Settings → Music sources. Everything inside it gets scanned in.") {
                    CardButton("Music sources…", onSources)
                }
            }
        }
        view.search?.let { res ->
            // The open tab's matching names first, as that tab shows them; then the rest mixed by
            // how well they match, each marked with what it is.
            val mine = res.hits.take(res.top)
            val others = res.hits.drop(res.top)
            val songsShown = res.hits.mapNotNull { it.track }
            fun playSong(t: io.github.momonekomata.sidea.library.LibTrack) = model.play(songsShown, songsShown.indexOfFirst { it.id == t.id }.coerceAtLeast(0))
            if (res.hits.isEmpty()) item(key = "s:none") { JCardNote("Nothing in the Library matches “${ui.filter.query.trim()}”.") }
            val myAlbums = mine.mapNotNull { it.album }
            if (ui.segment == LibSegment.ALBUMS && grid) {
                myAlbums.chunked(3).forEachIndexed { r, row ->
                    item(key = "s:alg:$r:" + row.first().key) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { al -> AlbumTile(model, al, showBy = true, Modifier.weight(1f)) { model.open(LibDrill.Album(al.key, al.title, al.by)) } }
                            repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            } else {
                mine.forEach { h -> item(key = "s:" + hitKey(h)) { HitRow(model, vm, h, marked = false, playing = queue.current?.id, onSong = ::playSong) } }
            }
            if (others.isNotEmpty()) {
                item(key = "s:others") {
                    Text(
                        if (mine.isEmpty()) "No ${ui.segment.label.lowercase()} by that name. More results:" else "More results",
                        color = Retro.InkFaint, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 2.dp),
                    )
                }
                others.forEach { h -> item(key = "s:o:" + hitKey(h)) { HitRow(model, vm, h, marked = true, playing = queue.current?.id, onSong = ::playSong) } }
            }
            res.more.forEach { (s, n) ->
                item(key = "s:more:" + s.name) {
                    JCardRow("→", "See all in ${s.label}", "${n} more", onClick = { model.segment(s) })
                }
            }
        }
        view.artists?.forEach { a ->
            item(key = "ar:" + a.name) {
                JCardRow(a.name.take(1).uppercase(), a.name, "${plural(a.albums, "album")} · ${plural(a.tracks, "song")}", onClick = { model.open(LibDrill.Artist(a.name)) })
            }
        }
        if (top is LibDrill.Artist || top is LibDrill.Genre) {
            item(key = "playall") {
                PlayRow(
                    onPlay = { model.playAll(artist = (top as? LibDrill.Artist)?.name, genre = (top as? LibDrill.Genre)?.name) },
                    onShuffle = { model.playAll(artist = (top as? LibDrill.Artist)?.name, genre = (top as? LibDrill.Genre)?.name, shuffle = true) },
                )
            }
        }
        view.albums?.let { albums ->
            if (grid) {
                // Three covers to a row: about 120 dp each on the phone's width.
                albums.chunked(3).forEachIndexed { r, row ->
                    item(key = "alg:$r:" + row.first().key) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            row.forEach { al -> AlbumTile(model, al, showBy = top !is LibDrill.Artist, Modifier.weight(1f)) { model.open(LibDrill.Album(al.key, al.title, al.by)) } }
                            repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                        }
                    }
                }
            } else {
                albums.forEach { al -> item(key = "al:" + al.key) { AlbumRow(model, al, showBy = top !is LibDrill.Artist) { model.open(LibDrill.Album(al.key, al.title, al.by)) } } }
            }
        }
        view.tracks?.let { tracks ->
            if (view.albumOrder && tracks.isNotEmpty()) {
                item(key = "albumhead") {
                    val first = tracks.first()
                    // The album's best file, as the grid shows it.
                    val best = tracks.maxWith(compareBy<io.github.momonekomata.sidea.library.LibTrack>({ SignalPaths.isLossless(it.codec) }, { it.sampleRate }, { it.bits ?: 0 }))
                    Text(
                        listOfNotNull(first.albumBy, first.year?.toString(), plural(tracks.size, "song"), formatLabel(best.codec, best.sampleRate, best.bits)).joinToString(" · "),
                        color = Retro.InkFaint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    )
                    PlayRow(onPlay = { model.play(tracks, 0) }, onShuffle = { model.play(tracks, 0, shuffle = true) })
                }
            }
            tracks.forEachIndexed { i, t ->
                item(key = "t:" + t.id) {
                    val number = when {
                        view.albumOrder -> t.track?.let { n -> (t.disc?.takeIf { it > 1 }?.let { "$it." } ?: "") + "%02d".format(n) } ?: "%02d".format(i + 1)
                        // A list in its own order: its numbers.
                        focusList != null -> "%02d".format(i + 1)
                        else -> ""
                    }
                    val detail = if (view.albumOrder) listOfNotNull(t.artist.takeIf { it.isNotBlank() && it != t.albumBy }, formatLabel(t.codec, t.sampleRate, t.bits)).joinToString(" · ")
                    else listOf(t.artist.ifBlank { t.albumBy }, t.album, formatLabel(t.codec, t.sampleRate, t.bits)).filter { it.isNotBlank() }.joinToString(" · ")
                    JCardRow(
                        number, t.title, detail,
                        current = queue.current?.id == t.id,
                        // Inside a playlist the menu can take the song out of it.
                        onLongClick = {
                            val at = playlist?.songs?.indexOfFirst { it.id == t.id } ?: -1
                            if (playlist != null && at >= 0) vm.openSongMenu(t, playlist.id, at) else vm.openSongMenu(t)
                        },
                        onClick = { model.play(tracks, i) },
                    )
                }
            }
            if (tracks.isEmpty() && songs > 0) item(key = "none") { JCardNote(if (listName != null && ui.filter.count == 1 && ui.filter.query.isBlank()) "None of this list's songs are in the Library yet." else "No songs match.") }
        }
        view.genres?.forEach { g ->
            item(key = "g:" + g.name) {
                JCardRow("♪", g.name, "${plural(g.albums, "album")} · ${plural(g.tracks, "song")}", onClick = { model.open(LibDrill.Genre(g.name)) })
            }
        }
    }
    if (filtering) {
        FilterSheet(
            ui.filter, ui.facets, Library.sources.value.map { it.id to it.name },
            onApply = { model.filter(it); filtering = false }, onDismiss = { filtering = false },
        )
    }
    if (picking) {
        PlaylistPicker(
            playlists, current = focusList,
            onPick = { model.showList(it); picking = false },
            onNew = { vm.newPlaylist(it, null) },
            onDismiss = { picking = false },
        )
    }
    deleting?.let { p ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete “${p.name}”?") },
            text = { Text("The songs stay where they are; only the list goes.") },
            confirmButton = { TextButton({ vm.deletePlaylist(p.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun hitKey(h: io.github.momonekomata.sidea.library.LibHit) = when {
    h.artist != null -> "ar:" + h.artist.name
    h.album != null -> "al:" + h.album.key
    h.track != null -> "t:" + h.track.id
    else -> "g:" + h.genre?.name
}

/**
 * One search result. [marked] results come from another tab than the one open, so they say what
 * they are (an album shows its cover, which says it already).
 */
@Composable
private fun HitRow(
    model: io.github.momonekomata.sidea.library.LibraryModel,
    vm: MainViewModel,
    h: io.github.momonekomata.sidea.library.LibHit,
    marked: Boolean,
    playing: String?,
    onSong: (io.github.momonekomata.sidea.library.LibTrack) -> Unit,
) {
    fun kind(k: String, rest: String) = if (marked) "$k · $rest" else rest
    h.artist?.let { a ->
        JCardRow(a.name.take(1).uppercase(), a.name, kind("Artist", "${plural(a.albums, "album")} · ${plural(a.tracks, "song")}"), onClick = { model.open(LibDrill.Artist(a.name)) })
    }
    h.album?.let { al -> AlbumRow(model, al, showBy = true) { model.open(LibDrill.Album(al.key, al.title, al.by)) } }
    h.track?.let { t ->
        JCardRow(
            "", t.title,
            kind("Song", listOf(t.artist.ifBlank { t.albumBy }, t.album, formatLabel(t.codec, t.sampleRate, t.bits)).filter { it.isNotBlank() }.joinToString(" · ")),
            current = playing == t.id,
            onLongClick = { vm.openSongMenu(t) },
            onClick = { onSong(t) },
        )
    }
    h.genre?.let { g ->
        JCardRow("♪", g.name, kind("Genre", "${plural(g.albums, "album")} · ${plural(g.tracks, "song")}"), onClick = { model.open(LibDrill.Genre(g.name)) })
    }
}

/** The Playlists tab: pick one to show its songs, or make a new one. */
@Composable
private fun PlaylistPicker(
    playlists: List<io.github.momonekomata.sidea.sources.Playlist>,
    current: String?,
    onPick: (String) -> Unit,
    onNew: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var naming by remember { mutableStateOf(false) }
    if (naming) {
        PlaylistNameDialog(onCreate = { onNew(it); naming = false }, onDismiss = { naming = false })
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playlists") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (playlists.isEmpty()) Text("No playlists yet. Make one here, or long-press a song and pick Add to playlist.", fontSize = 13.sp, color = Retro.Muted)
                playlists.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(p.id) }.padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("▤", color = Retro.Orange, modifier = Modifier.width(30.dp))
                        Text(p.name, fontSize = 15.sp, fontWeight = if (p.id == current) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(plural(p.songs.size, "song"), fontSize = 12.sp, color = Retro.Muted)
                    }
                }
                Row(Modifier.fillMaxWidth().clickable { naming = true }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("+", color = Retro.Orange, modifier = Modifier.width(30.dp))
                    Text("New playlist…", fontSize = 15.sp)
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Chip(label: String, on: Boolean, description: String? = null, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) Color.White else Retro.Ink,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier
            // A square key: the tab showing is the orange one, pressed in.
            .padding(vertical = 2.dp)
            .keyFace(orange = on, down = on)
            .clickable(onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

@Composable
private fun PlayRow(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).keyFace(orange = true).clickable(onClick = onPlay).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text("▶ Play", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f).keyFace().clickable(onClick = onShuffle).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text("⇄ Shuffle", color = Retro.Ink, fontWeight = FontWeight.Bold)
        }
    }
}

/** An album's cover, read the first time it's on screen. */
@Composable
private fun albumCover(model: io.github.momonekomata.sidea.library.LibraryModel, al: LibAlbum): ImageBitmap? {
    val context = LocalContext.current
    val cover by produceState<ImageBitmap?>(null, al.sampleId) {
        val track = withContext(Dispatchers.IO) { Library.db.tracks(LibFilter(), album = al.key, limit = 1).firstOrNull()?.let(model::trackOf) }
        value = track?.let { ArtworkStore.load(context, it)?.asImageBitmap() }
    }
    return cover
}

/** An album in the grid: a big square cover with the title and who it's by under it. */
@Composable
private fun AlbumTile(model: io.github.momonekomata.sidea.library.LibraryModel, al: LibAlbum, showBy: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cover = albumCover(model, al)
    Column(modifier.clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(Retro.CreamShade), contentAlignment = Alignment.Center) {
            if (cover != null) {
                Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            } else {
                Text(al.title.take(1).uppercase(), color = Retro.InkFaint, fontWeight = FontWeight.Bold, fontSize = 28.sp)
            }
        }
        Text(al.title, color = Retro.Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Text(
            listOfNotNull(al.by.takeIf { showBy }, al.year?.toString().takeIf { !showBy }).joinToString(" · ").ifBlank { formatLabel(al.codec, al.bestRate, al.bestBits) },
            color = Retro.InkFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** An album in the list: its cover, title, who by, year and format. */
@Composable
private fun AlbumRow(model: io.github.momonekomata.sidea.library.LibraryModel, al: LibAlbum, showBy: Boolean, onClick: () -> Unit) {
    val cover = albumCover(model, al)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val c = cover
        if (c != null) {
            Image(c, null, contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(3.dp)))
        } else {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(3.dp)).background(Retro.CreamShade), contentAlignment = Alignment.Center) {
                Text(al.title.take(1).uppercase(), color = Retro.InkFaint, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(al.title, color = Retro.Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(al.by.takeIf { showBy }, al.year?.toString(), "${al.tracks} songs", formatLabel(al.codec, al.bestRate, al.bestBits)).joinToString(" · ")
            Text(detail, color = Retro.InkFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Focus, after Roon's: every way to narrow the Library that its scan can back up, with counts
 * from what's actually in it. Each section shows its top few; View more shows the rest.
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun FilterSheet(
    current: LibFilter,
    facets: LibFacets?,
    sources: List<Pair<String, String>>,
    onApply: (LibFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    var f by remember(current) { mutableStateOf(current) }
    var from by remember(current) { mutableStateOf(current.yearFrom?.toString().orEmpty()) }
    var to by remember(current) { mutableStateOf(current.yearTo?.toString().orEmpty()) }
    val periods = listOf(1 to "Day", 7 to "Week", 30 to "Month", 91 to "3 months", 182 to "6 months", 365 to "Year")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Focus") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (facets == null) {
                    Text("Reading what's in the Library…")
                    return@Column
                }
                LabeledSwitch("Lossless only", f.losslessOnly) { f = f.copy(losslessOnly = it) }
                Facet("Genres", facets.genres) { (g, n) -> Toggle("$g ($n)", g in f.genres) { f = f.copy(genres = f.genres.flip(g)) } }
                if (facets.decades.isNotEmpty()) {
                    Facet("Release date", facets.decades) { (d, n) ->
                        Toggle("${d}s ($n)", from == "$d" && to == "${d + 9}") {
                            if (from == "$d" && to == "${d + 9}") { from = ""; to = "" } else { from = "$d"; to = "${d + 9}" }
                        }
                    }
                    facets.years?.let { years ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(from, { from = it.filter(Char::isDigit).take(4) }, label = { Text("From ${years.first}") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                            OutlinedTextField(to, { to = it.filter(Char::isDigit).take(4) }, label = { Text("To ${years.last}") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f))
                        }
                    }
                }
                Facet("Performers", facets.artists) { (a, n) -> Toggle("$a ($n)", a in f.artists) { f = f.copy(artists = f.artists.flip(a)) } }
                Facet("Composers", facets.composers) { (c, n) -> Toggle("$c ($n)", c in f.composers) { f = f.copy(composers = f.composers.flip(c)) } }
                Facet("Labels", facets.labels) { (l, n) -> Toggle("$l ($n)", l in f.labels) { f = f.copy(labels = f.labels.flip(l)) } }
                if (facets.played > 0) {
                    Facet("Most played", listOf(25, 50, 100, 250, 500, 1000).filter { it / 2 < facets.played }) { n ->
                        Toggle("Top $n played", f.topPlayed == n) { f = f.copy(topPlayed = if (f.topPlayed == n) null else n) }
                    }
                    Facet("Played in the last", periods) { (d, label) ->
                        Toggle(label, f.playedWithinDays == d) { f = f.copy(playedWithinDays = if (f.playedWithinDays == d) null else d) }
                    }
                }
                Facet("Added in the last", periods) { (d, label) ->
                    Toggle(label, f.addedWithinDays == d) { f = f.copy(addedWithinDays = if (f.addedWithinDays == d) null else d) }
                }
                if (facets.compilations > 0) {
                    Facet("Other", listOf(Unit)) { Toggle("Compilation (${facets.compilations})", f.compilationsOnly) { f = f.copy(compilationsOnly = !f.compilationsOnly) } }
                }
                if (sources.size > 1) {
                    Facet("Storage locations", sources) { (id, name) -> Toggle(name, id in f.sources) { f = f.copy(sources = f.sources.flip(id)) } }
                }
                Facet("Inspector", INSPECT.entries.toList()) { (k, label) -> Toggle(label, k in f.inspect) { f = f.copy(inspect = f.inspect.flip(k)) } }
                Facet("Format", facets.codecs) { (c, n) -> Toggle("$c ($n)", c in f.codecs) { f = f.copy(codecs = f.codecs.flip(c)) } }
                Facet("Sample rate", facets.rates) { (r, n) ->
                    val label = if (r >= 2_822_400) "DSD${r / 44_100}" else SignalPaths.khz(r)
                    Toggle("$label ($n)", r in f.rates) { f = f.copy(rates = f.rates.flip(r)) }
                }
                Facet("Bit depth", facets.bits) { (b, n) -> Toggle((if (b == 1) "DSD" else "$b-bit") + " ($n)", b in f.bits) { f = f.copy(bits = f.bits.flip(b)) } }
                Facet("Channel layout", facets.channels) { (c, n) ->
                    Toggle((when (c) { 1 -> "1.0 mono"; 2 -> "2.0 stereo"; else -> "Multichannel" }) + " ($n)", c in f.channels) { f = f.copy(channels = f.channels.flip(c)) }
                }
            }
        },
        confirmButton = {
            TextButton({ onApply(f.copy(yearFrom = from.toIntOrNull(), yearTo = to.toIntOrNull())) }) { Text("Show") }
        },
        dismissButton = {
            TextButton({ onApply(LibFilter(query = current.query)) }) { Text("Clear all") }
        },
    )
}

/** A Focus section: its title, the first six choices, and View more for the rest. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Facet(title: String, items: List<T>, item: @Composable (T) -> Unit) {
    if (items.isEmpty()) return
    var all by remember { mutableStateOf(false) }
    Text(title.uppercase(), fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        (if (all) items else items.take(6)).forEach { item(it) }
    }
    if (items.size > 6) {
        Text(if (all) "View less" else "View more (${items.size - 6})", color = Retro.Orange, fontSize = 12.sp, modifier = Modifier.clickable { all = !all }.padding(vertical = 2.dp))
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(on, onClick, label = { Text(label, fontSize = 12.sp) })
}

private fun <T> Set<T>.flip(v: T) = if (v in this) this - v else this + v

internal fun agoLabel(atMs: Long): String {
    val m = (System.currentTimeMillis() - atMs) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / (60 * 24)} days ago"
    }
}
