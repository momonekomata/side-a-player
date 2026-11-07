package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The tape in the deck: a cassette whose reels turn while it plays, the tape winding from the
 * left reel onto the right as the song goes on, and the song's name and artist written on the
 * label. The transport keys sit under it, so it's a full player too.
 */
@Composable
fun CassettePage(model: DeckModel, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).brushedMetal().padding(if (compact) 8.dp else 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp, Alignment.CenterVertically),
    ) {
        val progress = if (model.durationMs > 0) (model.positionMs.toFloat() / model.durationMs).coerceIn(0f, 1f) else 0f
        BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth(), contentAlignment = Alignment.Center) {
            // As big as fits, in a cassette's proportions.
            val w = minOf(maxWidth, maxHeight * CASSETTE_RATIO)
            Cassette(model.title.ifBlank { "Nothing playing" }, model.subtitle, progress, model.playing, Modifier.width(w).aspectRatio(CASSETTE_RATIO))
        }
        Text(
            "${formatTime(model.positionMs)} / ${if (model.durationMs > 0) formatTime(model.durationMs) else "--:--"}",
            color = Retro.OnMetalMuted, fontFamily = Retro.Mono, fontSize = 12.sp, letterSpacing = 1.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val h = if (compact) 44.dp else 52.dp
            DeckKey(Icons.Filled.SkipPrevious, "Previous", "REW", height = h, onHold = { model.onSkip(-5_000) }, onClick = model.onPrevious)
            DeckKey(if (model.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", if (model.playing) "PAUSE" else "PLAY", wide = true, lit = model.playing, height = h, onClick = model.onToggle)
            DeckKey(Icons.Filled.SkipNext, "Next", "FF", height = h, onHold = { model.onSkip(5_000) }, onClick = model.onNext)
        }
    }
}

private const val CASSETTE_RATIO = 1.58f

@Composable
private fun Cassette(title: String, artist: String, progress: Float, playing: Boolean, modifier: Modifier) {
    // The hubs turn at a speed set by the tape: a reel with little tape on it spins faster.
    var supplyAngle by remember { mutableFloatStateOf(0f) }
    var takeupAngle by remember { mutableFloatStateOf(0f) }
    val supply = packRadius(1f - progress)
    val takeup = packRadius(progress)
    LaunchedEffect(playing, supply, takeup) {
        if (!playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1e9f
                    supplyAngle = (supplyAngle + TAPE_SPEED / supply * dt) % 360f
                    takeupAngle = (takeupAngle + TAPE_SPEED / takeup * dt) % 360f
                }
                last = now
            }
        }
    }
    BoxWithConstraints(modifier) {
        val maxHeight = this.maxHeight
        val maxWidth = this.maxWidth
        Canvas(Modifier.fillMaxSize()) { drawCassette(supply, takeup, supplyAngle, takeupAngle) }
        // The label: written on, like a tape you made yourself, in the middle above the window.
        val labelTop = maxHeight * 0.13f
        Column(
            Modifier.fillMaxWidth().offset(y = labelTop).padding(horizontal = maxWidth * 0.12f),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                title, color = Retro.Ink, fontFamily = Retro.Logo, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
                fontSize = (maxHeight.value * 0.075f).coerceIn(12f, 22f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            if (artist.isNotBlank()) {
                Text(
                    artist, color = Retro.InkFaint, fontSize = (maxHeight.value * 0.05f).coerceIn(10f, 15f).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                )
            }
        }
        // "A" on the label's corner, as on a real tape's side marking.
        Box(Modifier.offset(x = maxWidth * 0.085f, y = maxHeight * 0.12f).size(maxHeight * 0.1f), contentAlignment = Alignment.Center) {
            Text("A", color = Retro.Orange, fontFamily = Retro.Logo, fontWeight = FontWeight.Black, fontSize = (maxHeight.value * 0.07f).coerceIn(10f, 20f).sp)
        }
    }
}

/** Degrees per second times radius units: how fast the hubs turn for the tape's speed. */
private const val TAPE_SPEED = 55f

/** The radius of a tape pack (as a share of the window's half-height) holding [share] of the tape. */
private fun packRadius(share: Float): Float {
    val hub = 0.32f
    val full = 0.95f
    // The tape's area grows with the square of the radius.
    return sqrt(hub * hub + (full * full - hub * hub) * share.coerceIn(0f, 1f))
}

