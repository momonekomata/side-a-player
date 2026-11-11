package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import io.github.momonekomata.sidea.audio.RepeatMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import io.github.momonekomata.sidea.LyricsState
import io.github.momonekomata.sidea.audio.SignalPath
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.unit.sp

/**
 * The deck, on the Walkman's anodised blue. The tape turns in a window at the top (cover, title,
 * the line being sung on its label; tap it for the lyrics). Upright: the song's name under it, a
 * dark display with the format, the tape-counter seek scale and the times, with ♥, shuffle and
 * repeat keys beside it, and tall piano keys along the bottom (swipe them for −10 −5 +5 +10).
 * [compact], for a phone on its side: the window on the left, and beside it the keys in their
 * strip, the song's name over a rule, the scale, and the heart and quality badge.
 */
@Composable
fun TapeDeck(model: DeckModel, modifier: Modifier = Modifier, compact: Boolean = false, onFullScreen: (() -> Unit)? = null) {
    if (io.github.momonekomata.sidea.Settings.prefs.collectAsState().value.theme == io.github.momonekomata.sidea.AppTheme.STEREO) {
        StereoDeck(model, modifier)
        return
    }
    var showPath by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    val panel = modifier
        .clip(shape)
        .anodizedBlue()
        .border(1.dp, Retro.ShellEdge.copy(alpha = 0.7f), shape)
    if (compact) {
        BoxWithConstraints(panel.padding(10.dp)) {
            val tapeW = minOf(maxWidth * 0.56f, (maxHeight - 12.dp) * CASSETTE_RATIO)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                TapeWindow(model, Modifier.width(tapeW), onFullScreen)
                Column(
                    Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.SpaceEvenly,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column { TransportPager(model, compact = false) }
                    TitleBlock(model, compact = true)
                    Column {
                        TapeScale(model, Retro.OnShell)
                        Times(model, Retro.Muted)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        model.favorite?.let { fav ->
                            IconButton(onClick = model.onFavorite, modifier = Modifier.size(36.dp)) {
                                Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", tint = if (fav) Retro.Orange else Retro.Muted)
                            }
                        }
                        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                            model.path?.let { QualityBadge(it.quality) { showPath = true } }
                        }
                    }
                }
            }
        }
    } else {
        BoxWithConstraints(panel.padding(12.dp)) {
            // The window takes what's left once the rest has its room.
            val tapeW = minOf(maxWidth, ((maxHeight - 420.dp) * CASSETTE_RATIO).coerceAtLeast(maxWidth * 0.6f))
            // The tape at the top; the line being sung in the room under it, as many lines as it
            // takes; the song's name, the display and the keys together at the bottom, each only as
            // tall as it needs.
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                TapeWindow(model, Modifier.width(tapeW), onFullScreen, lineOnTape = false)
                LyricLines(model, Modifier.weight(1f).fillMaxWidth())
                TitleBlock(model, compact = false)
                Box(Modifier.padding(top = 4.dp, bottom = 10.dp)) { Display(model) { showPath = true } }
                Column { PianoPager(model) }
            }
        }
    }
    val path = model.path
    if (showPath && path != null) SignalPathDialog(path, onDismiss = { showPath = false })
}

/**
 * Upright, under the tape: the line being sung, whole (wrapping onto as many lines as it needs),
 * and the next one fainter under it. Nothing when the lyrics aren't timed or are open on the tape.
 */
