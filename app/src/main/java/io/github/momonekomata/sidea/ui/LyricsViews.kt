package io.github.momonekomata.sidea.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.momonekomata.sidea.lyrics.Lyrics

/**
 * Lyrics on a strip of the J-card's paper. Synced lyrics keep the sung line centred and lit;
 * unsynced ones just scroll. Tapping it opens the full view.
 */
@Composable
fun LyricsPanel(lyrics: Lyrics, positionMs: Long, modifier: Modifier = Modifier, compact: Boolean, onClick: () -> Unit) {
    val current = lyrics.lineAt(positionMs)
    val list = rememberLazyListState()
    LaunchedEffect(current, compact) {
        // Keep the current line in the middle: scroll so it sits one line below the top in the strip.
        if (current >= 0) list.animateScrollToItem((current - if (compact) 1 else 2).coerceAtLeast(0))
    }
    Column(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Retro.Cream)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        LazyColumn(state = list, modifier = Modifier.fillMaxWidth().weight(1f, fill = !compact), userScrollEnabled = !lyrics.synced) {
            itemsIndexed(lyrics.lines) { i, line -> LyricText(line.text, i == current, compact) }
        }
        Text(
            "lyrics · ${lyrics.source}",
            color = Retro.InkFaint,
            fontFamily = Retro.Mono,
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.End).padding(end = 12.dp, top = 2.dp),
        )
    }
}

@Composable
private fun LyricText(text: String, lit: Boolean, compact: Boolean) {
    val color by animateColorAsState(if (lit) Retro.Orange else Retro.Ink.copy(alpha = 0.55f), label = "lyric")
    Text(
        text.ifBlank { "♪" },
        color = color,
        fontSize = if (compact) 14.sp else 17.sp,
        fontWeight = if (lit) FontWeight.Bold else FontWeight.Normal,
        textAlign = TextAlign.Center,
        maxLines = if (compact) 1 else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = if (compact) 2.dp else 5.dp),
    )
}

/** The whole lyric sheet in place of the track list, like the back of the J-card. */
@Composable
fun LyricsCard(title: String, lyrics: Lyrics, positionMs: Long, modifier: Modifier, onClose: () -> Unit) {
    val current = lyrics.lineAt(positionMs)
    val list = rememberLazyListState()
    LaunchedEffect(current) { if (current >= 0) list.animateScrollToItem((current - 3).coerceAtLeast(0)) }
    JCardFrame(title, "lyrics · ${lyrics.source}", modifier) {
        JCardRow("‹", "Back to the track list", onClick = onClose)
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(lyrics.lines) { i, line -> LyricText(line.text, i == current, compact = false) }
        }
    }
}
