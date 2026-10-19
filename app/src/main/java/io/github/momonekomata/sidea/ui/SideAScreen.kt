package io.github.momonekomata.sidea.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.momonekomata.sidea.BrowseState
import io.github.momonekomata.sidea.MainViewModel
import io.github.momonekomata.sidea.Settings
import io.github.momonekomata.sidea.SourceTab
import io.github.momonekomata.sidea.audio.EngineState
import io.github.momonekomata.sidea.audio.EqConfig
import io.github.momonekomata.sidea.audio.SignalPaths
import io.github.momonekomata.sidea.audio.StreamFacts
import io.github.momonekomata.sidea.sources.ArtworkStore
import io.github.momonekomata.sidea.audio.Player
import io.github.momonekomata.sidea.audio.QueueState
import io.github.momonekomata.sidea.sources.Entry
import io.github.momonekomata.sidea.sources.NasServer
import io.github.momonekomata.sidea.sources.Tags
import io.github.momonekomata.sidea.sources.TagStore
import io.github.momonekomata.sidea.sources.displayAlbum
import io.github.momonekomata.sidea.sources.displayArtist
import io.github.momonekomata.sidea.sources.displayTitle
import io.github.momonekomata.sidea.streaming.RemoteNowPlaying
import io.github.momonekomata.sidea.streaming.StreamingApp
import kotlinx.coroutines.delay

@Composable
fun SideAScreen(vm: MainViewModel, pickFolder: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val queue by Player.queue.collectAsStateWithLifecycle()
    val engine by Player.state.collectAsStateWithLifecycle()
    val remote by vm.streaming.nowPlaying.collectAsStateWithLifecycle()
    val tags by TagStore.tags.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    var lyricsOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    // Drives the position readout and the reels; remote positions are extrapolated per frame.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            Player.refreshPosition()
            tick++
            delay(250)
        }
    }

    val isNas = ui.tab == SourceTab.NAS
    BackHandler(enabled = !(lyricsOpen && lyrics.lyrics != null) && ((ui.tab == SourceTab.LOCAL && (ui.local?.path?.size ?: 0) > 1) || (isNas && ui.nas != null))) {
        vm.up(isNas)
    }
    BackHandler(enabled = lyricsOpen && lyrics.lyrics != null) { lyricsOpen = false }

    val app = ui.tab.app
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    val localArt by vm.nowArt.collectAsStateWithLifecycle()
    val remoteBitmap = app?.let { remote[it]?.art }
    val remoteArt = remember(remoteBitmap) { remoteBitmap?.asImageBitmap() }
    val localImage = remember(localArt) { localArt?.asImageBitmap() }
    val deck = if (app != null) remoteDeck(vm, app, remote[app], tick, remoteArt) else localDeck(queue, engine, tags, prefs.eq, localImage)
    var eqOpen by remember { mutableStateOf(false) }
    val sheet = lyrics.lyrics
    val showSheet = lyricsOpen && sheet != null

    Column(
        Modifier
            .fillMaxSize()
            .background(Retro.Background)
            .safeDrawingPadding(),
    ) {
        Header(eqActive = prefs.eq.isActive, onEq = { eqOpen = true }, onSettings = { settingsOpen = true })
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            val card: @Composable (Modifier) -> Unit = { m ->
                if (showSheet && sheet != null) {
                    LyricsCard(deck.title.ifBlank { "Lyrics" }, sheet, deck.positionMs, m, onClose = { lyricsOpen = false })
                } else {
                    SourceCard(vm, ui.tab, queue, remote, tags, pickFolder, m)
                }
            }
            if (maxWidth >= 600.dp) {
                // Unfolded: J-card on the left page; deck and lyrics on the right, like the open case.
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    card(Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp))
                    Column(Modifier.weight(1.15f).fillMaxHeight().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                        Deck(deck)
                        if (sheet != null && !showSheet) {
                            LyricsPanel(sheet, deck.positionMs, Modifier.fillMaxWidth().weight(1f), compact = false) { lyricsOpen = true }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Deck(deck)
                    if (sheet != null && !showSheet) {
                        LyricsPanel(sheet, deck.positionMs, Modifier.fillMaxWidth().height(84.dp), compact = true) { lyricsOpen = true }
                    }
                    card(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
        SourceTabs(ui.tab, vm::selectTab)
    }

    ui.certPrompt?.let { CertificateDialog(it, onTrust = { vm.trustCertificate(it) }, onDismiss = vm::dismissCertPrompt) }
    if (eqOpen) EqScreen(prefs.eq, onChange = { c -> Settings.update { it.copy(eq = c) } }, onDismiss = { eqOpen = false })
    if (settingsOpen) {
        SettingsDialog(prefs, onChange = { p -> Settings.update { p } }, onDismiss = { settingsOpen = false })
    }
}

@Composable
private fun Header(eqActive: Boolean, onEq: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.Top) {
        Text("Side A", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
        Text("+", color = Retro.Orange, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onEq) { Icon(Icons.Filled.Equalizer, "Equalizer", tint = if (eqActive) Color(0xFF6FB8FF) else Retro.Muted) }
        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = Retro.Muted) }
    }
}

