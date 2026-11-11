package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.text.drawText
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.momonekomata.sidea.audio.RepeatMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Stereo theme's tape page: the front of a blue stereo cassette player. WALKDECK and
 * "STEREO CASSETTE PLAYER" on the lid, a groove where the lid meets the body, a big outlined
 * STEREO, the tall window with the tape turning on its spindles (the song on the cassette's
 * label between them), the outlined arrow, and the silver side with the keys down it and a small
 * round button for the heart. Play is the orange button on the top edge, pressed in while the
 * tape runs. Upright it fills the page; on its side it stands in the middle, as tall as fits.
 */
/** How far the keys' symbols are turned: back upright when the player lies on its side. */
private val LocalIconTurn = androidx.compose.runtime.compositionLocalOf { 0f }

@Composable
fun StereoDeck(model: DeckModel, modifier: Modifier = Modifier, iconTurn: Float = 0f) = androidx.compose.runtime.CompositionLocalProvider(LocalIconTurn provides iconTurn) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val m = if (maxWidth / maxHeight > 0.72f) Modifier.fillMaxHeight().aspectRatio(0.66f) else Modifier.fillMaxSize()
        StereoBody(model, m)
    }
}

/**
 * The Stereo theme's full screen (a phone turned on its side opens on it): the player standing
 * at the screen's full height, and beside it the song and the line being sung. Upright, just the
 * player. A key closes it.
 */
@Composable
fun StereoFullScreen(model: DeckModel, closeLabel: String, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().anodizedBlue()) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(12.dp)) {
            if (maxWidth > maxHeight) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                    StereoBody(model, Modifier.fillMaxHeight().aspectRatio(0.66f))
                    Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 12.dp)) {
                        Text(
                            model.title.ifBlank { "Nothing playing" }, color = Retro.OnShell, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        if (model.subtitle.isNotBlank()) {
                            Text(model.subtitle, color = Retro.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        // The whole lyrics straight on the body, scrolling with the song.
                        LyricsFlow(model, Modifier.weight(1f).fillMaxWidth().padding(bottom = 10.dp))
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) { SmallLabelKey(closeLabel, closeLabel, onClose) }
                    }
                }
            } else {
                StereoBody(model, Modifier.fillMaxSize().padding(bottom = 44.dp))
                Box(Modifier.align(Alignment.BottomEnd)) { SmallLabelKey(closeLabel, closeLabel, onClose) }
            }
        }
    }
}

@Composable
private fun StereoBody(model: DeckModel, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val w = maxWidth
        val h = maxHeight
        val stripW = w * 0.17f
        val shape = RoundedCornerShape(10.dp)
        Row(
            Modifier
                .fillMaxSize()
                .shadow(8.dp, shape)
                .clip(shape),
        ) {
            // The body's left side, turning away into shadow.
            Box(Modifier.width(7.dp).fillMaxHeight().background(Brush.horizontalGradient(listOf(Color(0xFF0E1D45), Color(0xFF1B3672), Color(0xFF2A55A4)))))
            BluePanel(model, Modifier.weight(1f).fillMaxHeight(), w - stripW - 7.dp, h)
            SilverSide(model, Modifier.width(stripW).fillMaxHeight(), stripW)
        }
    }
}

/** The blue front: the lid with the name, the groove, STEREO, the window and the arrow. */
@Composable
private fun BluePanel(model: DeckModel, modifier: Modifier, w: Dp, h: Dp) {
    val white = Color(0xFFF2F5FA)
    Column(modifier.stereoBlue()) {
        // The lid.
        Column(Modifier.fillMaxWidth().height(h * 0.15f).padding(start = w * 0.08f), verticalArrangement = Arrangement.Center) {
            Text(
                "WALKDECK", color = white, fontFamily = Retro.Wordmark,
                fontSize = (w.value * 0.1f).sp, maxLines = 1,
            )
            Text(
                "STEREO CASSETTE PLAYER", color = white, fontWeight = FontWeight.Medium,
                fontSize = (w.value * 0.042f).sp, letterSpacing = (w.value * 0.001f).sp, maxLines = 1,
            )
        }
        // The groove where the lid meets the body: a dark line over a lit one.
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color(0xFF14295A)))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.22f)))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().padding(start = w * 0.17f), verticalArrangement = Arrangement.Top) {
                Spacer(Modifier.height(h * 0.05f))
                // STEREO in outline, as it's printed on the body, worn a little.
                WeatheredStereo(Modifier.offset(x = -(w * 0.04f)), (w.value * 0.15f).sp, w.value * 0.007f, white)
                Spacer(Modifier.height(h * 0.035f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TapeWindowStereo(model, Modifier.width(w * 0.4f).height(h * 0.52f))
                    Spacer(Modifier.width(w * 0.12f))
                    OutlineArrow(Modifier.width(w * 0.11f).height(h * 0.13f), white)
                }
            }
        }
    }
}

