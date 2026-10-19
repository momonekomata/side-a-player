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
    val speed: Float = 1f,
    val onSpeed: (Float) -> Unit = {},
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
    val panel = modifier
        .clip(RoundedCornerShape(10.dp))
        .background(Retro.Panel)
        .padding(if (compact) 8.dp else 12.dp)
    if (compact) {
        Row(panel, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtSquare(model, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true))
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                Controls(model, compact = true) { showPath = true }
            }
        }
    } else {
        // The square gets whatever height the controls leave, so the keys never fall off screen.
        Column(panel, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ArtSquare(
                model,
                Modifier
                    .weight(1f, fill = false)
                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                    .align(Alignment.CenterHorizontally),
            )
            Controls(model, compact = false) { showPath = true }
        }
    }
    val path = model.path
    if (showPath && path != null) SignalPathDialog(path, onDismiss = { showPath = false })
}

@Composable
private fun Controls(model: DeckModel, compact: Boolean, onPath: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                model.title.ifBlank { "Nothing playing" },
                color = Color.White,
                fontSize = if (compact) 16.sp else 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (model.subtitle.isNotBlank()) {
                Text(model.subtitle, color = Retro.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        model.favorite?.let { fav ->
            IconButton(onClick = model.onFavorite, modifier = Modifier.size(40.dp)) {
                Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", tint = if (fav) Retro.Orange else Retro.Muted)
            }
        }
    }
    val path = model.path
    if (path != null) {
        // The badge and the format; the rest of the signal path is a tap on the badge away.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QualityBadge(path.quality, onPath)
            Text(
                model.status.lines().first(),
                color = Retro.Muted,
                fontFamily = Retro.Mono,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onPath),
            )
        }
    } else {
        StatusLine(model.status, model.statusColor)
    }
    SeekBar(model)
    TransportPager(model, if (compact) 42.dp else 48.dp)
}

/**
 * The transport keys; swipe them left for −10 −5 speed +5 +10. Two dots show which is up.
 */
@Composable
private fun TransportPager(model: DeckModel, keyHeight: Dp) {
    val pager = rememberPagerState { 2 }
    HorizontalPager(pager, Modifier.fillMaxWidth().height(maxOf(keyHeight, 48.dp))) { page ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (page == 0) TransportRow(model, keyHeight) else SkipRow(model)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(2) { i ->
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (pager.currentPage == i) Retro.Orange else Retro.ShellEdge))
        }
    }
}

@Composable
private fun TransportRow(model: DeckModel, h: Dp) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        model.shuffle?.let { on ->
            IconButton(onClick = model.onShuffle) { Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (on) Retro.Orange else Retro.Muted) }
        }
        // Hold to rewind / fast-forward; tap for previous / next.
        DeckKey(Icons.Filled.SkipPrevious, "Previous", height = h, onHold = { model.onSkip(-5_000) }, onClick = model.onPrevious)
        DeckKey(if (model.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", wide = true, lit = model.playing, height = h, onClick = model.onToggle)
        DeckKey(Icons.Filled.SkipNext, "Next", height = h, onHold = { model.onSkip(5_000) }, onClick = model.onNext)
        model.repeat?.let { r ->
            IconButton(onClick = model.onRepeat) {
                Icon(if (r == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat", tint = if (r != RepeatMode.OFF) Retro.Orange else Retro.Muted)
            }
        }
    }
}

/** The cover, or the lyrics in its place; one tap swaps them. */
@Composable
private fun ArtSquare(model: DeckModel, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF111111))
            .clickable(onClick = model.onToggleLyrics),
    ) {
        Crossfade(model.showLyrics, label = "art-lyrics") { lyricsSide ->
            if (lyricsSide) {
                LyricsSquare(model.lyrics, model.positionMs, model.onRetryLyrics, Modifier.fillMaxSize())
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
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** −10 −5 [speed] +5 +10. The speed chip steps through 0.5×…2×; a long press returns to 1×. */
@Composable
private fun SkipRow(model: DeckModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        SmallKey("−10") { model.onSkip(-10_000) }
        SmallKey("−5") { model.onSkip(-5_000) }
        val label = "%.2f".format(model.speed).trimEnd('0').trimEnd('.') + "×"
        SmallKey(label, lit = model.speed != 1f, onLong = { model.onSpeed(1f) }) {
            model.onSpeed(SPEEDS[(SPEEDS.indexOf(model.speed).coerceAtLeast(0) + 1) % SPEEDS.size])
        }
        SmallKey("+5") { model.onSkip(5_000) }
        SmallKey("+10") { model.onSkip(10_000) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SmallKey(text: String, lit: Boolean = false, onLong: (() -> Unit)? = null, onClick: () -> Unit) {
    Text(
        text,
        color = if (lit) Retro.Orange else Color(0xFFD0D0D0),
        fontFamily = Retro.Mono,
        fontSize = 13.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, if (lit) Retro.Orange else Retro.ShellEdge, RoundedCornerShape(6.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
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
                inactiveTrackColor = Retro.ShellEdge,
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime((value * duration).toLong()), color = Retro.Muted, fontFamily = Retro.Mono, fontSize = 11.sp)
            Text(if (model.durationMs > 0) formatTime(model.durationMs) else "--:--", color = Retro.Muted, fontFamily = Retro.Mono, fontSize = 11.sp)
        }
    }
}

/** A chunky piano-key transport button like a tape deck's. */
@Composable
private fun DeckKey(icon: ImageVector, label: String, wide: Boolean = false, lit: Boolean = false, height: Dp = 52.dp, onHold: (() -> Unit)? = null, onClick: () -> Unit) {
    val hold by rememberUpdatedState(onHold)
    val click by rememberUpdatedState(onClick)
    Box(
        Modifier
            .size(width = if (wide) 88.dp else 64.dp, height = height)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2B2B2B))
            .border(1.dp, if (lit) Retro.Orange else Retro.ShellEdge, RoundedCornerShape(6.dp))
            .pointerInput(Unit) {
                // A tap is a tap; holding repeats [onHold] every 250 ms like a tape deck's FF/REW.
                awaitEachGesture {
                    awaitFirstDown()
                    val holder = hold
                    if (holder == null) {
                        if (waitForUpOrCancellation() != null) click()
                        return@awaitEachGesture
                    }
                    val released = withTimeoutOrNull(400) { waitForUpOrCancellation() }
                    if (released != null) {
                        click()
                        return@awaitEachGesture
                    }
                    var up = false
                    while (!up) {
                        holder()
                        up = withTimeoutOrNull(250) { waitForUpOrCancellation() } != null ||
                            currentEvent.changes.all { !it.pressed }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (lit) Retro.Orange else Color(0xFFE0E0E0))
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