@Composable
private fun SourceTabs(selected: SourceTab, onSelect: (SourceTab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SourceTab.entries.forEach { tab ->
            val on = tab == selected
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onSelect(tab) }.padding(4.dp)) {
                Text(
                    tab.label.uppercase(),
                    color = if (on) Retro.Orange else Retro.Muted,
                    fontFamily = Retro.Mono,
                    fontSize = 11.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    letterSpacing = 1.sp,
                )
                Text(if (on) "▲" else " ", color = Retro.Orange, fontSize = 8.sp)
            }
        }
    }
}

// ---- Deck models ----

private fun localDeck(queue: QueueState, engine: EngineState, tags: Map<String, Tags>, eq: EqConfig, art: ImageBitmap?): DeckModel {
    val t = queue.current
    val facts = engine.output
    val status = when {
        t == null -> "Pick a song from Local or NAS"
        engine.error != null -> "ERROR · ${engine.error}"
        facts == null -> "Threading the tape…"
        else -> summary(facts, engine.outputNote)
    }
    return DeckModel(
        title = t?.displayTitle(tags).orEmpty(),
        subtitle = listOf(t?.displayArtist(tags), t?.displayAlbum(tags)).filter { !it.isNullOrBlank() }.joinToString(" · "),
        positionMs = engine.positionMs,
        durationMs = engine.durationMs,
        playing = engine.isPlaying,
        status = status,
        statusColor = if (engine.error != null) Retro.Amber else Retro.Muted,
        path = if (engine.error == null) facts?.let { SignalPaths.describe(it, eq) } else null,
        art = art,
        onToggle = Player::togglePlay,
        onNext = { Player.next() },
        onPrevious = Player::previous,
        onSeek = Player::seekTo,
    )
}

/** One line next to the badge: what the file is and where it goes. */
private fun summary(f: StreamFacts, note: String?): String {
    val bits = f.sourceBits?.takeIf { it > 1 }?.let { "/$it-bit" } ?: ""
    val src = if (f.dsd != null) "DSD${f.sourceRate / 44_100}" else "${f.codec} ${SignalPaths.khz(f.sourceRate)}$bits"
    val dest = f.deviceName ?: f.device.label
    return when {
        f.bitPerfect -> "$src → $dest"
        else -> "$src → ${note ?: "Android mixer"}"
    }
}

@Suppress("UNUSED_PARAMETER") // tick forces recomposition so the extrapolated position moves
private fun remoteDeck(vm: MainViewModel, app: StreamingApp, np: RemoteNowPlaying?, tick: Long, art: ImageBitmap?) = DeckModel(
    title = np?.title.orEmpty(),
    subtitle = listOf(np?.artist, np?.album).filter { !it.isNullOrBlank() }.joinToString(" · "),
    positionMs = np?.positionMs ?: 0,
    durationMs = np?.durationMs ?: 0,
    playing = np?.isPlaying == true,
    status = if (np == null) "${app.label} is not playing" else "REMOTE · audio path and quality set in ${app.label}",
    statusColor = Retro.Muted,
    art = art,
    onToggle = { vm.streaming.playPause(app) },
    onNext = { vm.streaming.next(app) },
    onPrevious = { vm.streaming.previous(app) },
    onSeek = { vm.streaming.seekTo(app, it) },
)

// ---- J-card contents per source ----

@Composable
private fun SourceCard(
    vm: MainViewModel,
    tab: SourceTab,
    queue: QueueState,
    remote: Map<StreamingApp, RemoteNowPlaying>,
    tags: Map<String, Tags>,
    pickFolder: () -> Unit,
    modifier: Modifier,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    when (tab) {
        SourceTab.LOCAL -> {
            val local = ui.local
            if (local == null) {
                JCard("Local music", "no folder chosen", modifier) {
                    item {
                        JCardNote("Choose the folder that holds your music. FLAC, WAV, ALAC/M4A and MP3 are read in place.") {
                            CardButton("Choose folder", pickFolder)
                        }
                    }
                }
            } else {
                BrowserCard(local, queue, tags, modifier, onUp = { vm.up(false) }, onFolder = { vm.openFolder(false, it) }, onSong = { vm.play(false, it) }, onRetry = { vm.retry(false) }) {
                    item { JCardRow("", "Change music folder…", onClick = pickFolder) }
                }
            }
        }
        SourceTab.NAS -> NasCard(vm, ui.nas, ui.nasServers, queue, tags, modifier)
        else -> {
            val app = tab.app!!
            StreamingCard(vm, app, remote[app], ui.streamingAccess, modifier)
        }
    }
}

