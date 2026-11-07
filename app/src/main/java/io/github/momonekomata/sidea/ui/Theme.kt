package io.github.momonekomata.sidea.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The first Walkman's colours: a metallic blue body, a brushed-aluminium faceplate and silver
 * keys with navy printing, and the orange hotline button. The Library is a panel of that
 * aluminium, printed in navy, rather than a paper tape insert.
 */
object Retro {
    /** The body: metallic Walkman blue. */
    val Background = Color(0xFF24446F)
    /** Dialogs and the EQ: the body's darker underside. */
    val Panel = Color(0xFF1A3256)
    val Shell = Color(0xFF2D5083)
    val ShellEdge = Color(0xFF4C6EA5)
    /** Text printed on the blue body. */
    val OnShell = Color(0xFFEEF2F8)
    /** The Library's panel: light brushed aluminium, and its darker grooves. */
    val Cream = Color(0xFFEDEFF2)
    val CreamShade = Color(0xFFD6DBE1)
    /** Navy printing on the aluminium. */
    val Ink = Color(0xFF16233A)
    val InkFaint = Color(0xFF5A6679)
    /** The hotline button. */
    val Orange = Color(0xFFEC6F1E)
    val Tape = Color(0xFF4A3426)
    val Led = Color(0xFF7CF08A)
    val Amber = Color(0xFFFFB547)
    /** Secondary text and icons on the blue body. */
    val Muted = Color(0xFFB2C3DE)

    /** The faceplate: brushed aluminium, and what's printed on it. */
    val MetalLight = Color(0xFFDDE1E6)
    val MetalMid = Color(0xFFBFC5CC)
    val MetalDark = Color(0xFFA6ADB6)
    val MetalEdge = Color(0xFF7D858F)
    val OnMetal = Color(0xFF1C222C)
    val OnMetalMuted = Color(0xFF4F5866)
    /** The little dark window the quality light sits in. */
    val Lcd = Color(0xFF10151C)

    /** Typewriter for the deck's printed text. */
    val Mono = FontFamily.Monospace
    /**
     * The lettering on a Walkman's lid: a heavy, slanted sans (set Black and Italic). The
     * Library's header and the WALKDECK name use it.
     */
    val Logo = FontFamily.SansSerif
}

/** "1 song", "2 songs". */
fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

/**
 * Fine horizontal brushing: two paths of hairlines, a little lighter and a little darker than the
 * metal under them, in a fixed pattern. Built once per size, drawn as two calls.
 */
private class Grain(val light: Path, val dark: Path)

private fun grain(w: Float, h: Float): Grain {
    val light = Path()
    val dark = Path()
    var y = 0.5f
    var i = 0
    while (y < h) {
        val p = if ((i * 7919) % 5 < 2) light else dark
        p.moveTo(0f, y)
        p.lineTo(w, y)
        y += 2f + (i * 31 % 3)
        i++
    }
    return Grain(light, dark)
}

private fun DrawScope.drawGrain(g: Grain, lightAlpha: Float, darkAlpha: Float) {
    drawPath(g.light, Color.White.copy(alpha = lightAlpha), style = Stroke(1f))
    drawPath(g.dark, Color.Black.copy(alpha = darkAlpha), style = Stroke(1f))
}

/** A soft band of light across the metal, from upper left to lower right. */
private fun sheen(w: Float, h: Float, alpha: Float) = Brush.linearGradient(
    0f to Color.Transparent,
    0.30f to Color.Transparent,
    0.42f to Color.White.copy(alpha = alpha),
    0.50f to Color.White.copy(alpha = alpha * 0.35f),
    0.62f to Color.Transparent,
    1f to Color.Transparent,
    start = Offset(0f, 0f),
    end = Offset(w, h * 0.75f),
)

/** Brushed aluminium: a soft top-to-bottom sheen, a band of light across it, and fine grain. */
fun Modifier.brushedMetal(): Modifier = this.drawWithCache {
    val base = Brush.verticalGradient(listOf(Retro.MetalLight, Retro.MetalMid, Retro.MetalLight.copy(alpha = 0.95f), Retro.MetalDark))
    val band = sheen(size.width, size.height, 0.32f)
    val g = grain(size.width, size.height)
    onDrawBehind {
        drawRect(base)
        drawGrain(g, 0.10f, 0.045f)
        drawRect(band)
    }
}

/**
 * Brushed metal anodised in [tint] (the cover's colour): the same sheen, band of light and grain
 * as the silver, in that colour. Null is the plain silver.
 */
fun Modifier.brushedMetal(tint: Color?): Modifier = if (tint == null) brushedMetal() else this.drawWithCache {
    val base = Brush.verticalGradient(
        listOf(androidx.compose.ui.graphics.lerp(tint, Color.White, 0.16f), tint, androidx.compose.ui.graphics.lerp(tint, Color.White, 0.07f), androidx.compose.ui.graphics.lerp(tint, Color.Black, 0.28f)),
    )
    val band = sheen(size.width, size.height, 0.14f)
    val g = grain(size.width, size.height)
    onDrawBehind {
        drawRect(base)
        drawGrain(g, 0.06f, 0.08f)
        drawRect(band)
    }
}

/**
 * The cover's colour, as metal would take it: the hue that most of the cover's colourful pixels
 * share (or its average, for a cover with hardly any colour), its saturation held in, and dark
 * enough for light printing to read on it.
 */
