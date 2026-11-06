package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.Image
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
import io.github.momonekomata.sidea.sources.ArtworkStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "FLAC 24/96", "DSD128", "MP3 44.1": a song's format the way the deck's badge talks. */
fun formatLabel(codec: String, rate: Int, bits: Int?): String = when {
    codec == "DSD" && rate > 0 -> "DSD${rate / 44_100}"
    rate <= 0 -> codec
    bits != null && bits > 1 -> "$codec $bits/${SignalPaths.khz(rate).removeSuffix(" kHz")}"
    else -> "$codec ${SignalPaths.khz(rate).removeSuffix(" kHz")}"
}

/**
 * The Library: every song under the folders you added, by artist, album, title, genre or source,
 * searched and filtered on any of their tags or formats. Search, the tabs and Filter stay on top.
 */
@Composable
fun LibraryCard(vm: MainViewModel, queue: QueueState, pickFolder: () -> Unit, modifier: Modifier) {
    val model = vm.library
    val ui by model.ui.collectAsStateWithLifecycle()
    val scan by Library.scan.collectAsStateWithLifecycle()
    var filtering by remember { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    val top = ui.stack.lastOrNull()
    val view = ui.view
    val list = rememberLazyListState()
    // A new list starts at the top.
    LaunchedEffect(ui.segment, ui.stack.size, top) { list.scrollToItem(0) }

    val (songs, albums, artists) = ui.totals
    val subtitle = when {
        scan.running -> "scanning ${scan.source} · ${scan.folders} folders, ${scan.songs} songs" + if (scan.toRead > 0) " · reading ${scan.read}/${scan.toRead}" else ""
        else -> "$songs songs · $albums albums · $artists artists"
    }
    val labels: List<String> = view.artists?.map { it.name } ?: view.albums?.map { it.title } ?: view.tracks?.takeIf { !view.albumOrder }?.map { it.title } ?: view.genres?.map { it.name } ?: emptyList()
    // Rows above the list itself: Back, and Play/Shuffle under an artist or genre.
    val above = (if (top != null) 1 else 0) + (if (top is LibDrill.Artist || top is LibDrill.Genre) 1 else 0)
    val gridded = grid && view.albums != null
    val letters = remember(labels, above, gridded) {
        when {
            labels.size < 20 -> emptyList()
            // Three albums to a row in the grid.
            gridded -> letterIndex(labels, 0).map { (c, i) -> c to above + i / 3 }
            else -> letterIndex(labels, above)
        }
    }

    JCard(top?.title ?: "Library", subtitle, modifier, listState = list, letters = letters, pinned = {
        Column {
            OutlinedTextField(
                value = ui.filter.query,
                onValueChange = model::search,
                singleLine = true,
                placeholder = { Text("Search titles, artists, albums, genres…", color = Retro.InkFaint) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Retro.Ink, unfocusedTextColor = Retro.Ink,
                    focusedBorderColor = Retro.Orange, unfocusedBorderColor = Retro.InkFaint, cursorColor = Retro.Orange,
                ),
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LibSegment.entries.forEach { s -> Chip(s.label, ui.segment == s && top == null) { model.segment(s) } }
                Chip(if (ui.filter.count > 0) "Focus · ${ui.filter.count}" else "Focus", ui.filter.count > 0) {
                    model.loadFacets()
                    filtering = true
                }
                if (view.albums != null) Chip(if (grid) "☰ List" else "▦ Grid", false) { grid = !grid }
            }
        }
    }) {
        if (top != null) item(key = "back") { JCardRow("‹", "Back", onClick = { model.back() }) }
        if (songs == 0 && !scan.running && top == null && ui.segment != LibSegment.SOURCES) {
            item(key = "empty") {
                JCardNote("Point the Library at your music: open a folder in Local or Remote and tap \"Add this folder to the Library\", or add one from the phone here. Everything inside gets scanned in.") {
                    CardButton("Add a folder on the phone", pickFolder)
                }
            }
        }
        view.artists?.forEach { a ->
            item(key = "ar:" + a.name) {
                JCardRow(a.name.take(1).uppercase(), a.name, "${a.albums} album${if (a.albums == 1) "" else "s"} · ${a.tracks} songs", onClick = { model.open(LibDrill.Artist(a.name)) })
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
                    Text(
                        listOfNotNull(first.albumBy, first.year?.toString(), "${tracks.size} songs", formatLabel(first.codec, first.sampleRate, first.bits)).joinToString(" · "),
                        color = Retro.InkFaint, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp),
                    )
                    PlayRow(onPlay = { model.play(tracks, 0) }, onShuffle = { model.play(tracks, 0, shuffle = true) })
                }
            }
            tracks.forEachIndexed { i, t ->
                item(key = "t:" + t.id) {
                    val number = when {
                        view.albumOrder -> t.track?.let { n -> (t.disc?.takeIf { it > 1 }?.let { "$it." } ?: "") + "%02d".format(n) } ?: "%02d".format(i + 1)
                        else -> ""
                    }
                    val detail = if (view.albumOrder) listOfNotNull(t.artist.takeIf { it.isNotBlank() && it != t.albumBy }, formatLabel(t.codec, t.sampleRate, t.bits)).joinToString(" · ")
                    else listOf(t.artist.ifBlank { t.albumBy }, t.album, formatLabel(t.codec, t.sampleRate, t.bits)).filter { it.isNotBlank() }.joinToString(" · ")
                    JCardRow(
                        number, t.title, detail,
                        current = queue.current?.id == t.id,
                        onLongClick = { vm.openSongMenu(t) },
                        onClick = { model.play(tracks, i) },
                    )
                }
            }
            if (tracks.isEmpty() && songs > 0) item(key = "none") { JCardNote("No songs match.") }
        }
        view.genres?.forEach { g ->
            item(key = "g:" + g.name) {
                JCardRow("♪", g.name, "${g.albums} album${if (g.albums == 1) "" else "s"} · ${g.tracks} songs", onClick = { model.open(LibDrill.Genre(g.name)) })
            }
        }
        view.sources?.let { sources ->
            sources.forEach { (src, count) ->
                item(key = "s:" + src.id) {
                    var removing by remember { mutableStateOf(false) }
                    val state = when {
                        src.lastError != null -> "couldn't scan: ${src.lastError}"
                        src.lastScan == 0L -> "not scanned yet"
                        else -> "scanned ${agoLabel(src.lastScan)}"
                    }
                    JCardRow(
                        if (src.isLocal) "PH" else "NET", src.name, "$count songs · $state",
                        action = "Rescan" to { Library.scan(src.id) },
                        onLongClick = { removing = true },
                        onClick = { model.open(LibDrill.Source(src.id, src.name)) },
                    )
                    if (removing) {
                        AlertDialog(
                            onDismissRequest = { removing = false },
                            title = { Text("Remove ${src.name}?") },
                            text = { Text("Its songs leave the Library (and favourites keep working). The files themselves aren't touched.") },
                            confirmButton = { TextButton({ Library.remove(src.id); removing = false }) { Text("Remove") } },
                            dismissButton = { TextButton({ removing = false }) { Text("Cancel") } },
                        )
                    }
                }
            }
            item(key = "s:add") { JCardRow("＋", "Add a folder on the phone", onClick = pickFolder) }
            item(key = "s:all") { JCardRow("↻", "Rescan everything", onClick = Library::scanAll) }
            item(key = "s:tip") {
                JCardNote("NAS and cloud folders: open one in the Remote tab and tap \"Add this folder to the Library\". Long-press a source to remove it. Sources are rescanned on start when they're half a day old.")
            }
        }
    }
    if (filtering) FilterSheet(ui.filter, ui.facets, Library.sources.value.map { it.id to it.name }, onApply = { model.filter(it); filtering = false }, onDismiss = { filtering = false })
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) Color.White else Retro.Ink,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (on) Retro.Orange else Retro.CreamShade)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun PlayRow(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Retro.Orange).clickable(onClick = onPlay).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text("▶ Play", color = Color.White, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(Retro.CreamShade).clickable(onClick = onShuffle).padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
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
private fun FilterSheet(current: LibFilter, facets: LibFacets?, sources: List<Pair<String, String>>, onApply: (LibFilter) -> Unit, onDismiss: () -> Unit) {
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

private fun agoLabel(atMs: Long): String {
    val m = (System.currentTimeMillis() - atMs) / 60_000
    return when {
        m < 1 -> "just now"
        m < 60 -> "$m min ago"
        m < 60 * 24 -> "${m / 60} h ago"
        else -> "${m / (60 * 24)} days ago"
    }
}
