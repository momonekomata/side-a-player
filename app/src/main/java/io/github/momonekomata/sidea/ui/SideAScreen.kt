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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import io.github.momonekomata.sidea.LyricsState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import io.github.momonekomata.sidea.MainViewModel
import io.github.momonekomata.sidea.Settings
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
import io.github.momonekomata.sidea.sources.Favorite
import io.github.momonekomata.sidea.sources.displayTitle
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
import kotlinx.coroutines.launch
import androidx.compose.ui.input.nestedscroll.nestedScroll

@Composable
fun SideAScreen(vm: MainViewModel, pickLibraryFolder: () -> Unit) {
    val queue by Player.queue.collectAsStateWithLifecycle()
    val engine by Player.state.collectAsStateWithLifecycle()
    val parked by Player.parked.collectAsStateWithLifecycle()
    val tags by TagStore.tags.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val prefs by Settings.prefs.collectAsStateWithLifecycle()
    val localArt by vm.nowArt.collectAsStateWithLifecycle()
    val favorites by vm.favorites.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var settingsOpen by remember { mutableStateOf(false) }
    var sourcesOpen by rememberSaveable { mutableStateOf(false) }
    var eqOpen by remember { mutableStateOf(false) }
    // Cover or lyrics in the square; kept across songs, so lyrics stay up once chosen.
    var showLyrics by rememberSaveable { mutableStateOf(false) }

    // Drives the position readout.
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            Player.refreshPosition()
            tick++
            delay(250)
        }
    }

    // Back walks up out of an artist, album or genre opened in the Library.
    val libraryUi by vm.library.ui.collectAsStateWithLifecycle()
    BackHandler(enabled = libraryUi.stack.isNotEmpty()) { vm.library.back() }

    val localImage = remember(localArt) { localArt?.asImageBitmap() }
    val deck = localDeck(queue, engine, parked, tags, prefs.eq, localImage, favorites.map { it.id }.toSet(), vm::toggleFavorite).copy(
        lyrics = lyrics,
        showLyrics = showLyrics,
        onToggleLyrics = { showLyrics = !showLyrics },
        onRetryLyrics = vm::retryLyrics,
    )

    // Landscape: full screen. The system bars hide (swipe from an edge to peek), and the header's
    // buttons move beside the deck so the card and the deck get the whole height.
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val headerIcons: @Composable () -> Unit = {
        HeaderIcons(eqActive = prefs.eq.isActive, onEq = { eqOpen = true }, onSettings = { settingsOpen = true })
    }
    // What the lid says: bit-perfect only when the path really is.
    val out = engine.output
    val spec = if (out != null && engine.error == null && (out.bitPerfect || out.exclusive != null)) "STEREO · BIT-PERFECT" else "STEREO PORTABLE PLAYER"

    // The queue lives beside the deck (swipe left).
    val queuePage: @Composable (Modifier) -> Unit = { m -> QueueCard(vm, queue, tags, localImage, m) }
    val library: @Composable (Modifier) -> Unit = { m -> LibraryCard(vm, queue, onSources = { sourcesOpen = true }, m) }

    // A phone turned sideways opens on the tape, full screen; "‹ LIBRARY" (or Back) shows the
    // Library and deck, and the tape page's full-screen button brings it back. Every turn to
    // landscape starts on the tape again.
    // Upright, the tape goes full screen from the TAPE page's ⛶ button.
    var tapeFull by rememberSaveable(landscape) { mutableStateOf(landscape) }
    BackHandler(enabled = tapeFull) { tapeFull = false }
    ImmersiveMode(landscape || tapeFull)
    if (tapeFull) {
        TapeFullScreen(deck, closeLabel = if (landscape) "‹ LIBRARY" else "✕ CLOSE", onClose = { tapeFull = false })
    } else Column(
        Modifier
            .fillMaxSize()
            .background(Retro.Background)
            .then(if (landscape) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier.safeDrawingPadding()),
    ) {
        if (!landscape) Header(spec) { headerIcons() }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            if (maxWidth >= 600.dp) {
                WideLayout(
                    deck, short = maxHeight < 520.dp, trailing = if (landscape) headerIcons else null, queuePage = queuePage, card = library,
                    onTapeFullScreen = { tapeFull = true },
                )
            } else {
                PhoneLayout(deck, lyrics, queuePage, card = library, onTapeFullScreen = { tapeFull = true })
            }
        }
    }

    val songMenu by vm.songMenu.collectAsStateWithLifecycle()
    songMenu?.let { SongMenuDialog(vm, it) }
    if (eqOpen) EqScreen(prefs.eq, onChange = { c -> Settings.update { it.copy(eq = c) } }, onDismiss = { eqOpen = false })
    if (settingsOpen) {
        SettingsDialog(
            prefs,
            onChange = { p -> Settings.update { p } },
            onSources = { settingsOpen = false; sourcesOpen = true },
            onDismiss = { settingsOpen = false },
        )
    }
    if (sourcesOpen) SourcesScreen(vm, pickLibraryFolder, onDismiss = { vm.closeServer(); sourcesOpen = false })
    // After the sources screen, so it shows above it when a server asks to be trusted.
    ui.certPrompt?.let { CertificateDialog(it, onTrust = { vm.trustCertificate(it) }, onDismiss = vm::dismissCertPrompt) }
}

