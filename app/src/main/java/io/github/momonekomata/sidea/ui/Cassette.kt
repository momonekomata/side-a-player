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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.draw.shadow
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
    Box(Modifier.fillMaxSize().anodizedBlue())
}

internal const val CASSETTE_RATIO = 1.58f

/**
 * The cassette for the deck's window: its label without the keys (the deck has its own), and
 * the line being sung, when there are timed lyrics, where the keys would be.
 */
@Composable
internal fun TapeOnly(model: DeckModel, line: String?, modifier: Modifier = Modifier) {
    Cassette(model, modifier.aspectRatio(CASSETTE_RATIO), keys = false, line = line)
}

@Composable
private fun Cassette(model: DeckModel, modifier: Modifier, keys: Boolean = true, line: String? = null) {
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
    val grain = remember { grainPoints() }
    BoxWithConstraints(modifier.shadow(10.dp, RoundedCornerShape(6))) {
        val w = this.maxWidth
        val h = this.maxHeight
        fun font(share: Float, min: Float, max: Float) = (h.value * share).coerceIn(min, max).sp
        Canvas(Modifier.fillMaxSize()) { drawShell(grain) }
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
        // The print on the label and the dark inside of the window; the reels turning (on a layer
        // of their own, so only they redraw while the tape runs); then the glass and the foot.
        Canvas(Modifier.fillMaxSize()) { drawLabelPrint(grain) }
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawReels(supply, takeup, supplyAngle, takeupAngle) }
        Canvas(Modifier.fillMaxSize()) { drawGlassAndFoot() }
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

        if (!keys) {
            // The tape page's cassette: just the label, as a real tape's. The time, the format and
            // the quality are on the deck's display; under the window, the line being sung.
            if (line != null) {
                Text(
                    line, color = ink, style = shade, fontWeight = FontWeight.Medium, fontSize = font(0.055f, 10f, 18f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.offset(x = w * LABEL_L, y = h * 0.6f).width(w * LABEL_W).padding(horizontal = h * 0.06f),
                )
            }
            return@BoxWithConstraints
        }

        // The counter, in the window between the reels: an inked time on a strip of paper.
        Box(Modifier.offset(x = w * 0.41f, y = h * (WIN_T + WIN_H / 2) - h * 0.045f).width(w * 0.18f), contentAlignment = Alignment.Center) {
            Text(
                formatTime(model.positionMs), color = Retro.Ink, fontFamily = Retro.Mono, fontWeight = FontWeight.Bold,
                fontSize = font(0.055f, 10f, 20f), maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(1.dp)).background(Color(0xFFEDE7D9)).padding(horizontal = 5.dp, vertical = 1.dp),
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

        // Where the brand would be: the keys, square in their grey strip, PLAY the orange one.
        Box(
            Modifier.offset(x = w * LABEL_L, y = h * 0.645f).width(w * LABEL_W),
            contentAlignment = Alignment.Center,
        ) {
            val key = (h * 0.12f).coerceAtLeast(34.dp)
            KeyStrip(gap = (key * 0.1f).coerceIn(3.dp, 6.dp)) {
                LabelKey(Icons.Filled.SkipPrevious, "Previous", key, onClick = model.onPrevious)
                LabelKey(Icons.Filled.FastRewind, "Rewind 10 seconds", key) { model.onSkip(-10_000) }
                LabelKey(if (model.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", key, orange = true, latched = model.playing, onClick = model.onToggle)
                LabelKey(Icons.Filled.FastForward, "Skip 10 seconds", key) { model.onSkip(10_000) }
                LabelKey(Icons.Filled.SkipNext, "Next", key, onClick = model.onNext)
            }
        }
    }
}

/** A square key on the label: grey, or orange (and latched while playing) for play/pause. */
@Composable
private fun LabelKey(icon: ImageVector, label: String, size: Dp, orange: Boolean = false, latched: Boolean = false, onClick: () -> Unit) {
    SquareKey(size, orange = orange, latched = latched, onClick = onClick) { _ ->
        Icon(icon, label, tint = if (orange) Color.White else Retro.OnMetal, modifier = Modifier.fillMaxSize().padding(size * 0.22f))
    }
}

/** Degrees per second times radius (as a share of the tape's height): how fast the hubs turn. */
private const val TAPE_SPEED = 24f

private const val LABEL_L = 0.07f
private const val LABEL_W = 0.86f
private const val LABEL_T = 0.07f
private const val LABEL_H = 0.74f
private const val WIN_L = 0.2f
private const val WIN_W = 0.6f
private const val WIN_T = 0.33f
private const val WIN_H = 0.22f
/** The hubs' centres, from each side. */
private const val HUB_X = 0.29f

/**
 * The radius of a tape pack, as a share of the tape's height, holding [share] of the tape: from
 * just the leader on the spool to a full pack, bigger than the window is tall.
 */
private fun packRadius(share: Float): Float {
    val empty = 0.105f
    val full = 0.29f
    // The tape's area grows with the square of the radius.
    return sqrt(empty * empty + (full * full - empty * empty) * share.coerceIn(0f, 1f))
}

/** Fixed specks for the grain of the plastic and the paper, as shares of the width and height. */
private fun grainPoints(): List<Offset> {
    val r = java.util.Random(7)
    return List(2200) { Offset(r.nextFloat(), r.nextFloat()) }
}

private fun DrawScope.windowRect() = Rect(Offset(size.width * WIN_L, size.height * WIN_T), Size(size.width * WIN_W, size.height * WIN_H))

private fun DrawScope.windowPath(): Path {
    val r = windowRect()
    return Path().apply { addRoundRect(RoundRect(r, CornerRadius(r.height * 0.12f))) }
}

/**
 * Under everything: the shell of smoked plastic, lit from the upper left, with its moulded edge,
 * the lip round the label, grain, and grip ribs at the lower corners; then the label's paper.
 */
private fun DrawScope.drawShell(grain: List<Offset>) {
    val w = size.width
    val h = size.height
    val r = h * 0.055f
    drawRoundRect(Brush.verticalGradient(listOf(Color(0xFF3B3E45), Color(0xFF27292E), Color(0xFF1C1E22))), cornerRadius = CornerRadius(r))
    // The moulding's edge: lit along the top and left, in shadow along the bottom and right.
    val edge = h * 0.014f
    drawRoundRect(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.5f)),
            start = Offset(0f, 0f), end = Offset(w * 0.45f, h),
        ),
        topLeft = Offset(edge / 2, edge / 2), size = Size(w - edge, h - edge), cornerRadius = CornerRadius(r), style = Stroke(edge),
    )
    // The grain of the plastic.
    drawPoints(grain.map { Offset(it.x * w, it.y * h) }, PointMode.Points, Color.White.copy(alpha = 0.05f), strokeWidth = 1.3f)
    // The lip the label sits inside: a groove in shadow with a lit rim outside it.
    val lip = h * 0.016f
    val lipRect = Rect(Offset(w * LABEL_L - lip, h * LABEL_T - lip), Size(w * LABEL_W + 2 * lip, h * LABEL_H + 2 * lip))
    drawRoundRect(Color.Black.copy(alpha = 0.45f), lipRect.topLeft, lipRect.size, CornerRadius(r * 0.7f), style = Stroke(h * 0.007f))
    drawRoundRect(
        Color.White.copy(alpha = 0.07f), lipRect.topLeft - Offset(h * 0.006f, h * 0.006f),
        Size(lipRect.width + h * 0.012f, lipRect.height + h * 0.012f), CornerRadius(r * 0.8f), style = Stroke(h * 0.004f),
    )
    // Grip ribs at the two lower corners, between the screws and the foot.
    for (x0 in listOf(w * 0.07f, w * 0.80f)) {
        for (i in 0 until 6) {
            val y = h * (0.845f + i * 0.021f)
            drawLine(Color.Black.copy(alpha = 0.5f), Offset(x0, y), Offset(x0 + w * 0.13f, y), h * 0.006f)
            drawLine(Color.White.copy(alpha = 0.08f), Offset(x0, y + h * 0.006f), Offset(x0 + w * 0.13f, y + h * 0.006f), h * 0.004f)
        }
    }
    // The label: slightly warm paper, a little darker towards its foot.
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFFF4F0E7), Color(0xFFE5DFD1))),
        Offset(w * LABEL_L, h * LABEL_T), Size(w * LABEL_W, h * LABEL_H), CornerRadius(h * 0.03f),
    )
}