/** The face for STEREO: Harry Plain, the face the original is printed in (the user's copy). */
private val HarryFace = FontFamily(androidx.compose.ui.text.font.Font(io.github.momonekomata.sidea.R.font.harry_plain))

/**
 * STEREO, printed in outline in a rounded face, and weathered: small flecks and a couple of fine
 * scratches worn out of the print, and a faint unevenness over it, as years in a pocket leave it.
 * The wear is a fixed pattern, the same every time.
 */
@Composable
private fun WeatheredStereo(modifier: Modifier, size: androidx.compose.ui.unit.TextUnit, stroke: Float, color: Color) {
    // The face's letters are built from overlapping strokes, so outlining them as they are leaves
    // seams inside the E and R. Instead: a thick outline, with the filled letters cut out of it,
    // which leaves just the outer edge, clean.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    // Harry is set tight already, as the original is.
    val style = TextStyle(fontFamily = HarryFace, fontSize = size)
    val layout = remember(size) { measurer.measure("STEREO", style) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val pad = stroke * 2
    Canvas(
        modifier
            .size(with(density) { (layout.size.width + pad * 2).toDp() }, with(density) { (layout.size.height + pad * 2).toDp() })
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
            .drawWithCache {
                val r = java.util.Random(19)
                val w = this.size.width
                val h = this.size.height
                // Flecks where the paint has come away, of a few sizes.
                val flecks = List(60) { Triple(r.nextFloat() * w, r.nextFloat() * h, (0.4f + r.nextFloat() * 0.9f) * stroke) }
                // A couple of long, fine scratches across the lettering.
                val scratches = List(2) { Offset(r.nextFloat() * w * 0.5f, r.nextFloat() * h) to Offset(w * (0.5f + r.nextFloat() * 0.5f), r.nextFloat() * h) }
                onDrawWithContent {
                    drawContent()
                    flecks.forEach { (x, y, rad) -> drawCircle(Color.Black, rad, Offset(x, y), blendMode = androidx.compose.ui.graphics.BlendMode.DstOut) }
                    scratches.forEach { (a, b) -> drawLine(Color.Black.copy(alpha = 0.45f), a, b, strokeWidth = stroke * 0.35f, blendMode = androidx.compose.ui.graphics.BlendMode.DstOut) }
                    // A faint unevenness over the whole print.
                    drawRect(
                        Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.0f), Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.05f), Color.Black.copy(alpha = 0.2f))),
                        blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
                    )
                }
            },
    ) {
        // The outline: the filled letters stamped in a ring around their place (which swells them
        // by the outline's width), then the letters themselves cut out of the middle.
        val at = Offset(pad, pad)
        val band = stroke * 1.8f
        repeat(24) { i ->
            val a = i * (2 * PI / 24)
            drawText(layout, color, topLeft = at + Offset((band * cos(a)).toFloat(), (band * sin(a)).toFloat()))
        }
        drawText(layout, Color.Black, topLeft = at, blendMode = androidx.compose.ui.graphics.BlendMode.DstOut)
    }
}

/** An arrow pointing up, drawn in outline. */
@Composable
private fun OutlineArrow(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val head = h * 0.38f
        val shaft = w * 0.18f
        val p = Path().apply {
            moveTo(w / 2, 0f)
            lineTo(w, head)
            lineTo(w / 2 + shaft, head)
            lineTo(w / 2 + shaft, h)
            lineTo(w / 2 - shaft, h)
            lineTo(w / 2 - shaft, head)
            lineTo(0f, head)
            close()
        }
        drawPath(p, color, style = Stroke(width = 1.8.dp.toPx()))
    }
}