/**
 * Wide screens. Unfolded: the Library on the left page, the deck on the right, its square sized
 * so the controls fit under it. A phone on its side: the Library and a compact deck, square beside
 * the controls, with the EQ and Settings buttons at the far edge.
 */
@Composable
private fun WideLayout(
    deck: DeckModel,
    short: Boolean,
    trailing: (@Composable () -> Unit)?,
    queuePage: (@Composable (Modifier) -> Unit)?,
    card: @Composable (Modifier) -> Unit,
    onTapeFullScreen: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxSize().padding(start = if (trailing != null) 6.dp else 12.dp, end = if (trailing != null) 0.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(if (short) 8.dp else 16.dp)) {
        // Sideways the deck takes the larger share: the cover is as tall as the screen, and the
        // controls beside it need room for all three keys.
        card(Modifier.weight(1f).fillMaxHeight().padding(vertical = if (short) 6.dp else 8.dp))
        NowPlayingPager(
            deck,
            Modifier.weight(if (short) 1.25f else 1.15f).fillMaxHeight().padding(vertical = if (short) 6.dp else 8.dp),
            compact = short,
            queuePage = queuePage,
            onTapeFullScreen = onTapeFullScreen,
        )
        if (trailing != null) {
            Column(Modifier.fillMaxHeight().padding(vertical = 4.dp), verticalArrangement = Arrangement.Top) { trailing() }
        }
    }
}

/**
 * Phone: the Library fills the screen, and what's playing sits in a mini player at the bottom.
 * Tap it or swipe it up for the full deck with lyrics; swipe down or press Back to put it away.
 */
@Composable
private fun PhoneLayout(
    deck: DeckModel,
    lyrics: LyricsState,
    queuePage: (@Composable (Modifier) -> Unit)?,
    card: @Composable (Modifier) -> Unit,
    onTapeFullScreen: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = expanded) { expanded = false }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            card(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp))
            MiniPlayer(deck, lyrics.lyrics, onExpand = { expanded = true })
        }
        AnimatedVisibility(
            visible = expanded,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
        ) {
            ExpandedDeck(deck, queuePage, onCollapse = { expanded = false }, onTapeFullScreen = onTapeFullScreen)
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
            .brushedMetal()
            .clickable(onClick = onExpand)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if (drag < -40f) onExpand() },
                ) { _, dy -> drag += dy }
            },
    ) {
        val progress = if (deck.durationMs > 0) (deck.positionMs.toFloat() / deck.durationMs).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(3.dp).background(Retro.MetalEdge)) {
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
                Text(deck.title.ifBlank { "Nothing playing" }, color = Retro.OnMetal, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The line being sung, when there are timed lyrics; otherwise artist and album.
                val line = sheet?.let { l -> l.lineAt(deck.positionMs).takeIf { it >= 0 }?.let { l.lines[it].text } }?.takeIf { it.isNotBlank() }
                if (line != null) {
                    Text(line, color = Color(0xFFB4531A), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    Text(deck.subtitle.ifBlank { deck.status }, color = Retro.OnMetalMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                deck.path?.quality?.let { q ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 3.dp).clip(RoundedCornerShape(50)).background(Retro.Lcd).padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(q.color()))
                        Text(q.label, color = q.color(), fontFamily = Retro.Mono, fontSize = 9.sp, letterSpacing = 1.sp, modifier = Modifier.padding(start = 5.dp))
                    }
                }
            }
            IconButton(onClick = deck.onToggle) {
                Icon(if (deck.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", tint = if (deck.playing) Retro.Orange else Retro.OnMetal)
            }
            IconButton(onClick = deck.onNext) { Icon(Icons.Filled.SkipNext, "Next", tint = Retro.OnMetalMuted) }
        }
    }
}