/**
 * Over the label and the cover printed on it: the paper's fibre, the stripes, the label's edge,
 * and the window cut through it, dark inside.
 */
private fun DrawScope.drawLabelPrint(grain: List<Offset>) {
    val w = size.width
    val h = size.height
    val l = w * LABEL_L
    val t = h * LABEL_T
    val lw = w * LABEL_W
    val lh = h * LABEL_H
    clipRect(l, t, l + lw, t + lh) {
        // Paper: a matte speckle of light and dark fibre over the print.
        drawPoints(grain.map { Offset(l + it.x * lw, t + it.y * lh) }, PointMode.Points, Color.White.copy(alpha = 0.10f), strokeWidth = 1.5f)
        drawPoints(grain.take(1100).map { Offset(l + (1 - it.y) * lw, t + it.x * lh) }, PointMode.Points, Color.Black.copy(alpha = 0.10f), strokeWidth = 1.2f)
    }
    drawRect(Retro.Orange, Offset(l, t + h * 0.015f), Size(lw, h * 0.014f))
    drawRect(Retro.Background, Offset(l, t + h * 0.029f), Size(lw, h * 0.014f))
    // The paper's edge on the plastic.
    drawRoundRect(Color.Black.copy(alpha = 0.4f), Offset(l, t), Size(lw, lh), CornerRadius(h * 0.03f), style = Stroke(1.3f))
    // The window: the cassette's inside, dark, behind clear plastic; a rib down the middle.
    val win = windowRect()
    drawPath(windowPath(), Color(0xFF0A0A0C))
    drawRect(Color(0xFF151619), Offset(w * 0.485f, win.top), Size(w * 0.03f, win.height))
}