/**
 * The window: dark, under glass. Through it, the two spindles (chrome rings round black hubs,
 * turning) with the brown tape packs on them, top and bottom; between them the cassette's label,
 * with the song, the artist and the time on it.
 */
@Composable
private fun TapeWindowStereo(model: DeckModel, modifier: Modifier) {
    val progress = if (model.durationMs > 0) (model.positionMs.toFloat() / model.durationMs).coerceIn(0f, 1f) else 0f
    val supply = stereoPack(1f - progress)
    val takeup = stereoPack(progress)
    var supplyAngle by remember { mutableFloatStateOf(0f) }
    var takeupAngle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(model.playing, supply, takeup) {
        if (!model.playing) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1e9f
                    supplyAngle = (supplyAngle - 30f / supply * dt) % 360f
                    takeupAngle = (takeupAngle - 30f / takeup * dt) % 360f
                }
                last = now
            }
        }
    }
    val frame = RoundedCornerShape(6.dp)
    Box(
        modifier
            .shadow(2.dp, frame)
            .clip(frame)
            .background(Color(0xFF05070C))
            .border(2.dp, Color(0xFF0B1630), frame)
            .padding(4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF0A0D14)),
    ) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { drawSpindles(supply, takeup, supplyAngle, takeupAngle) }
        // The cassette's label between the spindles.
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val lw = maxWidth
            Column(
                Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.84f)
                    .fillMaxHeight(0.28f)
                    .clip(RoundedCornerShape(3.dp))
                    .brushedMetal()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    model.title.ifBlank { "Nothing playing" }, color = Retro.OnMetal, fontWeight = FontWeight.Bold,
                    fontSize = (lw.value * 0.085f).coerceIn(9f, 14f).sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = (lw.value * 0.1f).coerceIn(11f, 17f).sp,
                )
                if (model.subtitle.isNotBlank()) {
                    Text(model.subtitle, color = Retro.OnMetalMuted, fontSize = (lw.value * 0.065f).coerceIn(8f, 11f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    formatTime(model.positionMs) + " / " + (if (model.durationMs > 0) formatTime(model.durationMs) else "--:--"),
                    color = Retro.OnMetalMuted, fontFamily = Retro.Mono, fontSize = (lw.value * 0.065f).coerceIn(8f, 11f).sp, maxLines = 1,
                )
            }
        }
        // The glass: a little smoke, and the light across it.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0x22101826))
            drawRect(
                Brush.linearGradient(
                    0f to Color.White.copy(alpha = 0.14f), 0.3f to Color.White.copy(alpha = 0.04f), 0.45f to Color.Transparent, 1f to Color.Transparent,
                    start = Offset(0f, 0f), end = Offset(size.width, size.height * 0.7f),
                ),
            )
        }
    }
}

/** A pack's radius, as a share of the window's width: from the bare hub to a full pack. */
private fun stereoPack(share: Float): Float {
    val empty = 0.2f
    val full = 0.62f
    return sqrt(empty * empty + (full * full - empty * empty) * share.coerceIn(0f, 1f))
}

private fun DrawScope.drawSpindles(supply: Float, takeup: Float, supplyAngle: Float, takeupAngle: Float) {
    val w = size.width
    val h = size.height
    val topC = Offset(w / 2, h * 0.19f)
    val botC = Offset(w / 2, h * 0.81f)
    clipRect {
        drawStereoPack(topC, supply * w)
        drawStereoPack(botC, takeup * w)
    }
    drawSpindle(topC, w * 0.17f, supplyAngle)
    drawSpindle(botC, w * 0.17f, takeupAngle)
}

private fun DrawScope.drawStereoPack(c: Offset, r: Float) {
    drawCircle(Brush.radialGradient(listOf(Color(0xFF5A3A26), Color(0xFF3A2416), Color(0xFF22150C)), center = c, radius = r), r, c)
    var rr = r * 0.97f
    var i = 0
    while (rr > size.width * 0.19f) {
        drawCircle(if (i % 3 == 0) Color.Black.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.04f), rr, c, style = Stroke(1f))
        rr -= r * 0.035f + (i % 4) * 0.6f
        i++
    }
    drawCircle(Color(0xFF7A5238).copy(alpha = 0.6f), r, c, style = Stroke(1.2f))
}