/** The full deck over the cards. */
@Composable
private fun ExpandedDeck(deck: DeckModel, queuePage: (@Composable (Modifier) -> Unit)?, onCollapse: () -> Unit, onTapeFullScreen: () -> Unit) {
    // Swipe down anywhere to put it away: the panel follows the finger, and goes once pulled far
    // enough (or flung), else springs back. A list (lyrics, the queue) scrolls first and, pulled
    // down past its top, pulls the panel instead.
    var drag by remember { mutableFloatStateOf(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val far = with(androidx.compose.ui.platform.LocalDensity.current) { 140.dp.toPx() }
    fun release(velocity: Float = 0f) {
        if (drag > far || (drag > far / 4 && velocity > 1500f)) onCollapse()
        else scope.launch { androidx.compose.animation.core.animate(drag, 0f) { v, _ -> drag = v } }
    }
    val pull = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                // Pushing back up while the panel is pulled down moves the panel first.
                if (drag > 0f && available.y < 0f) {
                    val take = maxOf(available.y, -drag)
                    drag += take
                    return androidx.compose.ui.geometry.Offset(0f, take)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y > 0f) {
                    drag += available.y
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (drag <= 0f) return androidx.compose.ui.unit.Velocity.Zero
                release(available.y)
                return available
            }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = drag }
            .background(Retro.Background)
            .nestedScroll(pull)
            .pointerInput(Unit) {
                val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = { release(tracker.calculateVelocity().y) },
                    onDragCancel = { release() },
                ) { change, dy ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    drag = (drag + dy).coerceAtLeast(0f)
                    change.consume()
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onCollapse)
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.KeyboardArrowDown, "Collapse", tint = Retro.Muted)
            Text("NOW PLAYING", color = Retro.Muted, fontFamily = Retro.Mono, fontSize = 11.sp, letterSpacing = 3.sp)
        }
        // Swipe left for the queue.
        NowPlayingPager(deck, Modifier.weight(1f).fillMaxWidth().padding(bottom = 8.dp), compact = false, queuePage = queuePage, onTapeFullScreen = onTapeFullScreen)
    }
}

@Composable
private fun Header(spec: String, icons: @Composable () -> Unit) {
    // One slim line, so the Library gets the screen: the name as on a Walkman's lid (heavy
    // italic capitals), the orange stripe and the spec beside it, then the buttons.
    Row(Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                append("WALK")
                pushStyle(androidx.compose.ui.text.SpanStyle(color = Retro.Orange))
                append("DECK")
                pop()
            },
            color = Color(0xFFE9EDF3), fontFamily = Retro.Logo, fontSize = 20.sp, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, letterSpacing = 1.sp,
        )
        Box(Modifier.padding(start = 10.dp).width(14.dp).height(2.dp).background(Retro.Orange))
        Text(spec, color = Retro.Muted, fontFamily = Retro.Mono, fontSize = 8.sp, letterSpacing = 1.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 5.dp))
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


// ---- Deck models ----