/** In the window: the two packs of tape and the white spools they're wound on, turning. */
private fun DrawScope.drawReels(supply: Float, takeup: Float, supplyAngle: Float, takeupAngle: Float) {
    val w = size.width
    val h = size.height
    val win = windowRect()
    val cy = win.center.y
    val left = Offset(w * HUB_X, cy)
    val right = Offset(w * (1 - HUB_X), cy)
    clipPath(windowPath()) {
        drawPack(left, supply * h)
        drawPack(right, takeup * h)
        drawSpool(left, h * 0.095f, supplyAngle)
        drawSpool(right, h * 0.095f, takeupAngle)
    }
}

/** A pack of tape wound tight: deep brown, fine rings where the layers lie, a satin sheen. */
private fun DrawScope.drawPack(c: Offset, r: Float) {
    drawCircle(Brush.radialGradient(listOf(Color(0xFF5C3B27), Color(0xFF3D2617), Color(0xFF25170E)), center = c, radius = r), r, c)
    var rr = r * 0.97f
    var i = 0
    val inner = size.height * 0.1f
    while (rr > inner) {
        drawCircle(if (i % 3 == 0) Color.Black.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.04f), rr, c, style = Stroke(1f))
        rr -= r * 0.03f + (i % 4) * 0.7f
        i++
    }
    drawCircle(
        Brush.sweepGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.12f), Color.Transparent, Color.Transparent, Color.White.copy(alpha = 0.07f), Color.Transparent, Color.Transparent),
            center = c,
        ),
        r, c,
    )
    drawCircle(Color(0xFF7A5238).copy(alpha = 0.7f), r, c, style = Stroke(1.5f))
}

/**
 * A spool: white plastic shaded to the lower right, a drive hole with six teeth round its edge,
 * and the clip that holds the leader, all turning together.
 */
private fun DrawScope.drawSpool(c: Offset, r: Float, angle: Float) {
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFAFAF8), Color(0xFFDCDDDA), Color(0xFFB4B6B3)), center = c - Offset(r * 0.3f, r * 0.3f), radius = r * 1.5f),
        r, c,
    )
    drawCircle(Color.Black.copy(alpha = 0.35f), r, c, style = Stroke(r * 0.06f))
    val hole = r * 0.62f
    drawCircle(Color(0xFF0D0D0F), hole, c)
    rotate(angle, c) {
        repeat(6) { i ->
            rotate(i * 60f, c) {
                drawRoundRect(
                    Brush.horizontalGradient(listOf(Color(0xFFF0F0EE), Color(0xFFC9CBC8)), startX = c.x - r * 0.09f, endX = c.x + r * 0.09f),
                    Offset(c.x - r * 0.09f, c.y - hole - r * 0.02f), Size(r * 0.18f, r * 0.3f), CornerRadius(r * 0.04f),
                )
            }
        }
        drawRect(Color(0xFF9EA19E), Offset(c.x + r * 0.7f, c.y - r * 0.08f), Size(r * 0.26f, r * 0.16f))
    }
    drawCircle(Color.Black.copy(alpha = 0.45f), hole, c, style = Stroke(r * 0.05f))
}

/**
 * On top: the window's clear plastic (a raised edge, glare and a streak of reflected light), the
 * foot along the bottom with its openings, the tape running across them and the felt pad, and the
 * five screws.
 */