@Composable
internal fun LyricLines(model: DeckModel, modifier: Modifier) {
    val l = model.lyrics.lyrics
    val at = l?.lineAt(model.positionMs) ?: -1
    Box(modifier.padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        if (l == null || at < 0 || model.showLyrics) return@Box
        Crossfade(at, label = "lyric-line") { i ->
            val now = l.lines.getOrNull(i)?.text.orEmpty()
            val next = l.lines.getOrNull(i + 1)?.text?.takeIf { it.isNotBlank() }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    now.ifBlank { "♪" }, color = Retro.OnShell, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = 26.sp,
                )
                if (next != null) {
                    Text(
                        next, color = Retro.Muted.copy(alpha = 0.7f), fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * The whole lyrics straight on the body, no panel: centred, the line being sung large and white,
 * the rest smaller and faint, scrolling so the sung line stays in the middle. A tap on a timed
 * line goes to it. Untimed lyrics just scroll; with none, nothing shows.
 */
@Composable
internal fun LyricsFlow(model: DeckModel, modifier: Modifier) {
    val l = model.lyrics.lyrics ?: run {
        Box(modifier, contentAlignment = Alignment.Center) {
            if (model.lyrics.loading) Text("Finding the lyrics…", color = Retro.Muted, fontSize = 15.sp)
        }
        return
    }
    val at = l.lineAt(model.positionMs)
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    BoxWithConstraints(modifier) {
        val half = maxHeight / 2
        val halfPx = with(androidx.compose.ui.platform.LocalDensity.current) { half.toPx() }
        // Keep the sung line in the middle: glide it there when it's in view, jump when it isn't.
        LaunchedEffect(at, l) {
            if (at < 0) return@LaunchedEffect
            val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == at + 1 }
            if (item != null) {
                list.animateScrollBy(item.offset + item.size / 2f - halfPx)
            } else {
                list.scrollToItem(at + 1)
                list.scrollBy(-(halfPx - 30f))
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(half - 20.dp)) }
            items(l.lines.size) { i ->
                val line = l.lines[i]
                val now = i == at
                Text(
                    line.text.ifBlank { "♪" },
                    color = if (now) Retro.OnShell else Retro.Muted.copy(alpha = 0.55f),
                    fontSize = if (now) 22.sp else 17.sp,
                    fontWeight = if (now) FontWeight.SemiBold else FontWeight.Normal,
                    lineHeight = if (now) 28.sp else 22.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (l.synced && line.timeMs >= 0) Modifier.clickable { model.onSeek(line.timeMs) } else Modifier)
                        .padding(horizontal = 8.dp),
                )
            }
            item { Spacer(Modifier.height(half)) }
        }
    }
}

/** The line being sung right now, when the lyrics are timed. */
private fun lyricLine(model: DeckModel): String? =
    model.lyrics.lyrics?.let { l -> l.lineAt(model.positionMs).takeIf { it >= 0 }?.let { l.lines[it].text } }?.takeIf { it.isNotBlank() }

/**
 * The tape behind a little window in the body. A tap on it swaps it for the lyrics (and back);
 * under it, two small keys: LYRICS / TAPE, and full screen.
 */
@Composable
private fun TapeWindow(model: DeckModel, modifier: Modifier, onFullScreen: (() -> Unit)?, lineOnTape: Boolean = true) {
    val frame = RoundedCornerShape(8.dp)
    val glass = io.github.momonekomata.sidea.Settings.prefs.collectAsState().value.theme == io.github.momonekomata.sidea.AppTheme.GLASS
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .shadow(if (glass) 6.dp else 3.dp, frame)
                .clip(frame)
                .then(
                    if (glass) {
                        // A cassette deck's door: a brushed-metal frame round a dark well.
                        Modifier.brushedMetal().border(1.dp, Retro.MetalEdge, frame).padding(9.dp)
                            .border(2.dp, Color(0xFF05070B), RoundedCornerShape(5.dp))
                            .background(Color(0xFF06080C), RoundedCornerShape(5.dp))
                            .padding(7.dp)
                    } else {
                        Modifier.background(Color(0xFF0F1B2E)).border(1.5.dp, Color(0xFF0A1322), frame).padding(5.dp)
                    },
                ),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(CASSETTE_RATIO)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = model.onToggleLyrics),
            ) {
                Crossfade(model.showLyrics, label = "tape-lyrics") { lyricsSide ->
                    if (lyricsSide) {
                        LyricsSquare(model.lyrics, model.positionMs, model.playing, model.onRetryLyrics, model.onSeek, Modifier.fillMaxSize())
                    } else {
                        TapeOnly(model, if (lineOnTape) lyricLine(model) else null, Modifier.fillMaxSize())
                    }
                }
                if (glass && !model.showLyrics) GlassPane(Modifier.fillMaxSize())
            }
        }
        val hint = when {
            model.showLyrics -> "TAPE"
            model.lyrics.lyrics != null -> "LYRICS"
            model.lyrics.loading -> "LYRICS…"
            else -> null
        }
        if (hint != null || onFullScreen != null) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End)) {
                // Timed lyric lines take taps themselves (to jump), so this key swaps back too.
                if (hint != null) SmallLabelKey(hint, hint, model.onToggleLyrics)
                if (onFullScreen != null) SmallLabelKey("⛶ FULL", "Full screen", onFullScreen)
            }
        }
    }
}

