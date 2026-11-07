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

/** What the deck shows, whichever source is playing. */
data class DeckModel(
    val title: String,
    val subtitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val status: String,
    val statusColor: Color,
    /** The graded signal path behind the quality badge. */
    val path: SignalPath? = null,
    val art: ImageBitmap? = null,
    val lyrics: LyricsState = LyricsState(),
    /** The square shows lyrics instead of the cover. */
    val showLyrics: Boolean = false,
    val onToggleLyrics: () -> Unit = {},
    val onRetryLyrics: () -> Unit = {},
    /** Null hides the heart (streaming songs are favourited in their own apps). */
    val favorite: Boolean? = null,
    val onFavorite: () -> Unit = {},
    /** Null hides shuffle/repeat (streaming apps don't take them from other apps). */
    val shuffle: Boolean? = null,
    val repeat: RepeatMode? = null,
    val onShuffle: () -> Unit = {},
    val onRepeat: () -> Unit = {},
    val onSkip: (Long) -> Unit = {},
    val onToggle: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Long) -> Unit,
)

/**
 * The deck, on the Walkman's anodised blue. The tape turns in a window at the top (cover, title,
 * the line being sung on its label; tap it for the lyrics). Upright: the song's name under it, a
 * dark display with the format, the tape-counter seek scale and the times, with ♥, shuffle and
 * repeat keys beside it, and tall piano keys along the bottom (swipe them for −10 −5 +5 +10).
 * [compact], for a phone on its side: the window on the left, and beside it the keys in their
 * strip, the song's name over a rule, the scale, and the heart and quality badge.
 */
@Composable
fun Deck(model: DeckModel, modifier: Modifier = Modifier, compact: Boolean = false) {
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
                TapeWindow(model, Modifier.width(tapeW))
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
            val tapeW = minOf(maxWidth, ((maxHeight - 380.dp) * CASSETTE_RATIO).coerceAtLeast(maxWidth * 0.6f))
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TapeWindow(model, Modifier.width(tapeW))
                TitleBlock(model, compact = false)
                Display(model) { showPath = true }
                Column { PianoPager(model) }
            }
        }
    }
    val path = model.path
    if (showPath && path != null) SignalPathDialog(path, onDismiss = { showPath = false })
}

/** The line being sung right now, when the lyrics are timed. */
private fun lyricLine(model: DeckModel): String? =
    model.lyrics.lyrics?.let { l -> l.lineAt(model.positionMs).takeIf { it >= 0 }?.let { l.lines[it].text } }?.takeIf { it.isNotBlank() }

/**
 * The tape behind a little window in the body, under glass. A tap swaps it for the lyrics (and
 * back); a hint says which.
 */
@Composable
private fun TapeWindow(model: DeckModel, modifier: Modifier) {
    val frame = RoundedCornerShape(8.dp)
    Box(
        modifier
            .shadow(3.dp, frame)
            .clip(frame)
            .background(Color(0xFF0F1B2E))
            .border(1.5.dp, Color(0xFF0A1322), frame)
            .padding(5.dp),
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
                    TapeOnly(model, lyricLine(model), Modifier.fillMaxSize())
                }
            }
            // The glass: a soft band of light across it.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.10f), 0.35f to Color.White.copy(alpha = 0.03f), 0.36f to Color.Transparent, 1f to Color.Transparent,
                    ),
                ),
            )
            val hint = when {
                model.showLyrics -> "TAPE"
                model.lyrics.lyrics != null -> "LYRICS"
                model.lyrics.loading -> "LYRICS…"
                else -> null
            }
            if (hint != null) {
                Text(
                    hint, color = Color.White, fontFamily = Retro.Mono, fontSize = 9.sp, letterSpacing = 1.sp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(KeyShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                        // Timed lyric lines take taps themselves (to jump), so the hint swaps back too.
                        .clickable(onClick = model.onToggleLyrics)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
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
        if (model.subtitle.isNotBlank()) {
            Text(model.subtitle, color = Retro.Muted, fontSize = if (compact) 12.sp else 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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
                    // Two lines, so a long one (the Buffering speeds) wraps instead of being cut off.
                    Text(
                        model.status.lines().first(), color = Retro.Muted, fontFamily = Retro.Mono, fontSize = 11.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
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
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration by rememberUpdatedState(model.durationMs)
    val seek by rememberUpdatedState(model.onSeek)
    val value = dragging ?: if (model.durationMs > 0) (model.positionMs.toFloat() / model.durationMs).coerceIn(0f, 1f) else 0f
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
                    dragging = f
                    down.consume()
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        f = (c.position.x / size.width).coerceIn(0f, 1f)
                        dragging = f
                        c.consume()
                    }
                    seek((f * duration).toLong())
                    dragging = null
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
                PianoText(m, "−10", "Back 10 seconds") { model.onSkip(-10_000) }
                PianoText(m, "−5", "Back 5 seconds") { model.onSkip(-5_000) }
                PianoText(m, "+5", "Forward 5 seconds") { model.onSkip(5_000) }
                PianoText(m, "+10", "Forward 10 seconds") { model.onSkip(10_000) }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(2) { i ->
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (pager.currentPage == i) Retro.Orange else Retro.Muted.copy(alpha = 0.6f)))
        }
    }
}

/** A tall key: its sloped front a shade darker at the top, the symbol on it, the name at the foot. */
@Composable
private fun PianoKey(
    modifier: Modifier, icon: ImageVector, label: String, caption: String,
    orange: Boolean = false, latched: Boolean = false, onHold: (() -> Unit)? = null, onClick: () -> Unit,
) {
    KeyBox(modifier, orange = orange, latched = latched, onHold = onHold, onClick = onClick) { _ ->
        PianoFace(orange) {
            Icon(icon, contentDescription = label, tint = if (orange) Color.White else Retro.OnMetal, modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp).size(26.dp))
            Text(
                caption, color = if (orange) Color.White else Retro.OnMetalMuted, fontFamily = Retro.Mono, fontSize = 9.sp,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 7.dp),
            )
        }
    }
}