private fun DrawScope.drawGlassAndFoot() {
    val w = size.width
    val h = size.height
    val win = windowRect()
    val cr = CornerRadius(win.height * 0.12f)
    drawRoundRect(
        Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.17f), Color.White.copy(alpha = 0.04f), Color.Transparent, Color.White.copy(alpha = 0.05f)),
            start = win.topLeft, end = Offset(win.left + win.width * 0.6f, win.bottom + win.height * 0.4f),
        ),
        win.topLeft, win.size, cr,
    )
    clipPath(windowPath()) {
        rotate(-28f, win.center) {
            val x = win.center.x - win.width * 0.16f
            drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.13f), Color.Transparent), startX = x, endX = x + win.width * 0.09f),
                Offset(x, win.top - win.height), Size(win.width * 0.09f, win.height * 3),
            )
        }
    }
    drawRoundRect(Color.White.copy(alpha = 0.32f), win.topLeft, win.size, cr, style = Stroke(h * 0.006f))
    drawRoundRect(
        Color.Black.copy(alpha = 0.6f), win.topLeft - Offset(h * 0.005f, h * 0.005f),
        Size(win.width + h * 0.01f, win.height + h * 0.01f), cr, style = Stroke(h * 0.004f),
    )

    // The foot: the raised trapezoid where the heads go in.
    val footT = h * 0.82f
    val foot = Path().apply {
        moveTo(w * 0.19f, h)
        lineTo(w * 0.235f, footT)
        lineTo(w * 0.765f, footT)
        lineTo(w * 0.81f, h)
        close()
    }
    drawPath(foot, Brush.verticalGradient(listOf(Color(0xFF36393F), Color(0xFF232529)), startY = footT, endY = h))
    drawLine(Color.White.copy(alpha = 0.2f), Offset(w * 0.235f, footT), Offset(w * 0.765f, footT), h * 0.006f)
    drawLine(Color.Black.copy(alpha = 0.55f), Offset(w * 0.19f, h), Offset(w * 0.235f, footT), h * 0.006f)
    drawLine(Color.Black.copy(alpha = 0.55f), Offset(w * 0.81f, h), Offset(w * 0.765f, footT), h * 0.006f)
    // The openings along the bottom edge, the tape running across behind them.
    val openT = h * 0.905f
    fun opening(x: Float, ow: Float) {
        val left = w * x - w * ow / 2
        drawRect(Color(0xFF070708), Offset(left, openT), Size(w * ow, h - openT))
        drawRect(Brush.verticalGradient(listOf(Color(0xFF5A3926), Color(0xFF3A2416)), startY = h * 0.955f, endY = h * 0.977f), Offset(left, h * 0.955f), Size(w * ow, h * 0.022f))
        drawRect(Color.Black.copy(alpha = 0.6f), Offset(left, openT), Size(w * ow, h * 0.01f))
    }
    opening(0.27f, 0.05f)
    opening(0.73f, 0.05f)
    opening(0.5f, 0.085f)
    for (x in listOf(0.35f, 0.65f)) {
        drawCircle(Color(0xFF070708), h * 0.03f, Offset(w * x, h * 0.94f))
        drawCircle(Color.White.copy(alpha = 0.09f), h * 0.03f, Offset(w * x, h * 0.94f), style = Stroke(h * 0.004f))
    }
    for (x in listOf(0.41f, 0.59f)) drawRect(Color(0xFF070708), Offset(w * x - w * 0.008f, h * 0.93f), Size(w * 0.016f, h * 0.07f))
    // The pressure pad: felt on a small metal spring, pressing the tape in the head's opening.
    drawRect(Color(0xFF8C8F93), Offset(w * 0.475f, h * 0.925f), Size(w * 0.05f, h * 0.012f))
    drawRect(Color(0xFFD9D4C8), Offset(w * 0.482f, h * 0.937f), Size(w * 0.036f, h * 0.018f))
    listOf(
        Offset(w * 0.035f, h * 0.06f), Offset(w * 0.965f, h * 0.06f), Offset(w * 0.035f, h * 0.94f), Offset(w * 0.965f, h * 0.94f), Offset(w * 0.5f, h * 0.865f),
    ).forEach { drawScrew(it, h * 0.024f) }
}

/** A Phillips screw sunk in the shell: a shadowed recess, a domed metal head, the cross. */
private fun DrawScope.drawScrew(c: Offset, r: Float) {
    drawCircle(Color.Black.copy(alpha = 0.55f), r * 1.18f, c + Offset(r * 0.1f, r * 0.14f))
    drawCircle(Brush.radialGradient(listOf(Color(0xFFEDEFF1), Color(0xFFA9AEB4), Color(0xFF666B72)), center = c - Offset(r * 0.35f, r * 0.35f), radius = r * 1.6f), r, c)
    val k = r * 0.62f
    val s = r * 0.22f
    drawLine(Color(0xFF34383E), c - Offset(k, 0f), c + Offset(k, 0f), s)
    drawLine(Color(0xFF34383E), c - Offset(0f, k), c + Offset(0f, k), s)
    drawLine(Color.White.copy(alpha = 0.3f), c - Offset(k, -s * 0.7f), c + Offset(-s * 0.6f, s * 0.7f), r * 0.07f)
}