fun coverTint(art: ImageBitmap): Color? = try {
    var src = art.asAndroidBitmap()
    if (src.config == android.graphics.Bitmap.Config.HARDWARE) src = src.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
    val bmp = android.graphics.Bitmap.createScaledBitmap(src, 24, 24, true)
    val weight = FloatArray(12)
    val rs = FloatArray(12)
    val gs = FloatArray(12)
    val bs = FloatArray(12)
    var ar = 0f
    var ag = 0f
    var ab = 0f
    val hsv = FloatArray(3)
    for (y in 0 until 24) for (x in 0 until 24) {
        val c = bmp.getPixel(x, y)
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        ar += r; ag += g; ab += b
        android.graphics.Color.colorToHSV(c, hsv)
        if (hsv[2] < 0.15f) continue
        val wgt = hsv[1] * hsv[2]
        val bin = (hsv[0] / 30f).toInt().coerceIn(0, 11)
        weight[bin] += wgt
        rs[bin] += r * wgt; gs[bin] += g * wgt; bs[bin] += b * wgt
    }
    val best = weight.indices.maxBy { weight[it] }
    val base = if (weight.sum() < 576 * 0.06f || weight[best] <= 0f) {
        android.graphics.Color.rgb((ar / 576 * 255).toInt(), (ag / 576 * 255).toInt(), (ab / 576 * 255).toInt())
    } else {
        android.graphics.Color.rgb((rs[best] / weight[best] * 255).toInt(), (gs[best] / weight[best] * 255).toInt(), (bs[best] / weight[best] * 255).toInt())
    }
    val hsl = FloatArray(3)
    androidx.core.graphics.ColorUtils.colorToHSL(base, hsl)
    hsl[1] = hsl[1].coerceIn(0.08f, 0.5f)
    hsl[2] = 0.32f
    Color(androidx.core.graphics.ColorUtils.HSLToColor(hsl))
} catch (e: Exception) {
    null
}

/**
 * The body: anodised blue aluminium. Deeper towards the lower right, lit at the upper left, with
 * a band of light across it and the same fine brushing as the faceplate.
 */
fun Modifier.anodizedBlue(): Modifier = this.drawWithCache {
    val base = Brush.linearGradient(
        0f to Color(0xFF3563A3),
        0.35f to Retro.Background,
        0.75f to Color(0xFF1C3A63),
        1f to Color(0xFF244A80),
        start = Offset(0f, 0f),
        end = Offset(size.width, size.height),
    )
    val band = sheen(size.width, size.height, 0.10f)
    val g = grain(size.width, size.height)
    onDrawBehind {
        drawRect(base)
        drawGrain(g, 0.05f, 0.07f)
        drawRect(band)
    }
}

/** A key's face: light grey plastic, a touch lighter at the top. */
val KeyFace = Brush.verticalGradient(listOf(Color(0xFFF1F0EC), Color(0xFFDCDAD5), Color(0xFFC7C5C0)))
/** The one orange key. */
val KeyFaceLit = Brush.verticalGradient(listOf(Color(0xFFFF9C52), Color(0xFFF47A20), Color(0xFFD9620F)))
private val KeyRim = Color(0xFF8C8F94)
private val KeyRimLit = Color(0xFFB5520F)
/** Keys are square, their corners only just softened. */
val KeyShape = RoundedCornerShape(3.dp)

/**
 * A square key: its face, a bright edge along the top and a darker one along the bottom where its
 * side shows. [down] is pressed (or latched): the side hides and the face dims a little.
 */
fun Modifier.keyFace(orange: Boolean = false, down: Boolean = false, shape: Shape = KeyShape): Modifier = this
    .shadow(if (down) 0.dp else 2.dp, shape)
    .clip(shape)
    .background(if (orange) KeyFaceLit else KeyFace)
    .drawWithContent {
        drawContent()
        val px = 1.dp.toPx()
        if (down) {
            drawRect(Color.Black.copy(alpha = 0.12f))
        } else {
            drawRect(Color.White.copy(alpha = if (orange) 0.40f else 0.85f), size = Size(size.width, px))
            drawRect(Color.Black.copy(alpha = 0.18f), topLeft = Offset(0f, size.height - 2 * px), size = Size(size.width, 2 * px))
        }
    }
    .border(1.dp, if (orange) KeyRimLit else KeyRim, shape)

/** The recessed grey housing a row of keys sits in. */
@Composable
fun KeyStrip(modifier: Modifier = Modifier, gap: Dp = 5.dp, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.keyHousing(gap),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The same housing standing upright, its keys one above the other. */
@Composable
fun KeyStrip(modifier: Modifier = Modifier, vertical: Boolean, gap: Dp = 5.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.keyHousing(gap),
        verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

private fun Modifier.keyHousing(gap: Dp): Modifier {
    val shape = RoundedCornerShape(5.dp)
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color(0xFF8A8E94), Color(0xFFA7ABB0), Color(0xFFB4B7BB))))
        .border(1.dp, Color(0xFF6E7277), shape)
        .padding(gap)
}

/** The little light on a key that latches: orange when on. */
fun DrawScope.drawKeyLed(on: Boolean) {
    val r = 2.5.dp.toPx()
    drawCircle(if (on) Retro.Orange else Color(0xFF8E9196), r, Offset(size.width - r * 2.6f, r * 2.6f))
}

@Composable
fun SideATheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Retro.Orange,
            onPrimary = Color.Black,
            background = Retro.Background,
            surface = Retro.Panel,
            surfaceContainerHigh = Retro.Panel,
            onSurface = Retro.OnShell,
            onSurfaceVariant = Retro.Muted,
        ),
        content = content,
    )
}
