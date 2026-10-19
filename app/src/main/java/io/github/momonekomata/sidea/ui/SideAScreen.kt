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
import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Configuration
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.momonekomata.sidea.BrowseState
import io.github.momonekomata.sidea.SearchState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.momonekomata.sidea.LyricsState
import androidx.compose.material3.TextButton
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
import io.github.momonekomata.sidea.streaming.OutputSeen
import io.github.momonekomata.sidea.streaming.StreamingApp
import io.github.momonekomata.sidea.lyrics.Lyrics
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay

@Composable
fun SideAScreen(vm: MainViewModel, pickFolder: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val queue by Player.queue.collectAsStateWithLifecycle()
    val engine by Player.state.collectAsStateWithLifecycle()
    val remote by vm.streaming.nowPlaying.collectAsStateWithLifecycle()
    val tags by TagStore.tags.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val deckApp by vm.deckSource.collectAsStateWithLifecycle()
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    val localArt by vm.nowArt.collectAsStateWithLifecycle()
    // Collected so the remote signal path redraws when another app's output changes.
    val remoteOut by vm.streaming.output.seen.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val speed by Player.speed.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    var eqOpen by remember { mutableStateOf(false) }
    // Cover or lyrics in the square; kept across songs, so lyrics stay up once chosen.
    var showLyrics by rememberSaveable { mutableStateOf(false) }

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
    BackHandler(enabled = (ui.tab == SourceTab.LOCAL && (ui.local?.path?.size ?: 0) > 1) || (isNas && ui.nas != null)) {
        vm.up(isNas)
    }

    val app = deckApp
    val remoteBitmap = app?.let { remote[it]?.art }
    val remoteArt = remember(remoteBitmap) { remoteBitmap?.asImageBitmap() }
    val localImage = remember(localArt) { localArt?.asImageBitmap() }
    val deck = (
        if (app != null) remoteDeck(vm, app, remote[app], tick, remoteArt, remoteOut)
        else localDeck(queue, engine, tags, prefs.eq, localImage, favorites.map { it.id }.toSet(), speed, vm::toggleFavorite)
    ).copy(
        lyrics = lyrics,
        showLyrics = showLyrics,
        onToggleLyrics = { showLyrics = !showLyrics },
        onRetryLyrics = vm::retryLyrics,
    )
    // A dot on the tab of whatever is playing, so it's easy to find again.
    val playing = buildSet {
        if (engine.isPlaying) queue.current?.let { add(if (ui.nas?.entries?.any { e -> e is Entry.Song && e.track.id == it.id } == true) SourceTab.NAS else SourceTab.LOCAL) }
        remote.values.filter { it.isPlaying }.forEach { np -> SourceTab.entries.firstOrNull { it.app == np.app }?.let(::add) }
    }

    // Landscape: full screen. The system bars hide (swipe from an edge to peek), and the header's
    // buttons move into the tab row so the cards and the deck get the whole height.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    ImmersiveMode(landscape)
    val headerIcons: @Composable RowScope.() -> Unit = {
        HeaderIcons(eqActive = prefs.eq.isActive, onEq = { eqOpen = true }, onSettings = { settingsOpen = true })
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Retro.Background)
            .then(if (landscape) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier.safeDrawingPadding()),
    ) {
        if (!landscape) Header(headerIcons)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= 600.dp) {
                WideLayout(vm, ui.tab, deck, playing, short = maxHeight < 520.dp, trailing = if (landscape) headerIcons else null) { m ->
                    SourceCard(vm, ui.tab, queue, remote, tags, pickFolder, m)
                }
            } else {
                PhoneLayout(vm, ui.tab, deck, lyrics, playing) { tab, m -> SourceCard(vm, tab, queue, remote, tags, pickFolder, m) }
            }
        }
    }

    ui.certPrompt?.let { CertificateDialog(it, onTrust = { vm.trustCertificate(it) }, onDismiss = vm::dismissCertPrompt) }
    if (eqOpen) EqScreen(prefs.eq, onChange = { c -> Settings.update { it.copy(eq = c) } }, onDismiss = { eqOpen = false })
    if (settingsOpen) {
        SettingsDialog(prefs, onChange = { p -> Settings.update { p } }, onDismiss = { settingsOpen = false })
    }
}

