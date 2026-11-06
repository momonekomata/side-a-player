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
 * The classic portable cassette player: an anodised blue body, a brushed-aluminium faceplate,
 * silver piano keys with printed labels, an orange button, and the cream paper insert of the tape.
 */
object Retro {
    /** The body. */
    val Background = Color(0xFF1B2D52)
    /** Dialogs and the EQ: the body's darker underside. */
    val Panel = Color(0xFF14213D)
    val Shell = Color(0xFF223763)
    val ShellEdge = Color(0xFF3A5285)
    val Cream = Color(0xFFF1E6D2)
    val CreamShade = Color(0xFFE2D4BC)
    val Ink = Color(0xFF3B2F27)
    val InkFaint = Color(0xFF8A7A6B)
    val Orange = Color(0xFFE8742A)
    val Tape = Color(0xFF4A3426)
    val Led = Color(0xFF7CF08A)
    val Amber = Color(0xFFFFB547)
    /** Secondary text and icons on the blue body. */
    val Muted = Color(0xFFA3B5D6)

    /** The faceplate: brushed aluminium, and what's printed on it. */
    val MetalLight = Color(0xFFDDE1E6)
    val MetalMid = Color(0xFFBFC5CC)
    val MetalDark = Color(0xFFA6ADB6)
    val MetalEdge = Color(0xFF7D858F)
    val OnMetal = Color(0xFF1C222C)
    val OnMetalMuted = Color(0xFF4F5866)
    /** The little dark window the quality light sits in. */
    val Lcd = Color(0xFF10151C)

    /** Typewriter for the deck's printed text; cursive stands in for the handwritten J-card. */
    val Mono = FontFamily.Monospace
    val Hand = FontFamily.Cursive
}

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
            onSurface = Color(0xFFEDF1F7),
            onSurfaceVariant = Retro.Muted,
        ),
        content = content,
    )
}