private fun DrawScope.drawCassette(supply: Float, takeup: Float, supplyAngle: Float, takeupAngle: Float) {
    val w = size.width
    val h = size.height
    val r = h * 0.06f
    // The shell: dark smoked plastic with a lighter edge.
    drawRoundRect(Color(0xFF26282D), cornerRadius = CornerRadius(r, r))
    drawRoundRect(Color(0xFF3B3E45), cornerRadius = CornerRadius(r, r), style = Stroke(h * 0.012f))
    // Screws in the corners.
    listOf(Offset(w * 0.04f, h * 0.06f), Offset(w * 0.96f, h * 0.06f), Offset(w * 0.04f, h * 0.94f), Offset(w * 0.96f, h * 0.94f)).forEach { c ->
        drawCircle(Color(0xFF9AA0A8), h * 0.022f, c)
        drawLine(Color(0xFF55595F), c - Offset(h * 0.014f, 0f), c + Offset(h * 0.014f, 0f), h * 0.006f)
    }
    // The label: aluminium-white with the Walkman's orange and blue stripes along its foot.
    val labelL = w * 0.07f
    val labelT = h * 0.08f
    val labelW = w * 0.86f
    val labelH = h * 0.64f
    drawRoundRect(Retro.Cream, Offset(labelL, labelT), Size(labelW, labelH), CornerRadius(r * 0.6f, r * 0.6f))
    drawRect(Retro.Orange, Offset(labelL, labelT + labelH * 0.86f), Size(labelW, labelH * 0.055f))
    drawRect(Retro.Background, Offset(labelL, labelT + labelH * 0.915f), Size(labelW, labelH * 0.055f))
    // The window over the reels, clear of the stripes.
    val winL = w * 0.2f
    val winT = h * 0.37f
    val winW = w * 0.6f
    val winH = h * 0.24f
    drawRoundRect(Color(0xFF15161A), Offset(winL, winT), Size(winW, winH), CornerRadius(winH * 0.5f, winH * 0.5f))
    val cy = winT + winH / 2
    val unit = winH / 2
    val left = Offset(w * 0.33f, cy)
    val right = Offset(w * 0.67f, cy)
    // The tape packs: brown, a little glossy.
    val tape = Brush.radialGradient(listOf(Color(0xFF6B4A33), Color(0xFF3E2A1D)))
    drawCircle(tape, supply * unit, left)
    drawCircle(tape, takeup * unit, right)
    // The hubs: white with six teeth, turning.
    drawHub(left, unit * 0.42f, supplyAngle)
    drawHub(right, unit * 0.42f, takeupAngle)
    // The foot: the trapezoid where the heads go in, with its holes.
    val footT = h * 0.78f
    val foot = Path().apply {
        moveTo(w * 0.2f, h)
        lineTo(w * 0.26f, footT)
        lineTo(w * 0.74f, footT)
        lineTo(w * 0.8f, h)
        close()
    }
    drawPath(foot, Color(0xFF1C1D21))
    listOf(0.34f, 0.5f, 0.66f).forEach { x -> drawCircle(Color(0xFF0C0C0E), h * 0.035f, Offset(w * x, h * 0.89f)) }
    drawRect(Color(0xFF0C0C0E), Offset(w * 0.29f, h * 0.86f), Size(w * 0.03f, h * 0.06f))
    drawRect(Color(0xFF0C0C0E), Offset(w * 0.68f, h * 0.86f), Size(w * 0.03f, h * 0.06f))
}

private fun DrawScope.drawHub(c: Offset, radius: Float, angle: Float) {
    drawCircle(Color(0xFFE9ECEF), radius, c)
    drawCircle(Color(0xFF15161A), radius * 0.55f, c)
    rotate(angle, c) {
        repeat(6) { i ->
            val a = (i * 60.0) * PI / 180
            val p = Offset(c.x + (radius * 0.55f * cos(a)).toFloat(), c.y + (radius * 0.55f * sin(a)).toFloat())
            drawCircle(Color(0xFFE9ECEF), radius * 0.16f, p)
        }
        // A spoke so the turning is easy to see.
        drawLine(Color(0xFFE9ECEF), c, Offset(c.x + radius * 0.5f, c.y), radius * 0.14f)
    }
}