/**
 * Wide screens. Unfolded: the card on the left page, the deck on the right, its square sized so
 * the controls fit under it. A phone on its side: the card and a compact deck, square beside the
 * controls.
 */
@Composable
private fun WideLayout(
    vm: MainViewModel,
    tab: SourceTab,
    deck: DeckModel,
    playing: Set<SourceTab>,
    short: Boolean,
    trailing: (@Composable RowScope.() -> Unit)?,
    card: @Composable (Modifier) -> Unit,
) {
    Column {
        Row(Modifier.weight(1f).padding(horizontal = if (trailing != null) 6.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(if (short) 8.dp else 16.dp)) {
            card(Modifier.weight(1f).fillMaxHeight().padding(vertical = if (short) 4.dp else 8.dp))
            if (short) {
                Deck(deck, Modifier.weight(1.4f).fillMaxHeight().padding(vertical = 4.dp), compact = true)
            } else {
                Deck(deck, Modifier.weight(1.15f).fillMaxHeight().padding(vertical = 8.dp))
            }
        }
        SourceTabs(tab, playing, vm::selectTab, trailing)
    }
}

/**
 * Phone: the sources are cards you swipe between (or pick from the tab row), and what's playing
 * sits in a mini player at the bottom. Tap it or swipe it up for the full deck with lyrics;
 * swipe down or press Back to put it away.
 */
@Composable
private fun PhoneLayout(
    vm: MainViewModel,
    tab: SourceTab,
    deck: DeckModel,
    lyrics: LyricsState,
    playing: Set<SourceTab>,
    card: @Composable (SourceTab, Modifier) -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }
    val pager = rememberPagerState(initialPage = tab.ordinal) { SourceTab.entries.size }
    LaunchedEffect(pager.settledPage) { vm.selectTab(SourceTab.entries[pager.settledPage]) }
    LaunchedEffect(tab) { if (pager.currentPage != tab.ordinal) pager.animateScrollToPage(tab.ordinal) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            SourceTabs(tab, playing, vm::selectTab)
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp),
                pageSpacing = 12.dp,
            ) { page -> card(SourceTab.entries[page], Modifier.fillMaxSize()) }
            MiniPlayer(deck, lyrics.lyrics, onExpand = { expanded = true })
        }
        AnimatedVisibility(
            visible = expanded,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            ExpandedDeck(deck, onCollapse = { expanded = false })
        }
    }
}