@Composable
private fun BrowserCard(
    state: BrowseState,
    queue: QueueState,
    tags: Map<String, Tags>,
    modifier: Modifier,
    onUp: () -> Unit,
    onFolder: (Entry.Folder) -> Unit,
    onSong: (Entry.Song) -> Unit,
    onRetry: () -> Unit,
    footer: androidx.compose.foundation.lazy.LazyListScope.() -> Unit = {},
) {
    val songs = state.entries.count { it is Entry.Song }
    val folders = state.entries.size - songs
    val context = LocalContext.current
    val firstSong = state.entries.firstOrNull { it is Entry.Song } as Entry.Song?
    val cover by produceState<ImageBitmap?>(null, firstSong?.track?.id) {
        value = firstSong?.let { ArtworkStore.load(context, it.track)?.asImageBitmap() }
    }
    JCard(state.currentName, "$songs songs · $folders folders · ${state.browser.title}", modifier, art = cover) {
        if (state.path.size > 1) item { JCardRow("‹", "Back to ${state.path[state.path.size - 2].first}", onClick = onUp) }
        when {
            state.loading -> item { JCardNote("Reading…") }
            state.error != null -> item { JCardNote(state.error) { CardButton("Try again", onRetry) } }
            else -> {
                var n = 0
                state.entries.forEach { e ->
                    when (e) {
                        is Entry.Folder -> item { JCardRow("▸", e.name, "folder", onClick = { onFolder(e) }) }
                        is Entry.Song -> {
                            n++
                            val number = "%02d".format(n)
                            item {
                                val t = tags[e.track.id]
                                val detail = listOfNotNull(t?.artist, t?.album).joinToString(" · ").ifEmpty { e.name }
                                JCardRow(t?.trackNumber?.let { "%02d".format(it) } ?: number, e.track.displayTitle(tags), detail, current = queue.current?.id == e.track.id, onClick = { onSong(e) })
                            }
                        }
                    }
                }
                if (state.entries.isEmpty()) item { JCardNote("No folders or music files here.") }
            }
        }
        footer()
    }
}

@Composable
private fun NasCard(vm: MainViewModel, nas: BrowseState?, servers: List<NasServer>, queue: QueueState, tags: Map<String, Tags>, modifier: Modifier) {
    var editing by remember { mutableStateOf<NasServer?>(null) }
    var adding by remember { mutableStateOf(false) }
    if (nas != null) {
        BrowserCard(nas, queue, tags, modifier, onUp = { vm.up(true) }, onFolder = { vm.openFolder(true, it) }, onSong = { vm.play(true, it) }, onRetry = { vm.retry(true) }) {
            item { JCardRow("⏏", "Disconnect", onClick = vm::closeServer) }
        }
    } else {
        JCard("NAS", "${servers.size} servers · WebDAV / FTPS", modifier) {
            servers.forEach { s ->
                item {
                    JCardRow(s.kind.name.take(3), s.name, s.address, action = "Edit" to { editing = s }, onClick = { vm.openServer(s) })
                }
            }
            item { JCardRow("+", "Add server", onClick = { adding = true }) }
        }
    }
    if (adding) NasServerDialog(null, servers, onSave = { vm.saveServer(it); adding = false }, onDelete = {}, onDismiss = { adding = false })
    editing?.let { s ->
        NasServerDialog(s, servers, onSave = { vm.saveServer(it); editing = null }, onDelete = { vm.deleteServer(it); editing = null }, onDismiss = { editing = null })
    }
}

@Composable
private fun StreamingCard(vm: MainViewModel, app: StreamingApp, np: RemoteNowPlaying?, access: Boolean, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    JCard(app.label, if (np != null && np.queue.isNotEmpty()) "${np.queue.size} in queue" else "via ${app.label}", modifier) {
        when {
            !access -> item {
                JCardNote("To show and control what ${app.label} plays, Side A needs notification access. It only uses it to find music players; it never reads your notifications.") {
                    CardButton("Allow access") { context.startActivity(vm.streaming.accessSettingsIntent()) }
                }
            }
            !vm.streaming.isInstalled(app) -> item {
                JCardNote("${app.label} isn't installed.") { CardButton("Get ${app.label}") { vm.streaming.launch(app) } }
            }
            np == null || np.queue.isEmpty() -> item {
                val msg = if (np == null) "Start a playlist in ${app.label}; the deck follows along."
                else "${app.label} doesn't share its up-next list, so only the current song shows here."
                JCardNote(msg) { CardButton("Open ${app.label}") { vm.streaming.launch(app) } }
            }
            else -> {
                np.queue.forEachIndexed { i, q ->
                    item {
                        JCardRow("%02d".format(i + 1), q.title, q.subtitle, current = q.id == np.activeQueueId) {
                            vm.streaming.playQueueItem(app, q.id)
                        }
                    }
                }
                item { JCardRow("↗", "Open ${app.label}", onClick = { vm.streaming.launch(app) }) }
            }
        }
    }
}

@Composable
fun CardButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Retro.Ink),
        border = androidx.compose.foundation.BorderStroke(1.dp, Retro.Ink),
    ) { Text(text) }
}
