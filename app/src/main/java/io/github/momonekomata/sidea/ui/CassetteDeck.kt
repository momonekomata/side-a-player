package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val ASPECT = 1.58f // a compact cassette is 100.4 × 63.8 mm

/**
 * A compact cassette whose reels turn while playing. Tape moves from the left pack to the
 * right one as the song progresses, and each reel spins faster the less tape it holds, as a
 * real capstan-driven tape does.
 */
@Composable
fun Cassette(
    title: String,
    subtitle: String,
    progress: Float,
    playing: Boolean,
    modifier: Modifier = Modifier,
    art: ImageBitmap? = null,
) {
    val p by rememberUpdatedState(progress.coerceIn(0f, 1f))
    var leftAngle by remember { mutableFloatStateOf(0f) }
    var rightAngle by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val dt = (now - last) / 1e9f
                last = now
                // Constant tape speed: angular speed ∝ 1 / pack radius.
                val (rl, rr) = packRadii(p)
                leftAngle = (leftAngle + dt * 90f / rl) % 360f
                rightAngle = (rightAngle + dt * 90f / rr) % 360f
            }
        }
    }

    Box(modifier.fillMaxWidth().aspectRatio(ASPECT)) {
        Canvas(Modifier.matchParentSize()) { drawCassette(p, leftAngle, rightAngle) }
        // Label text sits on the cream strip at the top of the shell.
        // A small cover stuck on the label, like a hand-made mixtape's.
        if (art != null) {
            Image(
                art,
                contentDescription = "Cover",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(start = 32.dp, top = 18.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(3.dp)),
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = if (art != null) 86.dp else 34.dp, end = 34.dp, top = 18.dp),
        ) {
            Text(
                title.ifBlank { "— no tape —" },
                color = Retro.Ink,
                fontFamily = Retro.Hand,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                color = Retro.InkFaint,
                fontFamily = Retro.Mono,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Pack radii as a fraction of the maximum, from 0.35 (hub only) to 1 (full). */
private fun packRadii(progress: Float): Pair<Float, Float> {
    val hub = 0.35f
    // Area, not radius, moves linearly with tape length.
    fun r(fill: Float) = sqrt(hub * hub + (1 - hub * hub) * fill)
    return r(1 - progress) to r(progress)
}

private fun DrawScope.drawCassette(progress: Float, leftAngle: Float, rightAngle: Float) {
    val w = size.width
    val h = size.height
    val corner = CornerRadius(w * 0.03f)

    // Shell and its edge.
    drawRoundRect(Retro.Shell, cornerRadius = corner)
    drawRoundRect(Retro.ShellEdge, cornerRadius = corner, style = Stroke(width = w * 0.006f))

    // Screws.
    val screwR = w * 0.012f
    listOf(Offset(w * 0.035f, h * 0.055f), Offset(w * 0.965f, h * 0.055f), Offset(w * 0.035f, h * 0.945f), Offset(w * 0.965f, h * 0.945f), Offset(w * 0.5f, h * 0.9f))
        .forEach { c ->
            drawCircle(Color(0xFF555555), screwR, c)
            drawLine(Color(0xFF222222), c - Offset(screwR * 0.7f, 0f), c + Offset(screwR * 0.7f, 0f), strokeWidth = screwR * 0.35f)
        }

    // Cream label with the orange stripe.
    val labelTop = h * 0.08f
    val labelLeft = w * 0.07f
    val labelW = w * 0.86f
    val labelH = h * 0.62f
    drawRoundRect(Retro.Cream, Offset(labelLeft, labelTop), Size(labelW, labelH), CornerRadius(w * 0.015f))
    drawRect(Retro.Orange, Offset(labelLeft, labelTop + labelH * 0.42f), Size(labelW, labelH * 0.05f))
    drawRect(Retro.Orange.copy(alpha = 0.55f), Offset(labelLeft, labelTop + labelH * 0.49f), Size(labelW, labelH * 0.02f))
    // "A" side badge.
    drawRoundRect(Retro.Orange, Offset(labelLeft + labelW * 0.9f, labelTop + labelH * 0.06f), Size(labelW * 0.07f, labelH * 0.16f), CornerRadius(4f))

    // Tape window.
    val winTop = labelTop + labelH * 0.56f
    val winH = labelH * 0.38f
    val winLeft = w * 0.24f
    val winW = w * 0.52f
    val window = Path().apply {
        addRoundRect(RoundRect(winLeft, winTop, winLeft + winW, winTop + winH, CornerRadius(winH * 0.5f)))
    }
    drawPath(window, Color(0xFF1B1B1B))

    val cy = winTop + winH / 2
    val maxPack = w * 0.16f // two full packs would just touch
    val leftC = Offset(w * 0.33f, cy)
    val rightC = Offset(w * 0.67f, cy)
    val (rl, rr) = packRadii(progress)

    // The packs are bigger than the window; only the part behind it shows.
    clipPath(window) {
        drawCircle(Retro.Tape, maxPack * rl, leftC)
        drawCircle(Retro.Tape, maxPack * rr, rightC)
    }

    drawReel(leftC, winH * 0.32f, leftAngle)
    drawReel(rightC, winH * 0.32f, rightAngle)

    // Trapezoid at the bottom where the head meets the tape.
    val trap = Path().apply {
        moveTo(w * 0.2f, h)
        lineTo(w * 0.27f, h * 0.8f)
        lineTo(w * 0.73f, h * 0.8f)
        lineTo(w * 0.8f, h)
        close()
    }
    drawPath(trap, Color(0xFF1E1E1E))
    listOf(0.33f, 0.45f, 0.55f, 0.67f).forEach { x ->
        drawCircle(Color(0xFF0A0A0A), w * 0.014f, Offset(w * x, h * 0.9f))
    }
}

private fun DrawScope.drawReel(center: Offset, radius: Float, angle: Float) {
    drawCircle(Color(0xFFEFEFEF), radius, center)
    drawCircle(Color(0xFFBDBDBD), radius, center, style = Stroke(width = radius * 0.08f))
    rotate(angle, pivot = center) {
        // Six drive teeth around the spindle hole.
        repeat(6) { i ->
            val a = (i * 60.0) * PI / 180
            val inner = Offset(center.x + (radius * 0.38f * cos(a)).toFloat(), center.y + (radius * 0.38f * sin(a)).toFloat())
            val outer = Offset(center.x + (radius * 0.62f * cos(a)).toFloat(), center.y + (radius * 0.62f * sin(a)).toFloat())
            drawLine(Color(0xFF9E9E9E), inner, outer, strokeWidth = radius * 0.16f)
        }
        // A colored mark so the motion reads even when it's slow.
        drawCircle(Retro.Orange, radius * 0.1f, Offset(center.x + radius * 0.8f, center.y))
    }
    drawCircle(Color(0xFF2A2A2A), radius * 0.36f, center)
}