/** Collapsed now-playing card: cover, title, quality, play/pause, next, and a thin progress line. */
@Composable
private fun MiniPlayer(deck: DeckModel, sheet: Lyrics?, onExpand: () -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Retro.Panel)
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if (drag < -40f) onExpand() },
                ) { _, dy -> drag += dy }
            },
    ) {
        val progress = if (deck.durationMs > 0) (deck.positionMs.toFloat() / deck.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(3.dp).background(Retro.ShellEdge)) {
            Box(Modifier.fillMaxWidth(progress).height(3.dp).background(Retro.Orange))
        }
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val art = deck.art
            if (art != null) {
                Image(art, "Cover", contentScale = ContentScale.Crop, modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
            } else {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(Retro.Cream), contentAlignment = Alignment.Center) {
                    Text("A", color = Retro.Orange, fontWeight = FontWeight.Black, fontSize = 20.sp)
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(deck.title.ifBlank { "Nothing playing" }, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The line being sung, when there are timed lyrics; otherwise artist and album.
                val line = sheet?.let { l -> l.lineAt(deck.positionMs).takeIf { it >= 0 }?.let { l.lines[it].text } }?.takeIf { it.isNotBlank() }
                if (line != null) {
                    Text(line, color = Retro.Orange, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(deck.subtitle.ifBlank { deck.status }, color = Retro.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                deck.path?.quality?.let { q ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(q.color()))
                        Text(q.label, color = q.color(), fontFamily = Retro.Mono, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
            IconButton(onClick = deck.onToggle) {
                Icon(if (deck.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", tint = Color.White)
            }
            IconButton(onClick = deck.onNext) { Icon(Icons.Filled.SkipNext, "Next", tint = Retro.Muted) }
        }
    }
}

/** The full deck over the cards. */
@Composable
private fun ExpandedDeck(deck: DeckModel, onCollapse: () -> Unit) {
    var drag by remember { mutableFloatStateOf(0f) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Retro.Background)
            .padding(horizontal = 12.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onCollapse)
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { drag = 0f },
                        onDragEnd = { if (drag > 60f) onCollapse() },
                    ) { _, dy -> drag += dy }
                }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, "Collapse", tint = Retro.Muted)
            Text("NOW PLAYING", color = Retro.Muted, fontFamily = Retro.Mono, fontSize = 11.sp, letterSpacing = 3.sp)
        }
        // The cover shrinks to whatever the controls leave, so everything stays on screen.
        Deck(deck, Modifier.weight(1f).fillMaxWidth().padding(bottom = 8.dp))
    }
}

@Composable
private fun Header(icons: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Side A", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
        Text("+", color = Retro.Orange, fontSize = 16.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Top))
        Spacer(Modifier.weight(1f))
        icons()
    }
}

@Composable
private fun HeaderIcons(eqActive: Boolean, onEq: () -> Unit, onSettings: () -> Unit) {
    IconButton(onClick = onEq) { Icon(Icons.Filled.Equalizer, "Equalizer", tint = if (eqActive) Color(0xFF6FB8FF) else Retro.Muted) }
    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings", tint = Retro.Muted) }
}

/** Hides the status and navigation bars while [on]; a swipe from the edge shows them briefly. */
@Composable
private fun ImmersiveMode(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        var ctx = view.context
        while (ctx is ContextWrapper && ctx !is Activity) ctx = ctx.baseContext
        val window = (ctx as? Activity)?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, view)
            if (on) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {}
    }
}

/** All six sources fit on one line with equal widths; no sideways scrolling. */
@Composable
private fun SourceTabs(selected: SourceTab, playing: Set<SourceTab>, onSelect: (SourceTab) -> Unit, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (trailing != null) 2.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
        SourceTab.entries.forEach { tab ->
            val on = tab == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) Retro.Panel else Color.Transparent)
                    .clickable { onSelect(tab) }
                    .padding(vertical = 8.dp),
            ) {
                Text(
                    tab.short,
                    color = if (on) Retro.Orange else Retro.Muted,
                    fontFamily = Retro.Mono,
                    fontSize = 10.sp,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1,
                )
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(if (tab in playing) Retro.Led else Color.Transparent),
                )
            }
        }
        trailing?.invoke(this)
    }
}

// ---- Deck models ----

private fun localDeck(
    queue: QueueState,
    engine: EngineState,
    tags: Map<String, Tags>,
    eq: EqConfig,
    art: ImageBitmap?,
    favorites: Set<String>,
    speed: Float,
    onFavorite: (io.github.momonekomata.sidea.sources.Track) -> Unit,
): DeckModel {
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
        volume = facts?.exclusive?.volumePercent?.let { Settings.current.usbVolume },
        onVolume = Player::setUsbVolume,
        favorite = t?.let { it.id in favorites },
        onFavorite = { t?.let(onFavorite) },
        shuffle = queue.shuffle,
        repeat = queue.repeat,
        onShuffle = Player::toggleShuffle,
        onRepeat = Player::cycleRepeat,
        speed = speed,
        onSpeed = Player::setSpeed,
        onSkip = Player::skipBy,
        onToggle = Player::togglePlay,
        onNext = { Player.next() },
        onPrevious = Player::previous,
        onSeek = Player::seekTo,
    )
}

