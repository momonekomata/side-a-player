package io.github.momonekomata.sidea.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

object Retro {
    val Background = Color(0xFF0D0D0D)
    val Panel = Color(0xFF171717)
    val Shell = Color(0xFF262626)
    val ShellEdge = Color(0xFF3A3A3A)
    val Cream = Color(0xFFF1E6D2)
    val CreamShade = Color(0xFFE2D4BC)
    val Ink = Color(0xFF3B2F27)
    val InkFaint = Color(0xFF8A7A6B)
    val Orange = Color(0xFFE8742A)
    val Tape = Color(0xFF4A3426)
    val Led = Color(0xFF7CF08A)
    val Amber = Color(0xFFFFB547)
    val Muted = Color(0xFF8C8C8C)

    /** Typewriter for the deck's printed text; cursive stands in for the handwritten J-card. */
    val Mono = FontFamily.Monospace
    val Hand = FontFamily.Cursive
}

@Composable
fun SideATheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Retro.Orange,
            onPrimary = Color.Black,
            background = Retro.Background,
            surface = Retro.Panel,
            onSurface = Color(0xFFEDEDED),
        ),
        content = content,
    )
}
