package io.github.momonekomata.sidea.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import kotlinx.coroutines.delay
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.momonekomata.sidea.LyricsState
import io.github.momonekomata.sidea.Settings

/**
 * Lyrics where the cover was. Timed lyrics keep the sung line lit and a third of the way down;
 * untimed ones just scroll. With none, it says why and offers Retry.
 */
@Composable
fun LyricsSquare(state: LyricsState, positionMs: Long, playing: Boolean, onRetry: () -> Unit, onSeek: (Long) -> Unit, modifier: Modifier) {
    val lyrics = state.lyrics
    Box(modifier.background(Color(0xFF151515))) {
        if (lyrics == null) {
            Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val text = when {
                    state.key == null -> "Nothing playing"
                    state.loading -> "Looking for lyrics…"
                    !Settings.current.onlineLyrics -> "No lyrics in the file, and online lookup is off in Settings"
                    else -> "No lyrics found for ${state.song}"
                }
                Text(text, color = Retro.Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                if (state.missed) TextButton(onClick = onRetry) { Text("Retry", color = Retro.Orange) }
            }
            return@Box
        }
        // The position arrives four times a second; karaoke wants every frame, so it's carried
        // forward on the frame clock between updates.
        var now by remember { mutableLongStateOf(positionMs) }
        LaunchedEffect(positionMs, playing) {
            now = positionMs
            if (!playing) return@LaunchedEffect
            val start = withFrameMillis { it }
            while (true) withFrameMillis { f -> now = positionMs + (f - start) }
        }
        val current = lyrics.lineAt(now)
        val list = rememberLazyListState()
        // Scroll freely; the lyrics follow the song again a few seconds after the finger lifts.
        var touchedAt by remember { mutableLongStateOf(0L) }
        LaunchedEffect(list) {
            list.interactionSource.interactions.collect { if (it is DragInteraction.Start || it is DragInteraction.Stop) touchedAt = System.currentTimeMillis() }
        }
        LaunchedEffect(current, touchedAt) {
            val idle = System.currentTimeMillis() - touchedAt
            if (idle < FOLLOW_AFTER_MS) delay(FOLLOW_AFTER_MS - idle)
            if (current >= 0 && !list.isScrollInProgress) list.animateScrollToItem((current - 2).coerceAtLeast(0))
        }
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 20.dp),
        ) {
            itemsIndexed(lyrics.lines) { i, line ->
                val lit = i == current
                val color by animateColorAsState(
                    when {
                        lit -> Retro.Orange
                        current >= 0 && i < current -> Color.White.copy(alpha = 0.35f)
                        else -> Color.White.copy(alpha = 0.75f)
                    },
                    label = "lyric",
                )
                Text(
                    // The line being sung fills in as it's sung, like karaoke.
                    if (lit && lyrics.synced && line.text.isNotBlank()) karaoke(line.text, lyrics.sungChars(i, now)) else AnnotatedString(line.text.ifBlank { "♪" }),
                    color = color,
                    fontSize = if (lit) 21.sp else 18.sp,
                    fontWeight = if (lit) FontWeight.Bold else FontWeight.Medium,
                    textAlign = TextAlign.Start,
                    modifier = Modifier
                        .fillMaxWidth()
                        // Timed: tap a line to play from there. Plain lyrics have no times to go to.
                        .then(if (lyrics.synced && line.timeMs >= 0) Modifier.clickable { touchedAt = 0L; onSeek(line.timeMs) } else Modifier)
                        .padding(horizontal = 18.dp, vertical = 5.dp),
                )
            }
        }
        Text(
            "lyrics · ${lyrics.source}",
            color = Retro.Muted,
            fontFamily = Retro.Mono,
            fontSize = 9.sp,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        )
    }
}

/** How long after a scroll the lyrics wait before following the song again. */
private const val FOLLOW_AFTER_MS = 4_000L

/**
 * [text] with the first [sung] characters lit and the rest still to come; the character being
 * sung blends between the two, so the fill moves smoothly rather than a letter at a time.
 */
private fun karaoke(text: String, sung: Float): AnnotatedString {
    val done = sung.toInt().coerceIn(0, text.length)
    val part = (sung - done).coerceIn(0f, 1f)
    val lit = Retro.Orange
    val ahead = Color.White.copy(alpha = 0.55f)
    return buildAnnotatedString {
        withStyle(SpanStyle(color = lit)) { append(text, 0, done) }
        if (done < text.length) {
            withStyle(SpanStyle(color = lerp(ahead, lit, part))) { append(text[done]) }
            withStyle(SpanStyle(color = ahead)) { append(text, done + 1, text.length) }
        }
    }
}