/** One line next to the badge: what the file is and where it goes. */
/** Two lines under the badge: what the file is, and where it goes. */
private fun summary(f: StreamFacts, note: String?): String {
    val bits = f.sourceBits?.takeIf { it > 1 }?.let { " · $it-bit" } ?: ""
    val src = if (f.dsd != null) "DSD${f.sourceRate / 44_100} · ${"%.1f".format(f.sourceRate / 1e6)} MHz" else "${f.codec} · ${SignalPaths.khz(f.sourceRate)}$bits"
    val dest = f.deviceName ?: f.device.label
    val out = when {
        f.bitPerfect -> "→ $dest · bit-perfect ${SignalPaths.khz(f.outputRate)} ${f.outputFormat.label}"
        else -> "→ ${note ?: "Android mixer"} → $dest"
    }
    return "$src\n$out"
}

// tick and seen force recomposition: the position is extrapolated, and the output can change.
@Suppress("UNUSED_PARAMETER")
private fun remoteDeck(vm: MainViewModel, app: StreamingApp, np: RemoteNowPlaying?, tick: Long, art: ImageBitmap?, seen: OutputSeen) = DeckModel(
    title = np?.title.orEmpty(),
    subtitle = listOf(np?.artist, np?.album).filter { !it.isNullOrBlank() }.joinToString(" · "),
    positionMs = np?.positionMs ?: 0,
    durationMs = np?.durationMs ?: 0,
    playing = np?.isPlaying == true,
    status = if (np == null) "${app.label} is not playing" else remoteSummary(app, np, seen),
    statusColor = Retro.Muted,
    path = np?.let { SignalPaths.describeRemote(vm.streaming.output.facts(app, it)) },
    art = art,
    onToggle = { vm.streaming.playPause(app) },
    onNext = { vm.streaming.next(app) },
    onPrevious = { vm.streaming.previous(app) },
    onSeek = { vm.streaming.seekTo(app, it) },
    onSkip = { d -> np?.let { vm.streaming.seekTo(app, (it.positionMs + d).coerceAtLeast(0)) } },
    speed = np?.speed ?: 1f,
    onSpeed = { vm.streaming.setSpeed(app, it) },
)

/** As much of the stream's format as can be seen from outside the app. */
private fun remoteSummary(app: StreamingApp, np: RemoteNowPlaying, seen: OutputSeen): String {
    val dest = seen.deviceName ?: seen.device?.label ?: "phone"
    val rate = seen.bitPerfectRate
    return when {
        rate != null -> "${app.label} stream\n→ $dest · bit-perfect ${SignalPaths.khz(rate)}${seen.bitPerfectFormat?.let { " ${it.label}" } ?: ""}"
        np.qualityHint != null -> "${app.label} · ${np.qualityHint}\n→ $dest"
        else -> "${app.label} · up to ${app.ceiling.substringBefore(" (")}\n→ Android mixer${seen.mixerRate?.let { " · ${SignalPaths.khz(it)}" } ?: ""} → $dest"
    }
}

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
                BrowserCard(vm, false, local, queue, tags, modifier, onUp = { vm.up(false) }, onFolder = { vm.openFolder(false, it) }, onSong = { vm.play(false, it) }, onRetry = { vm.retry(false) }) {
                    item { JCardRow("", "Change music folder…", onClick = pickFolder) }
                }
            }
        }
        SourceTab.NAS -> NasCard(vm, ui.nas, ui.nasServers, queue, tags, modifier)
        SourceTab.FAVORITES -> FavoritesCard(vm, queue, modifier)
        else -> {
            val app = tab.app!!
            StreamingCard(vm, app, remote[app], ui.streamingAccess, modifier)
        }
    }
}