/**
 * The Glass deck theme's door glass over the tape: smoked a little blue-grey, darker towards the
 * foot, with a broad soft reflection and a thin bright streak across it, and the glass's edge
 * catching the light along the top and left.
 */
@Composable
private fun GlassPane(modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0x332B3B52), Color(0x44141C28), Color(0x66080B10))))
        // The broad reflection: a soft band from the upper left.
        drawRect(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.16f), 0.28f to Color.White.copy(alpha = 0.06f), 0.42f to Color.Transparent, 1f to Color.Transparent,
                start = androidx.compose.ui.geometry.Offset(0f, 0f), end = androidx.compose.ui.geometry.Offset(w * 0.8f, h),
            ),
        )
        // A thin bright streak, and a fainter one beside it.
        rotate(-24f, androidx.compose.ui.geometry.Offset(w / 2, h / 2)) {
            val x = w * 0.62f
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent), startX = x, endX = x + w * 0.05f),
                androidx.compose.ui.geometry.Offset(x, -h), androidx.compose.ui.geometry.Size(w * 0.05f, h * 3),
            )
            val x2 = x + w * 0.08f
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.09f), Color.Transparent), startX = x2, endX = x2 + w * 0.12f),
                androidx.compose.ui.geometry.Offset(x2, -h), androidx.compose.ui.geometry.Size(w * 0.12f, h * 3),
            )
        }
        // The glass's edge.
        val px = 1.5.dp.toPx()
        drawRect(Color.White.copy(alpha = 0.35f), size = androidx.compose.ui.geometry.Size(w, px))
        drawRect(Color.White.copy(alpha = 0.18f), size = androidx.compose.ui.geometry.Size(px, h))
        drawRect(Color.Black.copy(alpha = 0.5f), topLeft = androidx.compose.ui.geometry.Offset(0f, h - px), size = androidx.compose.ui.geometry.Size(w, px))
    }
}

/** A small grey key with a word on it. */
@Composable
internal fun SmallLabelKey(text: String, label: String, onClick: () -> Unit) {
    Text(
        text, color = Retro.OnMetal, fontFamily = Retro.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = Modifier
            .keyFace()
            .clickable(onClick = onClick)
            .semantics { contentDescription = label }
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** The song's name, a rule, and the artist and album, printed on the body. */
@Composable
private fun TitleBlock(model: DeckModel, compact: Boolean) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            model.title.ifBlank { "Nothing playing" }, color = Retro.OnShell, fontSize = if (compact) 16.sp else 19.sp,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Box(Modifier.padding(vertical = 4.dp).fillMaxWidth(0.62f).height(1.dp).background(Retro.Muted.copy(alpha = 0.6f)))
        ArtistAlbumLine(model, Retro.Muted, if (compact) 12.sp else 13.sp)
    }
}

/**
 * Upright: ♥, shuffle and repeat keys down the left, and the dark display beside them: the
 * quality badge and format (tap for the signal path), the seek scale and the times.
 */
@Composable
private fun Display(model: DeckModel, onPath: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val side = 40.dp
        if (model.favorite != null || model.shuffle != null || model.repeat != null) {
            KeyStrip(Modifier.fillMaxHeight(), vertical = true, gap = 4.dp) {
                model.favorite?.let { fav ->
                    DeckKey(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", "LOVE", side, led = fav, onClick = model.onFavorite)
                }
                ShuffleKey(model, side)
                RepeatKey(model, side)
            }
        }
        val lcd = RoundedCornerShape(6.dp)
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(lcd)
                .background(Brush.verticalGradient(listOf(Color(0xFF0B1220), Retro.Lcd)))
                .border(1.dp, Color(0xFF2A3A55), lcd)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            val path = model.path
            if (path != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityBadge(path.quality, onPath)
                    // One line: the format fits beside the badge; anything longer is cut short.
                    Text(
                        model.status.lines().first(), color = Retro.Muted, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
                        modifier = Modifier.weight(1f, fill = false).clickable(onClick = onPath),
                    )
                }
            } else {
                // The status is printed for the light faceplate; on the display it's light.
                StatusLine(model.status, if (model.statusColor == Retro.OnMetalMuted) Retro.Muted else Retro.Orange)
            }
            Column {
                TapeScale(model, Retro.Muted)
                Times(model, Retro.Orange)
            }
        }
    }
}

