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
        .padding(if (compact) 10.dp else 14.dp)
    if (compact) {
        Row(panel, horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            ArtSquare(model, Modifier.fillMaxHeight().aspectRatio(1f, matchHeightConstraintsFirst = true))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Controls(model, compact = true) { showPath = true }
            }
        }
    } else {
        Column(panel, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ArtSquare(model, Modifier.fillMaxWidth().aspectRatio(1f))
            Controls(model, compact = false) { showPath = true }
        }
    }
    val path = model.path
    if (showPath && path != null) SignalPathDialog(path, onDismiss = { showPath = false })
}

@Composable
private fun Controls(model: DeckModel, compact: Boolean, onPath: () -> Unit) {
    Column {
        Text(
            model.title.ifBlank { "Nothing playing" },
            color = Color.White,
            fontSize = if (compact) 17.sp else 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (model.subtitle.isNotBlank()) {
            Text(model.subtitle, color = Retro.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val path = model.path
    if (path != null) {
        // Badge on its own line; source and output each get a full line below, wrapping if
        // they must rather than being cut off.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            QualityBadge(path.quality, onPath)
            model.status.lines().forEach { line ->
                Text(line, color = Retro.Muted, fontFamily = Retro.Mono, fontSize = 11.sp)
            }
        }
    } else {
        StatusLine(model.status, model.statusColor)
    }
    SeekBar(model)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
        val h = if (compact) 42.dp else 52.dp
        DeckKey(Icons.Filled.SkipPrevious, "Previous", height = h, onClick = model.onPrevious)
        DeckKey(if (model.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", wide = true, lit = model.playing, height = h, onClick = model.onToggle)
        DeckKey(Icons.Filled.SkipNext, "Next", height = h, onClick = model.onNext)
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
private fun DeckKey(icon: ImageVector, label: String, wide: Boolean = false, lit: Boolean = false, height: Dp = 52.dp, onClick: () -> Unit) {
    Box(
        Modifier
            .size(width = if (wide) 96.dp else 72.dp, height = height)
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2B2B2B))
            .border(1.dp, if (lit) Retro.Orange else Retro.ShellEdge, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (lit) Retro.Orange else Color(0xFFE0E0E0))
    }
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