@Composable
private fun BrowserCard(
    vm: MainViewModel,
    isNas: Boolean,
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
    val search by vm.search.collectAsStateWithLifecycle()
    search?.takeIf { it.isNas == isNas }?.let { s ->
        SearchCard(vm, s, queue, tags, modifier)
        return
    }
    // Each folder keeps its own scroll position, so going back up lands where you left off.
    val scrollKey = "${state.browser.title}:${state.currentPath}"
    val list = remember(scrollKey) { vm.scrollFor(scrollKey).let { (i, o) -> LazyListState(i, o) } }
    LaunchedEffect(list, scrollKey) {
        snapshotFlow { list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset }
            .collect { (i, o) -> vm.saveScroll(scrollKey, i, o) }
    }
    val songs = state.entries.count { it is Entry.Song }
    val folders = state.entries.size - songs
    val context = LocalContext.current
    val firstSong = state.entries.firstOrNull { it is Entry.Song } as Entry.Song?
    val cover by produceState<ImageBitmap?>(null, firstSong?.track?.id) {
        value = firstSong?.let { ArtworkStore.load(context, it.track)?.asImageBitmap() }
    }
    JCard(state.currentName, "$songs songs · $folders folders · ${state.browser.title}", modifier, art = cover, listState = list) {
        item { JCardRow("⌕", "Search ${state.browser.title.lowercase()}…", onClick = { vm.openSearch(isNas) }) }
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

/** Search across the whole library, folders included, titles and artists from tags. */
@Composable
private fun SearchCard(vm: MainViewModel, s: SearchState, queue: QueueState, tags: Map<String, Tags>, modifier: Modifier) {
    BackHandler { vm.closeSearch() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    JCardFrame("Search", if (s.indexing) "reading folders… ${s.foldersRead}" else "${s.results.size} found", modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
            OutlinedTextField(
                value = s.query,
                onValueChange = vm::setQuery,
                singleLine = true,
                placeholder = { Text("Title, artist, album, folder", color = Retro.InkFaint) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Retro.Ink, unfocusedTextColor = Retro.Ink,
                    focusedBorderColor = Retro.Orange, unfocusedBorderColor = Retro.InkFaint, cursorColor = Retro.Orange,
                ),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            TextButton(onClick = vm::closeSearch) { Text("Close", color = Retro.Ink) }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (s.query.isNotBlank() && s.results.isEmpty() && !s.indexing) item { JCardNote("Nothing matches “${s.query}”.") }
            s.results.forEach { f ->
                item {
                    when (val e = f.entry) {
                        is Entry.Song -> {
                            val t = tags[e.track.id]
                            val where = f.crumbs.drop(1).joinToString(" / ") { it.first }.ifEmpty { f.crumbs.first().first }
                            JCardRow("♪", e.track.displayTitle(tags), listOfNotNull(t?.artist, where).joinToString(" · "), current = queue.current?.id == e.track.id, onClick = { vm.playFound(f) })
                        }
                        is Entry.Folder -> JCardRow("▸", e.name, f.crumbs.dropLast(1).drop(1).joinToString(" / ") { it.first }.ifEmpty { "folder" }, onClick = { vm.openFound(f) })
                    }
                }
            }
        }
    }
}

/** Favourite songs from Local and the NAS, newest first. */
@Composable
private fun FavoritesCard(vm: MainViewModel, queue: QueueState, modifier: Modifier) {
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    JCard("Favourites", "${favorites.size} songs", modifier) {
        if (favorites.isEmpty()) item { JCardNote("Tap ♡ next to a song's title on the deck to keep it here.") }
        favorites.forEachIndexed { i, f ->
            item {
                JCardRow("%02d".format(i + 1), f.title, listOf(f.artist, f.album).filter { it.isNotBlank() }.joinToString(" · "), current = queue.current?.id == f.id, onClick = { vm.playFavorites(i) })
            }
        }
    }
}

@Composable
private fun NasCard(vm: MainViewModel, nas: BrowseState?, servers: List<NasServer>, queue: QueueState, tags: Map<String, Tags>, modifier: Modifier) {
    var editing by remember { mutableStateOf<NasServer?>(null) }
    var adding by remember { mutableStateOf(false) }
    if (nas != null) {
        BrowserCard(vm, true, nas, queue, tags, modifier, onUp = { vm.up(true) }, onFolder = { vm.openFolder(true, it) }, onSong = { vm.play(true, it) }, onRetry = { vm.retry(true) }) {
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
