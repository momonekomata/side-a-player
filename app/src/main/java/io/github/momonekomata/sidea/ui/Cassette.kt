package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The TAPE page: the song's cover, blurred and dimmed, behind a cassette whose label carries
 * everything: the cover, title and artist, the format and quality light, the counter between
 * the reels (which turn while it plays, the tape winding from left to right as the song goes on),
 * and the transport keys. All the writing is on the opaque label, so it reads on any cover.
 */
@Composable
fun CassettePage(model: DeckModel, modifier: Modifier = Modifier, onFullScreen: (() -> Unit)? = null) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        CoverBackdrop(model.art)
        // A tape lies along the screen's long side: on an upright phone it's drawn turned a
        // quarter to the left (read it with the phone on its side), so it fills the page.
        val upright = maxHeight > maxWidth
        Box(Modifier.fillMaxSize().then(if (upright) Modifier.sideways() else Modifier).padding(6.dp), contentAlignment = Alignment.Center) {
            CassetteFit(model)
        }
        if (onFullScreen != null) {
            Pill("⛶ FULL SCREEN", Modifier.align(Alignment.TopEnd).padding(8.dp), onFullScreen)
        }
    }
}

/**
 * The tape on its own, edge to edge: the cassette's shell is the screen's edge. On an upright
 * phone it lies along the screen's length, turned a quarter to the left.
 */
@Composable
fun TapeFullScreen(model: DeckModel, closeLabel: String, onClose: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF26282D))) {
        val upright = maxHeight > maxWidth
        Box(Modifier.fillMaxSize().then(if (upright) Modifier.sideways() else Modifier)) {
            CassetteFit(model)
            // On the shell's bare corner, between the screw and the foot, clear of the label.
            Pill(closeLabel, Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 16.dp), onClose)
        }
    }
}

/**
 * The cassette filling the space it's given: stretched to the space when that's anything like
 * a tape's shape (long and low), otherwise as large as fits in a tape's proportions.
 */
@Composable
private fun CassetteFit(model: DeckModel) {
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val ratio = maxWidth / maxHeight
        val m = when {
            ratio < MIN_RATIO -> Modifier.width(maxWidth).height(maxWidth / MIN_RATIO)
            ratio > MAX_RATIO -> Modifier.height(maxHeight).width(maxHeight * MAX_RATIO)
            else -> Modifier.fillMaxSize()
        }
        Cassette(model, m)
    }
}

private const val MIN_RATIO = 1.45f
private const val MAX_RATIO = 2.4f

/** Lays the content out a quarter turn to the left: its width along this space's height. */
private fun Modifier.sideways() = layout { measurable, c ->
    val p = measurable.measure(Constraints.fixed(c.maxHeight, c.maxWidth))
    layout(c.maxWidth, c.maxHeight) {
        p.placeWithLayer((c.maxWidth - p.width) / 2, (c.maxHeight - p.height) / 2) { rotationZ = -90f }
    }
}

/** A dark pill over the cover, readable on any picture. */
@Composable
private fun Pill(text: String, modifier: Modifier, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontFamily = Retro.Mono, fontSize = 11.sp, letterSpacing = 1.sp,
        modifier = modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** Behind the cassette. */
@Composable
private fun CoverBackdrop(art: ImageBitmap?) {
    // The cover is on the tape's label now; behind the tape is the Walkman's blue.
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Retro.Shell, Retro.Panel))))
}

private const val CASSETTE_RATIO = 1.58f