private fun localDeck(
    queue: QueueState,
    engine: EngineState,
    parked: io.github.momonekomata.sidea.audio.Resume?,
    tags: Map<String, Tags>,
    eq: EqConfig,
    art: ImageBitmap?,
    favorites: Set<String>,
    onFavorite: (io.github.momonekomata.sidea.sources.Track) -> Unit,
): DeckModel {
    val t = queue.current
    val facts = engine.output
    val status = when {
        t == null -> "Pick a song from the Library"
        engine.error != null -> "ERROR · ${engine.error}"
        // Before Threading: a NAS refill can be what's holding the song up.
        engine.bufferNote != null -> engine.bufferNote!!
        // Paused with the DAC handed back, or brought back from last time: Play carries on.
        parked != null && !engine.isPlaying && !engine.isBuffering -> "Paused · press play"
        facts == null -> "Threading the tape…"
        else -> summary(facts, engine.outputNote)
    }
    return DeckModel(
        title = t?.displayTitle(tags).orEmpty(),
        subtitle = listOf(t?.displayArtist(tags), t?.displayAlbum(tags)).filter { !it.isNullOrBlank() }.joinToString(" · "),
        positionMs = parked?.positionMs ?: engine.positionMs,
        durationMs = parked?.durationMs?.takeIf { it > 0 } ?: engine.durationMs,
        playing = engine.isPlaying,
        status = status,
        statusColor = if (engine.error != null) Color(0xFFB4531A) else Retro.OnMetalMuted,
        path = if (engine.error == null) facts?.let { SignalPaths.describe(it, eq) } else null,
        art = art,
        favorite = t?.let { it.id in favorites },
        onFavorite = { t?.let(onFavorite) },
        shuffle = queue.shuffle,
        repeat = queue.repeat,
        onShuffle = Player::toggleShuffle,
        onRepeat = Player::cycleRepeat,
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
    // Lossy files have no bit depth of their own; what the decoder hands out isn't the file's.
    val bits = f.sourceBits?.takeIf { it > 1 && SignalPaths.isLossless(f.codec) }?.let { " · $it-bit" } ?: ""
    val src = if (f.dsd != null) "DSD${f.sourceRate / 44_100} · ${"%.1f".format(f.sourceRate / 1e6)} MHz" else "${SignalPaths.codecName(f.codec)} · ${SignalPaths.khz(f.sourceRate)}$bits"
    val dest = f.deviceName ?: f.device.label
    val out = when {
        f.bitPerfect -> "→ $dest · bit-perfect ${SignalPaths.khz(f.outputRate)} ${f.outputFormat.label}"
        else -> "→ ${note ?: "Android mixer"} → $dest"
    }
    return "$src\n$out"
}

/**
 * TAPE · DECK · QUEUE: it opens on the deck; the cassette turning is a swipe right, the queue a
 * swipe left. The names under them take taps too.
 */
@Composable
private fun NowPlayingPager(
    deck: DeckModel,
    modifier: Modifier,
    compact: Boolean,
    queuePage: (@Composable (Modifier) -> Unit)?,
    onTapeFullScreen: (() -> Unit)? = null,
) {
    val labels = if (queuePage != null) listOf("TAPE", "DECK", "QUEUE") else listOf("TAPE", "DECK")
    val pager = rememberPagerState(initialPage = 1) { labels.size }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(modifier) {
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 12.dp) { page ->
            when {
                page == 0 -> CassettePage(deck, Modifier.fillMaxSize(), onFullScreen = onTapeFullScreen)
                page == 2 && queuePage != null -> queuePage(Modifier.fillMaxSize().padding(horizontal = if (compact) 0.dp else 12.dp))
                else -> Deck(deck, Modifier.fillMaxSize(), compact = compact)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
            labels.forEachIndexed { i, label ->
                Text(
                    label,
                    color = if (pager.currentPage == i) Retro.Orange else Retro.Muted,
                    fontFamily = Retro.Mono, fontSize = 11.sp, letterSpacing = 2.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable { scope.launch { pager.animateScrollToPage(i) } }.padding(horizontal = 10.dp, vertical = if (compact) 4.dp else 6.dp),
                )
            }
        }
    }
}

/** An up-next row: its key outlives reordering, which a list index wouldn't. */
private class QueueSlot(val track: io.github.momonekomata.sidea.sources.Track, val key: Long)

/**
 * The queue as Apple Music lays it out: shuffle, repeat and clear always on top; what played
 * above (scroll up for more), the song playing with its cover, then Playing Next. Drag an up-next
 * song by its handle to move it, swipe it left to take it out, long-press it for more.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueCard(vm: MainViewModel, queue: QueueState, tags: Map<String, Tags>, art: ImageBitmap?, modifier: Modifier) {
    val played by io.github.momonekomata.sidea.sources.PlayHistory.items.collectAsStateWithLifecycle()
    val current = queue.current
    // Oldest first, ending just above the song playing (which history already holds).
    val past = remember(played, current?.id) {
        (if (current != null && played.firstOrNull()?.song?.id == current.id) played.drop(1) else played).reversed()
    }
    val nextKey = remember { longArrayOf(0) }
    val upcoming = remember { mutableStateListOf<QueueSlot>() }
    var dragKey by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(queue) {
        if (dragKey == null) {
            upcoming.clear()
            if (queue.index >= 0) queue.tracks.drop(queue.index + 1).forEach { upcoming += QueueSlot(it, nextKey[0]++) }
        }
    }
    val list = rememberLazyListState()
    // History header + its songs come before the song playing.
    val nowItem = if (past.isNotEmpty()) past.size + 1 else 0
    // Opens on the song playing, history out of sight above it.
    LaunchedEffect(current?.id) { if (dragKey == null && current != null) list.scrollToItem(nowItem) }

    fun commit() {
        dragKey = null
        dragOffset = 0f
        Player.setUpcoming(upcoming.map { it.track })
    }

    fun drag(dy: Float) {
        val key = dragKey ?: return
        dragOffset += dy
        val visible = list.layoutInfo.visibleItemsInfo
        val me = visible.firstOrNull { it.key == key } ?: return
        val centre = (me.offset + dragOffset + me.size / 2).toInt()
        val over = visible.firstOrNull { it.key is Long && it.key != key && centre in it.offset until it.offset + it.size } ?: return
        val from = upcoming.indexOfFirst { it.key == key }
        val to = upcoming.indexOfFirst { it.key == over.key }
        if (from < 0 || to < 0) return
        upcoming.add(to, upcoming.removeAt(from))
        // The row now sits where the other was; keep it under the finger.
        dragOffset += me.offset - over.offset
    }

    JCard("Queue", "${upcoming.size} playing next", modifier, listState = list, pinned = {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QueueToggle("⇄ Shuffle", queue.shuffle, Modifier.weight(1f), Player::toggleShuffle)
            val repeat = when (queue.repeat) {
                io.github.momonekomata.sidea.audio.RepeatMode.OFF -> "↻ Repeat"
                io.github.momonekomata.sidea.audio.RepeatMode.ALL -> "↻ All"
                io.github.momonekomata.sidea.audio.RepeatMode.ONE -> "↻ One"
            }
            QueueToggle(repeat, queue.repeat != io.github.momonekomata.sidea.audio.RepeatMode.OFF, Modifier.weight(1f), Player::cycleRepeat)
            QueueToggle("Clear", false, Modifier.weight(0.8f), Player::clearUpcoming)
        }
    }) {
        if (past.isNotEmpty()) {
            item(key = "history") { QueueSection("History", "Clear", io.github.momonekomata.sidea.sources.PlayHistory::clear) }
            past.forEachIndexed { i, p ->
                item(key = "h$i:${p.atMs}") {
                    val f = p.song
                    JCardRow(
                        playedAgo(p.atMs), f.title, listOf(f.artist, f.album).filter { it.isNotBlank() }.joinToString(" · "),
                        onLongClick = { vm.openSongMenu(f) },
                        onClick = { vm.playPlayed(f) },
                    )
                }
            }
        }
        item(key = "now") {
            if (current == null) {
                JCardNote("Nothing playing. Play a song, or long-press one and pick Play Next or Play Last.")
            } else {
                NowPlayingRow(current.displayTitle(tags), listOf(current.displayArtist(tags), current.displayAlbum(tags)).filter { it.isNotBlank() }.joinToString(" · "), art) {
                    vm.openSongMenu(current)
                }
            }
        }
        if (upcoming.isNotEmpty()) item(key = "next") { QueueSection("Playing Next", null, null) }
        upcoming.forEachIndexed { i, slot ->
            item(key = slot.key) {
                val dragging = dragKey == slot.key
                val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
                    if (v == SwipeToDismissBoxValue.EndToStart) {
                        upcoming.remove(slot)
                        Player.setUpcoming(upcoming.map { it.track })
                        true
                    } else {
                        false
                    }
                })
                SwipeToDismissBox(
                    swipe,
                    enableDismissFromStartToEnd = false,
                    backgroundContent = {
                        Box(Modifier.fillMaxSize().background(Color(0xFFD64545)).padding(end = 18.dp), contentAlignment = Alignment.CenterEnd) {
                            Text("Remove", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    },
                    modifier = Modifier
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer {
                            translationY = if (dragging) dragOffset else 0f
                            shadowElevation = if (dragging) 12f else 0f
                        }
                        .then(if (dragging) Modifier else Modifier.animateItem()),
                ) {
                    val t = slot.track
                    JCardRow(
                        "", t.displayTitle(tags), listOf(t.displayArtist(tags), t.displayAlbum(tags)).filter { it.isNotBlank() }.joinToString(" · "),
                        onLongClick = { vm.openSongMenu(t, queueIndex = queue.index + 1 + upcoming.indexOf(slot)) },
                        trailing = {
                            // The handle drags at once, as in Apple Music; the rest of the row taps and swipes.
                            Icon(
                                Icons.Filled.DragHandle, "Move",
                                tint = Retro.InkFaint,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .size(28.dp)
                                    .pointerInput(slot.key) {
                                        detectDragGestures(
                                            onDragStart = { dragKey = slot.key; dragOffset = 0f },
                                            onDrag = { change, d -> change.consume(); drag(d.y) },
                                            onDragEnd = ::commit,
                                            onDragCancel = ::commit,
                                        )
                                    },
                            )
                        },
                        onClick = { if (dragKey == null) Player.jumpTo(queue.index + 1 + upcoming.indexOf(slot)) },
                    )
                }
            }
        }
    }
}

/** A button along the top of the queue, lit when it's on. */
@Composable
private fun QueueToggle(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Retro.Orange else Retro.CreamShade)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) Color.White else Retro.Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
    }
}

