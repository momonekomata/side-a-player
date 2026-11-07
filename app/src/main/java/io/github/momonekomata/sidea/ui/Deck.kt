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
 * Cover art, full size, with the lyrics in the same square: tap to swap. Below it the title,
 * the quality badge with the signal path, the seek bar and the transport keys. [compact] lays the
 * square beside the controls, for a phone on its side.
 */
@Composable
fun Deck(model: DeckModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    var showPath by remember { mutableStateOf(false) }
    // The faceplate: brushed aluminium, printed in dark ink.
    val panel = modifier
        .clip(RoundedCornerShape(10.dp))
        .brushedMetal()
    if (compact) {
        // A phone on its side: the cover no wider than about two fifths of the panel, so the
        // song, the badge and all the keys get a roomy column beside it.
        BoxWithConstraints(panel.padding(10.dp)) {
            val art = minOf(maxHeight, maxWidth * 0.42f)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtSquare(model, Modifier.size(art))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                    Controls(model, compact = true) { showPath = true }
                }
            }
        }
    } else {
        // The cover edge to edge on top, the song and its badge under it (tap the badge for the
        // signal path), the transport pinned to the bottom. The cover shrinks only when the
        // screen is too short for all three.
        BoxWithConstraints(panel) {
            val art = minOf(maxWidth, maxHeight - 250.dp).coerceAtLeast(120.dp)
            Column(Modifier.fillMaxSize()) {
                ArtSquare(model, Modifier.size(art).align(Alignment.CenterHorizontally), shape = RectangleShape)
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SongInfo(model, compact = false) { showPath = true }
                }
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                    SeekBar(model)
                    TransportPager(model, compact = false)
                }
            }
        }
    }
    val path = model.path
    if (showPath && path != null) SignalPathDialog(path, onDismiss = { showPath = false })
}

@Composable
private fun Controls(model: DeckModel, compact: Boolean, onPath: () -> Unit) {
    SongInfo(model, compact, onPath)
    SeekBar(model)
    TransportPager(model, compact)
}

/** Title, artist and album, the heart, and the quality badge with the format beside it. */
@Composable
private fun SongInfo(model: DeckModel, compact: Boolean, onPath: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                model.title.ifBlank { "Nothing playing" },
                color = Retro.OnMetal,
                fontSize = if (compact) 16.sp else 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (model.subtitle.isNotBlank()) {
                Text(model.subtitle, color = Retro.OnMetalMuted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        model.favorite?.let { fav ->
            IconButton(onClick = model.onFavorite, modifier = Modifier.size(40.dp)) {
                Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", tint = if (fav) Retro.Orange else Retro.OnMetalMuted)
            }
        }
    }
    val path = model.path
    if (path != null) {
        // The badge and the format; the rest of the signal path is a tap on the badge away.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QualityBadge(path.quality, onPath)
            // Two lines, so a long one (the Buffering speeds) wraps instead of being cut off.
            Text(
                model.status.lines().first(),
                color = Retro.OnMetalMuted,
                fontFamily = Retro.Mono,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clickable(onClick = onPath),
            )
        }
    } else {
        StatusLine(model.status, model.statusColor)
    }
}

/**
 * The transport keys; swipe them left for −10 −5 +5 +10. Two dots show which is up.
 */
@Composable
internal fun TransportPager(model: DeckModel, compact: Boolean) {
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
internal fun ShuffleKey(model: DeckModel, k: Dp) {
    model.shuffle?.let { on ->
        DeckKey(Icons.Filled.Shuffle, "Shuffle", "SHUF", k, led = on, onClick = model.onShuffle)
    }
}

@Composable
internal fun RepeatKey(model: DeckModel, k: Dp) {
    model.repeat?.let { r ->
        DeckKey(
            if (r == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat", if (r == RepeatMode.ONE) "RPT 1" else "RPT", k,
            led = r != RepeatMode.OFF, onClick = model.onRepeat,
        )
    }
}

/** The cover, or the lyrics in its place; one tap swaps them. */
@Composable
private fun ArtSquare(model: DeckModel, modifier: Modifier, shape: Shape = RoundedCornerShape(8.dp)) {
    Box(
        modifier
            .clip(shape)
            .background(Color(0xFF111111))
            .clickable(onClick = model.onToggleLyrics),
    ) {
        Crossfade(model.showLyrics, label = "art-lyrics") { lyricsSide ->
            if (lyricsSide) {
                LyricsSquare(model.lyrics, model.positionMs, model.playing, model.onRetryLyrics, model.onSeek, Modifier.fillMaxSize())
            } else {
                val art = model.art
                if (art != null) {
                    Image(art, "Cover", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(Retro.Shell), contentAlignment = Alignment.Center) {
                        Text("A", color = Retro.Orange, fontSize = 72.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        // A hint for what a tap does, and that lyrics exist at all.
        val hint = when {
            model.showLyrics -> "COVER"
            model.lyrics.lyrics != null -> "LYRICS"
            model.lyrics.loading -> "LYRICS…"
            else -> null
        }
        if (hint != null) {
            Text(
                hint,
                color = Color.White,
                fontFamily = Retro.Mono,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    // Timed lyric lines take taps themselves (to jump), so the chip swaps back too.
                    .clickable(onClick = model.onToggleLyrics)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
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
internal fun StatusLine(text: String, color: Color) {
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

@Composable
private fun SeekBar(model: DeckModel) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = model.durationMs.coerceAtLeast(1)
    val value = dragging ?: (model.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    Column {
        Slider(
            value = value,
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { model.onSeek((it * duration).toLong()) }
                dragging = null
            },
            enabled = model.durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = Retro.Orange,
                activeTrackColor = Retro.Orange,
                inactiveTrackColor = Retro.MetalEdge,
                disabledInactiveTrackColor = Retro.MetalEdge.copy(alpha = 0.6f),
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime((value * duration).toLong()), color = Retro.OnMetalMuted, fontFamily = Retro.Mono, fontSize = 11.sp)
            Text(if (model.durationMs > 0) formatTime(model.durationMs) else "--:--", color = Retro.OnMetalMuted, fontFamily = Retro.Mono, fontSize = 11.sp)
        }
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