/** A spindle: a chrome ring round a black hub with its drive teeth, a chrome cap in the middle. */
private fun DrawScope.drawSpindle(c: Offset, r: Float, angle: Float) {
    drawCircle(Color.Black.copy(alpha = 0.5f), r * 1.08f, c + Offset(r * 0.06f, r * 0.08f))
    drawCircle(
        Brush.sweepGradient(listOf(Color(0xFFE9ECEF), Color(0xFF8D9399), Color(0xFFF5F6F7), Color(0xFF7C838A), Color(0xFFE9ECEF)), center = c),
        r, c,
    )
    drawCircle(Color(0xFF0C0D10), r * 0.72f, c)
    rotate(angle, c) {
        repeat(6) { i ->
            val a = i * PI / 3
            val p = Offset(c.x + (r * 0.5f * cos(a)).toFloat(), c.y + (r * 0.5f * sin(a)).toFloat())
            drawCircle(Color(0xFF3A3D42), r * 0.1f, p)
        }
    }
    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF9AA0A6)), center = c - Offset(r * 0.08f, r * 0.08f), radius = r * 0.3f), r * 0.24f, c)
    drawCircle(Color.White.copy(alpha = 0.5f), r * 0.98f, c, style = Stroke(r * 0.05f))
}

/**
 * The silver side: brushed aluminium, the keys down it (previous, back 10, forward 10, next,
 * shuffle, repeat) and the small round button, which is the heart.
 */
@Composable
private fun SilverSide(model: DeckModel, modifier: Modifier, w: Dp) {
    val k = minOf(w - 14.dp, 46.dp)
    Column(
        modifier.brushedMetal().border(0.5.dp, Retro.MetalEdge).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        model.shuffle?.let { on -> SideKey(Icons.Filled.Shuffle, "Shuffle", k, led = on, onClick = model.onShuffle) }
        SideKey(Icons.Filled.SkipPrevious, "Previous", k, onHold = { model.onSkip(-5_000) }, onClick = model.onPrevious)
        WindKey(-10_000, k, model.onSkip, turn = LocalIconTurn.current)
        // PLAY, in the row with the others at the middle: the orange key, held down while playing.
        SquareKey(k * 1.12f, orange = true, latched = model.playing, onClick = model.onToggle) { _ ->
            Icon(if (model.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", tint = Color.White, modifier = Modifier.size(k * 0.55f).rotate(LocalIconTurn.current))
        }
        WindKey(10_000, k, model.onSkip, turn = LocalIconTurn.current)
        SideKey(Icons.Filled.SkipNext, "Next", k, onHold = { model.onSkip(5_000) }, onClick = model.onNext)
        model.repeat?.let { r ->
            SideKey(if (r == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat", k, led = r != RepeatMode.OFF, onClick = model.onRepeat)
        }
        // The small round button on the side: the heart.
        model.favorite?.let { fav ->
            Box(
                Modifier
                    .size(k * 0.62f)
                    .shadow(2.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Brush.radialGradient(listOf(Color(0xFFF4F5F6), Color(0xFFAEB4BA))))
                    .border(1.dp, Retro.MetalEdge, CircleShape)
                    .clickable(onClick = model.onFavorite),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", tint = if (fav) Retro.Orange else Retro.OnMetalMuted, modifier = Modifier.size(k * 0.36f).rotate(LocalIconTurn.current))
            }
        }
    }
}

@Composable
private fun SideKey(icon: ImageVector, label: String, k: Dp, led: Boolean? = null, onHold: (() -> Unit)? = null, onClick: () -> Unit) {
    SquareKey(k, led = led, onHold = onHold, onClick = onClick) { _ ->
        Icon(icon, label, tint = Retro.OnMetal, modifier = Modifier.size(k * 0.46f).rotate(LocalIconTurn.current))
    }
}