/** "History", "Playing Next": a heading in the list, with an optional action at the end. */
@Composable
private fun QueueSection(title: String, action: String?, onAction: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Retro.Ink, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, color = Retro.Orange, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.clickable(onClick = onAction).padding(6.dp))
        }
    }
}

/** The song playing, larger and with its cover, between what played and what's next. */
@Composable
private fun NowPlayingRow(title: String, detail: String, art: ImageBitmap?, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Retro.Orange.copy(alpha = 0.16f))
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongClick() }) }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (art != null) {
            Image(art, "Cover", contentScale = ContentScale.Crop, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)))
        } else {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(4.dp)).background(Retro.CreamShade), contentAlignment = Alignment.Center) {
                Text("▶", color = Retro.Orange, fontSize = 20.sp)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("NOW PLAYING", color = Retro.Orange, fontFamily = Retro.Mono, fontSize = 10.sp, letterSpacing = 2.sp)
            Text(title, color = Retro.Ink, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, color = Retro.InkFaint, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** "now", "5m", "3h", "2d": how long ago, short enough for the number column. */
private fun playedAgo(atMs: Long): String {
    val m = (System.currentTimeMillis() - atMs) / 60_000
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m"
        m < 60 * 24 -> "${m / 60}h"
        else -> "${m / (60 * 24)}d"
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