@Composable
private fun Cassette(model: DeckModel, modifier: Modifier) {
    val progress = if (model.durationMs > 0) (model.positionMs.toFloat() / model.durationMs).coerceIn(0f, 1f) else 0f
    // The hubs turn at a speed set by the tape: a reel with little tape on it spins faster.
    var supplyAngle by remember { mutableFloatStateOf(0f) }
    var takeupAngle by remember { mutableFloatStateOf(0f) }
    val supply = packRadius(1f - progress)
    val takeup = packRadius(progress)
    LaunchedEffect(model.playing, supply, takeup) {
        if (!model.playing) return@LaunchedEffect
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
        val w = this.maxWidth
        val h = this.maxHeight
        fun font(share: Float, min: Float, max: Float) = (h.value * share).coerceIn(min, max).sp
        Canvas(Modifier.fillMaxSize()) { drawShell() }
        // The cover printed on the label, shaded so the white writing on it reads on any picture.
        val art = model.art
        val onArt = art != null
        if (art != null) {
            Box(
                Modifier.offset(x = w * LABEL_L, y = h * LABEL_T).size(w * LABEL_W, h * LABEL_H)
                    .clip(RoundedCornerShape(h * 0.036f)),
            ) {
                Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Black.copy(alpha = 0.5f), Color.Black.copy(alpha = 0.7f)))))
            }
        }
        Canvas(Modifier.fillMaxSize()) { drawMechanism(supply, takeup, supplyAngle, takeupAngle) }
        // Ink on the bare label; white with a soft shadow on the cover.
        val ink = if (onArt) Color.White else Retro.Ink
        val faint = if (onArt) Color.White.copy(alpha = 0.88f) else Retro.InkFaint
        val shade = if (onArt) androidx.compose.ui.text.TextStyle(shadow = androidx.compose.ui.graphics.Shadow(Color.Black, blurRadius = 8f)) else androidx.compose.ui.text.TextStyle.Default

        // Top of the label: cover, title and artist, the side.
        Row(
            Modifier.offset(x = w * LABEL_L + h * 0.03f, y = h * 0.115f).width(w * LABEL_W - h * 0.06f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumb = h * 0.17f
            if (art != null) {
                Image(art, "Cover", contentScale = ContentScale.Crop, modifier = Modifier.size(thumb).clip(RoundedCornerShape(4.dp)))
            } else {
                Box(Modifier.size(thumb).clip(RoundedCornerShape(4.dp)).background(Retro.Shell), contentAlignment = Alignment.Center) {
                    Text("A", color = Retro.Orange, fontWeight = FontWeight.Black, fontSize = font(0.09f, 12f, 26f))
                }
            }
            Column(Modifier.weight(1f).padding(start = h * 0.035f)) {
                Text(
                    model.title.ifBlank { "Nothing playing" }, color = ink, style = shade, fontFamily = Retro.Logo, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic,
                    fontSize = font(0.07f, 12f, 26f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (model.subtitle.isNotBlank()) {
                    Text(model.subtitle, color = faint, style = shade, fontSize = font(0.048f, 10f, 17f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("A", color = Retro.Orange, fontFamily = Retro.Logo, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = font(0.09f, 14f, 30f), modifier = Modifier.padding(start = h * 0.02f))
        }

        // The counter, in the window between the reels.
        Box(Modifier.offset(x = w * 0.41f, y = h * (WIN_T + WIN_H / 2) - h * 0.045f).width(w * 0.18f), contentAlignment = Alignment.Center) {
            Text(
                formatTime(model.positionMs), color = Retro.Orange, fontFamily = Retro.Mono, fontWeight = FontWeight.Bold,
                fontSize = font(0.055f, 10f, 20f), maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(3.dp)).background(Color.Black).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }

        // Under the window: the quality light and the format, and how long the song is.
        Row(
            Modifier.offset(x = w * LABEL_L, y = h * 0.585f).width(w * LABEL_W).padding(horizontal = h * 0.04f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(h * 0.025f),
        ) {
            model.path?.quality?.let { q ->
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Retro.Lcd).padding(horizontal = 6.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(q.color()))
                    Text(q.label, color = q.color(), fontFamily = Retro.Mono, fontWeight = FontWeight.Bold, fontSize = font(0.032f, 8f, 12f), modifier = Modifier.padding(start = 4.dp))
                }
            }
            Text(
                model.status.lines().first(), color = ink, style = shade, fontFamily = Retro.Mono, fontSize = font(0.038f, 9f, 14f),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Text(if (model.durationMs > 0) formatTime(model.durationMs) else "--:--", color = faint, style = shade, fontFamily = Retro.Mono, fontSize = font(0.038f, 9f, 14f))
        }

        // Where the brand would be: the keys.
        Row(
            Modifier.offset(x = w * LABEL_L, y = h * 0.66f).width(w * LABEL_W).padding(horizontal = h * 0.04f),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val key = h * 0.11f
            LabelKey(Icons.Filled.SkipPrevious, "Previous", key, outline = onArt, onClick = model.onPrevious)
            LabelKey(Icons.Filled.FastRewind, "Rewind 10 seconds", key, outline = onArt) { model.onSkip(-10_000) }
            LabelKey(if (model.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", key * 1.15f, lit = true, onClick = model.onToggle)
            LabelKey(Icons.Filled.FastForward, "Skip 10 seconds", key, outline = onArt) { model.onSkip(10_000) }
            LabelKey(Icons.Filled.SkipNext, "Next", key, outline = onArt, onClick = model.onNext)
        }
    }
}

/** A round key printed on the label: dark, or orange for play/pause. */
@Composable
private fun LabelKey(icon: ImageVector, label: String, size: Dp, lit: Boolean = false, outline: Boolean = false, onClick: () -> Unit) {
    // On a cover the dark keys get a white rim, so they stand out from a dark picture.
    Box(
        Modifier.size(size.coerceAtLeast(32.dp)).clip(CircleShape).background(if (lit) Retro.Orange else Retro.Ink)
            .then(if (outline && !lit) Modifier.border(1.5.dp, Color.White.copy(alpha = 0.75f), CircleShape) else Modifier)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.fillMaxSize().padding(size.coerceAtLeast(32.dp) * 0.2f))
    }
}

/** Degrees per second times radius units: how fast the hubs turn for the tape's speed. */
private const val TAPE_SPEED = 55f

private const val LABEL_L = 0.07f
private const val LABEL_W = 0.86f
private const val LABEL_T = 0.07f
private const val LABEL_H = 0.74f
private const val WIN_T = 0.33f
private const val WIN_H = 0.22f

/** The radius of a tape pack (as a share of the window's half-height) holding [share] of the tape. */
private fun packRadius(share: Float): Float {
    val hub = 0.32f
    val full = 0.95f
    // The tape's area grows with the square of the radius.
    return sqrt(hub * hub + (full * full - hub * hub) * share.coerceIn(0f, 1f))
}

/** Under everything: the shell, its screws, and the label's white. */
private fun DrawScope.drawShell() {
    val w = size.width
    val h = size.height
    val r = h * 0.06f
    // The shell: dark smoked plastic with a lighter edge.
    drawRoundRect(Color(0xFF26282D), cornerRadius = CornerRadius(r, r))
    drawRoundRect(Color(0xFF3B3E45), cornerRadius = CornerRadius(r, r), style = Stroke(h * 0.012f))
    // Screws in the corners.
    listOf(Offset(w * 0.035f, h * 0.06f), Offset(w * 0.965f, h * 0.06f), Offset(w * 0.035f, h * 0.94f), Offset(w * 0.965f, h * 0.94f)).forEach { c ->
        drawCircle(Color(0xFF9AA0A8), h * 0.02f, c)
        drawLine(Color(0xFF55595F), c - Offset(h * 0.013f, 0f), c + Offset(h * 0.013f, 0f), h * 0.006f)
    }
    // The label: aluminium-white with the Walkman's orange and blue stripes along its top.
    drawRoundRect(Retro.Cream, Offset(w * LABEL_L, h * LABEL_T), Size(w * LABEL_W, h * LABEL_H), CornerRadius(r * 0.6f, r * 0.6f))
}

/** Over the label (and the cover on it): the stripes, the window and its reels, the foot. */
private fun DrawScope.drawMechanism(supply: Float, takeup: Float, supplyAngle: Float, takeupAngle: Float) {
    val w = size.width
    val h = size.height
    val labelL = w * LABEL_L
    val labelT = h * LABEL_T
    val labelW = w * LABEL_W
    drawRect(Retro.Orange, Offset(labelL, labelT + h * 0.015f), Size(labelW, h * 0.014f))
    drawRect(Retro.Background, Offset(labelL, labelT + h * 0.029f), Size(labelW, h * 0.014f))
    // The window over the reels.
    val winL = w * 0.18f
    val winT = h * WIN_T
    val winW = w * 0.64f
    val winH = h * WIN_H
    drawRoundRect(Color(0xFF15161A), Offset(winL, winT), Size(winW, winH), CornerRadius(winH * 0.5f, winH * 0.5f))
    val cy = winT + winH / 2
    val unit = winH / 2
    val left = Offset(w * 0.31f, cy)
    val right = Offset(w * 0.69f, cy)
    // The tape packs: brown, a little glossy.
    val tape = Brush.radialGradient(listOf(Color(0xFF6B4A33), Color(0xFF3E2A1D)))
    drawCircle(tape, supply * unit, left)
    drawCircle(tape, takeup * unit, right)
    // The hubs: white with six teeth, turning.
    drawHub(left, unit * 0.42f, supplyAngle)
    drawHub(right, unit * 0.42f, takeupAngle)
    // The foot: the trapezoid where the heads go in, with its holes.
    val footT = h * 0.84f
    val foot = Path().apply {
        moveTo(w * 0.2f, h)
        lineTo(w * 0.25f, footT)
        lineTo(w * 0.75f, footT)
        lineTo(w * 0.8f, h)
        close()
    }
    drawPath(foot, Color(0xFF1C1D21))
    listOf(0.36f, 0.5f, 0.64f).forEach { x -> drawCircle(Color(0xFF0C0C0E), h * 0.028f, Offset(w * x, h * 0.92f)) }
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