@Composable
private fun PianoText(modifier: Modifier, text: String, label: String, onClick: () -> Unit) {
    KeyBox(modifier.semantics { contentDescription = label }, onClick = onClick) { _ ->
        PianoFace(false) {
            Text(text, color = Retro.OnMetal, fontFamily = Retro.Mono, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun PianoFace(orange: Boolean, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            // The key's sloped front: a band a little darker across its top third.
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = if (orange) 0.10f else 0.07f), 0.32f to Color.Black.copy(alpha = 0.02f),
                    0.33f to Color.White.copy(alpha = if (orange) 0.10f else 0.35f), 0.345f to Color.Transparent, 1f to Color.Transparent,
                ),
            )
        },
        content = content,
    )
}

/**
 * The transport keys; swipe them left for −10 −5 +5 +10. Two dots show which is up.
 */
@Composable
private fun TransportPager(model: DeckModel, compact: Boolean) {
    val keySize = if (compact) 44.dp else 54.dp
    val pager = rememberPagerState { 2 }
    // The keys plus the strip around them.
    HorizontalPager(pager, Modifier.fillMaxWidth().height(keySize + 14.dp)) { page ->
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // As many square keys as the row holds, shrunk if the width is short.
            val n = if (page == 0) (if (compact) 3 else 2 + (if (model.shuffle != null) 1 else 0) + (if (model.repeat != null) 1 else 0) + 1)
            else 4 + (if (compact && model.shuffle != null) 1 else 0) + (if (compact && model.repeat != null) 1 else 0)
            val fit = (maxWidth - 12.dp - 5.dp * (n - 1)) / n
            val k = minOf(keySize, fit)
            if (page == 0) TransportRow(model, k, compact) else SkipRow(model, k, compact)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(2) { i ->
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (pager.currentPage == i) Retro.Orange else Retro.MetalEdge))
        }
    }
}

/**
 * One row of square keys in their grey strip, as on the Walkman's side: PLAY is the orange one, and
 * it stays pressed in while the tape runs.
 */
