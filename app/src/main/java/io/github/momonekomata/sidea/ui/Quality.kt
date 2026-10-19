package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.momonekomata.sidea.audio.Quality
import io.github.momonekomata.sidea.audio.SignalPath

fun Quality.color(): Color = when (this) {
    Quality.LOSSLESS -> Color(0xFFB48CFF)
    Quality.ENHANCED -> Color(0xFF6FB8FF)
    Quality.HIGH_QUALITY -> Retro.Led
    Quality.LOSSY -> Retro.Amber
}

private fun Quality.meaning() = when (this) {
    Quality.LOSSLESS -> "Every sample reaches the DAC exactly as it is in the file."
    Quality.ENHANCED -> "Lossless source, deliberately changed by your EQ."
    Quality.HIGH_QUALITY -> "Lossless source, but something on the way resamples or mixes it."
    Quality.LOSSY -> "Part of the path throws information away (a compressed file, or Bluetooth)."
}

/** A lit pill like Roon's signal-path light; tap for the whole path. */
@Composable
fun QualityBadge(q: Quality, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, q.color(), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(q.color()))
        Text(q.label, color = q.color(), fontFamily = Retro.Mono, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
fun SignalPathDialog(path: SignalPath, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Signal path") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    QualityBadge(path.quality) {}
                }
                Text(path.quality.meaning(), fontSize = 13.sp, color = Retro.Muted)
                path.stages.forEach { stage ->
                    Row(verticalAlignment = Alignment.Top) {
                        Box(Modifier.padding(top = 5.dp).size(9.dp).clip(CircleShape).background(stage.quality.color()))
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(stage.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(stage.detail, fontSize = 12.sp, color = Retro.Muted)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}
