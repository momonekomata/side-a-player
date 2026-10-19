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
import io.github.momonekomata.sidea.audio.SignalPath
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
    /** Local playback only: the graded signal path behind the quality badge. */
    val path: SignalPath? = null,
    val art: ImageBitmap? = null,
    val onToggle: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Long) -> Unit,
)

@Composable
fun Deck(model: DeckModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    var showCover by remember { mutableStateOf(false) }
    var showPath by remember { mutableStateOf(false) }
    Column(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Retro.Panel)
            .padding(if (compact) 10.dp else 16.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 14.dp),
    ) {
        if (!compact) {
            Text(
                "STREAMING · CASSETTE · PLAYER",
                color = Retro.Muted,
                fontFamily = Retro.Mono,
                fontSize = 10.sp,
                letterSpacing = 3.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
        val progress = if (model.durationMs > 0) model.positionMs.toFloat() / model.durationMs else 0f
        // Compact (landscape phone): the cassette is sized by height so the controls still fit.
        val sizing = if (compact) Modifier.height(150.dp).align(Alignment.CenterHorizontally) else Modifier.fillMaxWidth()
        val tapCover = sizing.clickable(enabled = model.art != null) { showCover = !showCover }
        if (showCover && model.art != null) {
            CoverView(model.art, tapCover)
        } else {
            Cassette(model.title, model.subtitle, progress, model.playing, tapCover, model.art)
        }
        val path = model.path
        if (path != null) {
            // Badge on its own line; source and output each get a full line below, wrapping if
            // they must rather than being cut off.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                QualityBadge(path.quality) { showPath = true }
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
    val path = model.path
    if (showPath && path != null) SignalPathDialog(path, onDismiss = { showPath = false })
}

/** The cover in place of the cassette, framed like the front of the case. */
@Composable
private fun CoverView(art: ImageBitmap, modifier: Modifier) {
    Box(
        modifier
            .aspectRatio(1.58f)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF111111))
            .border(2.dp, Retro.ShellEdge, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(art, "Cover", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxHeight().padding(10.dp).clip(RoundedCornerShape(4.dp)))
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