@Composable
private fun TransportRow(model: DeckModel, k: Dp, compact: Boolean) {
    KeyStrip {
        // Beside a cover on a phone held sideways there's only room for the three keys;
        // shuffle and repeat move to the second page there.
        if (!compact) ShuffleKey(model, k)
        // Hold to rewind / fast-forward; tap for previous / next.
        DeckKey(Icons.Filled.SkipPrevious, "Previous", "REW", k, onHold = { model.onSkip(-5_000) }, onClick = model.onPrevious)
        DeckKey(if (model.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", if (model.playing) "PAUSE" else "PLAY", k, orange = true, latched = model.playing, onClick = model.onToggle)
        DeckKey(Icons.Filled.SkipNext, "Next", "FF", k, onHold = { model.onSkip(5_000) }, onClick = model.onNext)
        if (!compact) RepeatKey(model, k)
    }
}

@Composable
private fun ShuffleKey(model: DeckModel, k: Dp) {
    model.shuffle?.let { on ->
        DeckKey(Icons.Filled.Shuffle, "Shuffle", "SHUF", k, led = on, onClick = model.onShuffle)
    }
}

@Composable
private fun RepeatKey(model: DeckModel, k: Dp) {
    model.repeat?.let { r ->
        DeckKey(
            if (r == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat", if (r == RepeatMode.ONE) "RPT 1" else "RPT", k,
            led = r != RepeatMode.OFF, onClick = model.onRepeat,
        )
    }
}

/** −10 −5 +5 +10 seconds (with shuffle and repeat at the ends when the deck is compact). */
@Composable
private fun SkipRow(model: DeckModel, k: Dp, compact: Boolean) {
    KeyStrip {
        if (compact) ShuffleKey(model, k)
        TextKey("−10", "Back 10 seconds", k) { model.onSkip(-10_000) }
        TextKey("−5", "Back 5 seconds", k) { model.onSkip(-5_000) }
        TextKey("+5", "Forward 5 seconds", k) { model.onSkip(5_000) }
        TextKey("+10", "Forward 10 seconds", k) { model.onSkip(10_000) }
        if (compact) RepeatKey(model, k)
    }
}

/** A square key with its value printed on it. */
@Composable
private fun TextKey(text: String, label: String, k: Dp, onClick: () -> Unit) {
    SquareKey(k, onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) { _ ->
        Text(text, color = Retro.OnMetal, fontFamily = Retro.Mono, fontSize = if (k < 44.dp) 11.sp else 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusLine(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text,
            color = color,
            fontFamily = Retro.Mono,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * A square key with its name printed under the symbol. [orange] is the one orange key; [latched]
 * keeps it pressed in (PLAY while the tape runs); [led] gives it a little light, orange when on.
 */
@Composable
internal fun DeckKey(
    icon: ImageVector, label: String, caption: String, k: Dp = 54.dp,
    orange: Boolean = false, latched: Boolean = false, led: Boolean? = null,
    onHold: (() -> Unit)? = null, onClick: () -> Unit,
) {
    SquareKey(k, orange = orange, latched = latched, led = led, onHold = onHold, onClick = onClick) { _ ->
        val ink = if (orange) Color.White else Retro.OnMetal
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(if (k < 46.dp) 18.dp else 22.dp))
            if (k >= 40.dp) Text(caption, color = if (orange) Color.White else Retro.OnMetalMuted, fontFamily = Retro.Mono, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
        }
    }
}

/**
 * The square key itself: it sinks while held (and stays down while [latched]). A tap is a tap;
 * with [onHold], holding repeats it every 250 ms like a tape deck's FF/REW.
 */
@Composable
internal fun SquareKey(
    k: Dp, modifier: Modifier = Modifier, orange: Boolean = false, latched: Boolean = false, led: Boolean? = null,
    onHold: (() -> Unit)? = null, onClick: () -> Unit, content: @Composable (down: Boolean) -> Unit,
) = KeyBox(modifier.size(k), orange, latched, led, onHold, onClick, content)

/** A key of any size [modifier] gives it, pressed and held as [SquareKey]. */
@Composable
internal fun KeyBox(
    modifier: Modifier, orange: Boolean = false, latched: Boolean = false, led: Boolean? = null,
    onHold: (() -> Unit)? = null, onClick: () -> Unit, content: @Composable (down: Boolean) -> Unit,
) {
    val hold by rememberUpdatedState(onHold)
    val click by rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }
    val down = pressed || latched
    Box(
        modifier
            .padding(top = if (down) 1.5.dp else 0.dp, bottom = if (down) 0.dp else 1.5.dp)
            .keyFace(orange = orange, down = down)
            .then(if (led != null) Modifier.drawWithContent { drawContent(); drawKeyLed(led) } else Modifier)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    try {
                        keyGesture(hold, click)
                    } finally {
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { content(down) }
}

/** After the press: a tap, or (with [hold]) a hold that repeats every 250 ms. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.keyGesture(hold: (() -> Unit)?, click: () -> Unit) {
    if (hold == null) {
        if (waitForUpOrCancellation() != null) click()
        return
    }
    // A timeout and a cancelled touch both come back as null, so tell them apart: a swipe that
    // starts on the key (the pager or a list taking the touch) must not count as holding it.
    var ended = KeyEnd.HELD
    withTimeoutOrNull(400) { ended = if (waitForUpOrCancellation() != null) KeyEnd.UP else KeyEnd.CANCELLED }
    when (ended) {
        KeyEnd.UP -> { click(); return }
        KeyEnd.CANCELLED -> return
        KeyEnd.HELD -> {}
    }
    while (true) {
        hold()
        ended = KeyEnd.HELD
        withTimeoutOrNull(250) { ended = if (waitForUpOrCancellation() != null) KeyEnd.UP else KeyEnd.CANCELLED }
        if (ended != KeyEnd.HELD || currentEvent.changes.all { !it.pressed }) return
    }
}

private enum class KeyEnd { HELD, UP, CANCELLED }

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