@Composable
private fun Times(model: DeckModel, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(formatTime(model.positionMs), color = color, fontFamily = Retro.Mono, fontSize = 11.sp)
        Text(if (model.durationMs > 0) formatTime(model.durationMs) else "--:--", color = color, fontFamily = Retro.Mono, fontSize = 11.sp)
    }
}

/**
 * The seek bar as a tape counter's scale: ticks numbered 0 to 10, the part played in orange and
 * an orange marker where the song is. Tap or drag anywhere on it to move.
 */
@Composable
private fun TapeScale(model: DeckModel, ink: Color) {
    val hand = rememberSeekHand()
    val duration by rememberUpdatedState(model.durationMs)
    val seek by rememberUpdatedState(model.onSeek)
    val value = if (model.durationMs > 0) hand.shown((model.positionMs.toFloat() / model.durationMs).coerceIn(0f, 1f), model.durationMs) else 0f
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val digit = androidx.compose.ui.text.TextStyle(color = ink, fontFamily = Retro.Mono, fontSize = 8.sp)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .semantics { contentDescription = "Seek" }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (duration <= 0) return@awaitEachGesture
                    var f = (down.position.x / size.width).coerceIn(0f, 1f)
                    hand.dragging = f
                    down.consume()
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        f = (c.position.x / size.width).coerceIn(0f, 1f)
                        hand.dragging = f
                        c.consume()
                    }
                    hand.dragging = f
                    hand.release(duration, seek)
                }
            },
    ) {
        val w = size.width
        val h = size.height
        val pad = 5.dp.toPx()
        val span = w - 2 * pad
        val base = h * 0.52f
        val n = 50
        for (i in 0..n) {
            val x = pad + span * i / n
            val major = i % 5 == 0
            val played = i.toFloat() / n <= value
            drawLine(
                if (played) Retro.Orange else ink.copy(alpha = if (major) 0.85f else 0.45f),
                Offset(x, base - if (major) h * 0.34f else h * 0.18f), Offset(x, base),
                strokeWidth = if (major) 1.6.dp.toPx() else 1.dp.toPx(),
            )
            if (major) {
                val t = measurer.measure((i / 5).toString(), digit)
                drawText(t, topLeft = Offset(x - t.size.width / 2f, base + h * 0.08f))
            }
        }
        drawLine(ink.copy(alpha = 0.5f), Offset(pad, base), Offset(w - pad, base), 1.5.dp.toPx())
        drawLine(Retro.Orange, Offset(pad, base), Offset(pad + span * value, base), 3.dp.toPx())
        val mx = pad + span * value
        val mw = 6.dp.toPx()
        drawRoundRect(Retro.Orange, Offset(mx - mw / 2, h * 0.06f), Size(mw, base - h * 0.06f + 3.dp.toPx()), CornerRadius(1.5.dp.toPx()))
    }
}

/**
 * The tall piano keys along the bottom: previous, PLAY (the orange one, latched while the tape
 * runs) and next; hold previous/next to wind. Swipe them for −10 −5 +5 +10.
 */
@Composable
private fun PianoPager(model: DeckModel) {
    val pager = rememberPagerState { 2 }
    val tall = 84.dp
    HorizontalPager(pager, Modifier.fillMaxWidth().height(tall + 12.dp)) { page ->
        KeyStrip(Modifier.fillMaxWidth()) {
            val m = Modifier.weight(1f).height(tall)
            if (page == 0) {
                PianoKey(m, Icons.Filled.SkipPrevious, "Previous", "PREV", onHold = { model.onSkip(-5_000) }, onClick = model.onPrevious)
                PianoKey(
                    Modifier.weight(1.25f).height(tall), if (model.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause",
                    if (model.playing) "PAUSE" else "PLAY", orange = true, latched = model.playing, onClick = model.onToggle,
                )
                PianoKey(m, Icons.Filled.SkipNext, "Next", "NEXT", onHold = { model.onSkip(5_000) }, onClick = model.onNext)
            } else {
                for (step in listOf(-10_000L, -5_000L, 5_000L, 10_000L)) PianoWind(m, step, onSkip = model.onSkip)
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(2) { i ->
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (pager.currentPage == i) Retro.Orange else Retro.Muted.copy(alpha = 0.6f)))
        }
    }
}

