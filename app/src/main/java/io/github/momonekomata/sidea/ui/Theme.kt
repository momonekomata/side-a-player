package io.github.momonekomata.sidea.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.background
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

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

/** Brushed aluminium: a soft top-to-bottom sheen with fine horizontal grain. */
fun Modifier.brushedMetal(): Modifier = this
    .background(Brush.verticalGradient(listOf(Retro.MetalLight, Retro.MetalMid, Retro.MetalLight.copy(alpha = 0.95f), Retro.MetalDark)))
    .drawBehind {
        // The grain: thin lines a little lighter or darker than the plate, in a fixed pattern.
        var y = 0f
        var i = 0
        while (y < size.height) {
            val light = (i * 7919) % 5 < 2
            drawLine(
                if (light) Color.White.copy(alpha = 0.10f) else Color.Black.copy(alpha = 0.045f),
                Offset(0f, y), Offset(size.width, y), strokeWidth = 1f,
            )
            y += 2f + (i * 31 % 3)
            i++
        }
    }

/** A silver key's face: lighter at the top, like a rounded metal key catching the light. */
val KeyFace = Brush.verticalGradient(listOf(Color(0xFFF2F4F6), Color(0xFFCDD2D8), Color(0xFFB1B7BF)))
val KeyFaceLit = Brush.verticalGradient(listOf(Color(0xFFF59A5C), Retro.Orange, Color(0xFFC85A16)))

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
