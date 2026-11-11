package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import io.github.momonekomata.sidea.audio.RepeatMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.withLink
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import io.github.momonekomata.sidea.LyricsState
import io.github.momonekomata.sidea.audio.SignalPath
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.unit.sp

/** What the deck shows, whichever source is playing. */
data class DeckModel(
    val title: String,
    val subtitle: String,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
    val status: String,
    val statusColor: Color,
    /** The graded signal path behind the quality badge. */
    val path: SignalPath? = null,
    val art: ImageBitmap? = null,
    val lyrics: LyricsState = LyricsState(),
    /** The square shows lyrics instead of the cover. */
    val showLyrics: Boolean = false,
    val onToggleLyrics: () -> Unit = {},
    val onRetryLyrics: () -> Unit = {},
    /** Null hides the heart (streaming songs are favourited in their own apps). */
    val favorite: Boolean? = null,
    val onFavorite: () -> Unit = {},
    /** Null hides shuffle/repeat (streaming apps don't take them from other apps). */
    val shuffle: Boolean? = null,
    val repeat: RepeatMode? = null,
    val onShuffle: () -> Unit = {},
    val onRepeat: () -> Unit = {},
    val onSkip: (Long) -> Unit = {},
    val onToggle: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onSeek: (Long) -> Unit,
    /** The cover's colour, for the faceplate; null leaves it silver. */
    val tint: Color? = null,
    /** The two halves of [subtitle], each a link when its callback is set. */
    val artist: String = "",
    val album: String = "",
    /** Opens the artist's page in the Library; null when the song isn't in it. */
    val onArtist: (() -> Unit)? = null,
    /** Opens the album's page in the Library; null when the song isn't in it. */
    val onAlbum: (() -> Unit)? = null,
)

/**
 * "Artist · Album" under the song's title, each a link to its page in the Library (underlined,
 * orange while pressed) when the deck knows where; plain [DeckModel.subtitle] otherwise.
 */
@Composable
internal fun ArtistAlbumLine(
    model: DeckModel, color: Color, fontSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier, maxLines: Int = 1,
    style: androidx.compose.ui.text.TextStyle = androidx.compose.material3.LocalTextStyle.current,
) {
    if (model.subtitle.isBlank()) return
    val linked = (model.onArtist != null && model.artist.isNotBlank()) || (model.onAlbum != null && model.album.isNotBlank())
    if (!linked) {
        Text(model.subtitle, color = color, fontSize = fontSize, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier, style = style)
        return
    }
    val text = androidx.compose.ui.text.buildAnnotatedString {
        fun part(s: String, go: (() -> Unit)?) {
            if (go == null) {
                append(s)
                return
            }
            val link = androidx.compose.ui.text.LinkAnnotation.Clickable(
                tag = s,
                styles = androidx.compose.ui.text.TextLinkStyles(
                    style = androidx.compose.ui.text.SpanStyle(textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                    pressedStyle = androidx.compose.ui.text.SpanStyle(color = Retro.Orange, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline),
                ),
            ) { go() }
            withLink(link) { append(s) }
        }
        if (model.artist.isNotBlank()) part(model.artist, model.onArtist)
        if (model.artist.isNotBlank() && model.album.isNotBlank()) append(" · ")
        if (model.album.isNotBlank()) part(model.album, model.onAlbum)
    }
    Text(text, color = color, fontSize = fontSize, maxLines = maxLines, overflow = TextOverflow.Ellipsis, modifier = modifier, style = style)
}

/** What's printed on the faceplate: dark on silver, light on a cover's colour. */
internal class DeckInk(val main: Color, val muted: Color)

internal fun deckInk(tint: Color?) =
    if (tint == null) DeckInk(Retro.OnMetal, Retro.OnMetalMuted) else DeckInk(Color(0xFFF3F5F8), Color(0xFFF3F5F8).copy(alpha = 0.72f))

/**
 * Cover art, full size, with the lyrics in the same square: tap to swap. Below it the title,
 * the quality badge with the signal path, the seek bar and the transport keys. [compact] lays the
 * square beside the controls, for a phone on its side. [fullBleed] is the phone's full-screen
 * player: the cover from the very top of the screen, fading into the faceplate, and tall piano
 * keys down to the bottom edge. The faceplate is brushed metal in the cover's colour.
 */
@Composable
fun Deck(model: DeckModel, modifier: Modifier = Modifier, compact: Boolean = false, fullBleed: Boolean = false) {
    var showPath by remember { mutableStateOf(false) }
    val ink = deckInk(model.tint)
    if (fullBleed && !compact) {
        FullBleedDeck(model, ink, modifier) { showPath = true }
        val path = model.path
        if (showPath && path != null) SignalPathDialog(path, onDismiss = { showPath = false })
        return
    }
    // The faceplate: brushed aluminium, silver or in the cover's colour.
    val panel = modifier
        .clip(RoundedCornerShape(10.dp))
        .brushedMetal(model.tint)
    if (compact) {
        // A phone on its side: the cover no wider than about two fifths of the panel, so the
        // song, the badge and all the keys get a roomy column beside it.
        BoxWithConstraints(panel.padding(10.dp)) {
            val art = minOf(maxHeight, maxWidth * 0.42f)
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtSquare(model, Modifier.size(art))
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically)) {
                    Controls(model, ink, compact = true) { showPath = true }
                }
            }
        }
    } else {
        // The cover edge to edge on top, the song and its badge under it (tap the badge for the
        // signal path), the transport pinned to the bottom. The cover shrinks only when the
        // screen is too short for all three.
        BoxWithConstraints(panel) {
            val art = minOf(maxWidth, maxHeight - 250.dp).coerceAtLeast(120.dp)
            Column(Modifier.fillMaxSize()) {
                ArtSquare(model, Modifier.size(art).align(Alignment.CenterHorizontally), shape = RectangleShape)
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SongInfo(model, ink, compact = false) { showPath = true }
                }
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                    SeekBar(model, ink)
                    TransportPager(model, compact = false)
                }
            }
        }
    }
    val path = model.path
    if (showPath && path != null) SignalPathDialog(path, onDismiss = { showPath = false })
}

/**
 * The phone's full-screen player: the cover from the top edge of the screen (the status bar over
 * it), fading into the faceplate; the song, its badge and the seek bar; and the piano keys
 * running down to the bottom edge.
 */
@Composable
private fun FullBleedDeck(model: DeckModel, ink: DeckInk, modifier: Modifier, onPath: () -> Unit) {
    val bottomInset = androidx.compose.foundation.layout.WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(modifier.fillMaxSize().brushedMetal(model.tint)) {
        val keysH = 104.dp + bottomInset
        // As tall as the room allows, up to a touch taller than wide (barely cropping the cover).
        // The lyrics take all the room down to the song, starting under the status bar.
        val statusTop = androidx.compose.foundation.layout.WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        // Cover ⇄ lyrics: the square's height, its fade and its top all glide together, on the
        // same curve and time as the deck rising over the header (see SideAScreen).
        val glide = androidx.compose.animation.core.tween<Float>(DECK_GLIDE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        val glideDp = androidx.compose.animation.core.tween<Dp>(DECK_GLIDE_MS, easing = androidx.compose.animation.core.FastOutSlowInEasing)
        val artH by androidx.compose.animation.core.animateDpAsState(
            if (model.showLyrics) (maxHeight - keysH - 190.dp).coerceAtLeast(140.dp)
            else minOf(maxWidth * 1.06f, maxHeight - keysH - 190.dp).coerceAtLeast(140.dp),
            glideDp, label = "art-height",
        )
        val fadeFrom by androidx.compose.animation.core.animateFloatAsState(if (model.showLyrics) 0.86f else 0.62f, glide, label = "art-fade")
        val topPad by androidx.compose.animation.core.animateDpAsState(if (model.showLyrics) statusTop else 0.dp, glideDp, label = "art-top")
        val shade by androidx.compose.animation.core.animateFloatAsState(if (model.showLyrics) 1f else 0f, glide, label = "art-shade")
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(artH)
                    // The cover's foot fades out, so the brushed metal itself shows through it.
                    .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(0f to Color.Black, fadeFrom to Color.Black, 1f to Color.Transparent),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                    },
            ) {
                ArtSquare(model, Modifier.fillMaxSize().padding(top = topPad), shape = RectangleShape, showHint = false)
                // With the lyrics up the deck reaches the top of the screen: a little shade behind
                // the status bar's icons.
                Box(Modifier.fillMaxWidth().height(72.dp).graphicsLayer { alpha = shade }.background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent))))
            }
            // The song and its format sit down by the seek bar and the keys.
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom),
            ) {
                Box {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { SongInfo(model, ink, compact = false, onPath) }
                    // 🎵 / 🖼: a small key just above the song's name at the right. It hangs off
                    // the song's block, which keeps one place on the screen whatever the cover
                    // or lyrics above are doing, so the key never moves.
                    val hint = when {
                        model.showLyrics -> "🖼"
                        model.lyrics.lyrics != null || model.lyrics.loading -> "🎵"
                        else -> null
                    }
                    if (hint != null) {
                        Box(Modifier.align(Alignment.TopEnd).offset(y = (-44).dp)) {
                            EmojiKey(hint, if (model.showLyrics) "Show the cover" else "Show the lyrics", model.onToggleLyrics)
                        }
                    }
                }
            }
            Box(Modifier.padding(horizontal = 16.dp)) { SeekBar(model, ink) }
            PianoTransport(model, bottomInset)
        }
    }
}

/** How long the deck takes to glide between the cover and the lyrics. */
internal const val DECK_GLIDE_MS = 380

/** A small square key with an emoji on it (🎵 lyrics, 🖼 cover). */
@Composable
internal fun EmojiKey(emoji: String, label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .keyFace()
            .clickable(onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 16.sp)
    }
}

/**
 * Tall piano keys from the seek bar down to the screen's bottom edge: shuffle, previous, PLAY
 * (orange, held down while playing), next and repeat. Swipe them for −10 −5 +5 +10. The names
 * sit above the gesture bar.
 */
@Composable
private fun PianoTransport(model: DeckModel, bottomInset: Dp) {
    val pager = rememberPagerState { 2 }
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(2) { i ->
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (pager.currentPage == i) Retro.Orange else Color.White.copy(alpha = 0.45f)))
        }
    }
    val tall = 98.dp + bottomInset
    val housing = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
    HorizontalPager(pager, Modifier.fillMaxWidth().height(tall)) { page ->
        Row(
            Modifier
                .fillMaxSize()
                .clip(housing)
                .background(Brush.verticalGradient(listOf(Color(0xFF8A8E94), Color(0xFFA7ABB0), Color(0xFFB4B7BB))))
                .padding(start = 5.dp, end = 5.dp, top = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            val m = Modifier.weight(1f).fillMaxHeight()
            if (page == 0) {
                model.shuffle?.let { on -> PianoKey(m, Icons.Filled.Shuffle, "Shuffle", "SHUF", bottomInset, led = on, onClick = model.onShuffle) }
                PianoKey(m, Icons.Filled.SkipPrevious, "Previous", "PREV", bottomInset, onHold = { model.onSkip(-5_000) }, onClick = model.onPrevious)
                PianoKey(
                    Modifier.weight(1.35f).fillMaxHeight(), if (model.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause",
                    if (model.playing) "PAUSE" else "PLAY", bottomInset, orange = true, latched = model.playing, onClick = model.onToggle,
                )
                PianoKey(m, Icons.Filled.SkipNext, "Next", "NEXT", bottomInset, onHold = { model.onSkip(5_000) }, onClick = model.onNext)
                model.repeat?.let { r ->
                    PianoKey(
                        m, if (r == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat", if (r == RepeatMode.ONE) "RPT 1" else "RPT",
                        bottomInset, led = r != RepeatMode.OFF, onClick = model.onRepeat,
                    )
                }
            } else {
                for (step in listOf(-10_000L, -5_000L, 5_000L, 10_000L)) PianoWind(m, step, bottomInset, model.onSkip)
            }
        }
    }
}

/** A tall key: its sloped front a shade darker at the top, the symbol on it, the name at its foot. */
@Composable
internal fun PianoKey(
    modifier: Modifier, icon: ImageVector, label: String, caption: String, bottomInset: Dp = 0.dp,
    orange: Boolean = false, latched: Boolean = false, led: Boolean? = null, onHold: (() -> Unit)? = null, onClick: () -> Unit,
) {
    KeyBox(modifier, orange = orange, latched = latched, led = led, onHold = onHold, onClick = onClick) { _ ->
        PianoFace(orange) {
            Icon(icon, contentDescription = label, tint = if (orange) Color.White else Retro.OnMetal, modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp).size(26.dp))
            Text(
                caption, color = if (orange) Color.White else Retro.OnMetalMuted, fontFamily = Retro.Mono, fontSize = 9.sp, maxLines = 1,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp + bottomInset),
            )
        }
    }
}

/**
 * ◀◀ / ▶▶ (two triangles) or ◀◀◀ / ▶▶▶ (three) for the wind keys: more triangles, the bigger the
 * jump. Drawn, so both sizes match and no font is needed for them.
 */
@Composable
internal fun WindMark(forward: Boolean, triangles: Int, tint: Color, height: Dp, modifier: Modifier = Modifier) {
    androidx.compose.foundation.Canvas(modifier.size(width = height * 0.62f * triangles, height = height)) {
        val w = size.width / triangles
        val h = size.height
        for (i in 0 until triangles) {
            val x = i * w
            val p = androidx.compose.ui.graphics.Path().apply {
                if (forward) {
                    moveTo(x, 0f); lineTo(x + w, h / 2); lineTo(x, h)
                } else {
                    moveTo(x + w, 0f); lineTo(x, h / 2); lineTo(x + w, h)
                }
                close()
            }
            drawPath(p, tint)
        }
    }
}

/** A wind key's jump: ±5 s with two triangles, ±10 s with three. */
internal fun windTriangles(stepMs: Long) = if (kotlin.math.abs(stepMs) >= 10_000) 3 else 2

/**
 * A tall wind key, like a deck's: a tap jumps [stepMs] (back when negative), holding it winds,
 * the same jump again every quarter second until it's let go.
 */
@Composable
internal fun PianoWind(modifier: Modifier, stepMs: Long, bottomInset: Dp = 0.dp, onSkip: (Long) -> Unit) {
    val label = (if (stepMs < 0) "Back " else "Forward ") + "${kotlin.math.abs(stepMs) / 1000} seconds (hold to wind)"
    KeyBox(modifier.semantics { contentDescription = label }, onHold = { onSkip(stepMs) }, onClick = { onSkip(stepMs) }) { _ ->
        PianoFace(false) {
            WindMark(stepMs > 0, windTriangles(stepMs), Retro.OnMetal, 18.dp, Modifier.align(Alignment.TopCenter).padding(top = 20.dp))
            Text(
                "${kotlin.math.abs(stepMs) / 1000} SEC", color = Retro.OnMetalMuted, fontFamily = Retro.Mono, fontSize = 9.sp, maxLines = 1,
                fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp + bottomInset),
            )
        }
    }
}

@Composable
private fun PianoFace(orange: Boolean, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier.fillMaxSize().drawBehind {
            // The key's sloped front: a band a little darker across its top, then the lit edge.
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = if (orange) 0.10f else 0.07f), 0.30f to Color.Black.copy(alpha = 0.02f),
                    0.31f to Color.White.copy(alpha = if (orange) 0.10f else 0.35f), 0.325f to Color.Transparent, 1f to Color.Transparent,
                    endY = 100.dp.toPx(),
                ),
            )
        },
        content = content,
    )
}

@Composable
private fun Controls(model: DeckModel, ink: DeckInk, compact: Boolean, onPath: () -> Unit) {
    SongInfo(model, ink, compact, onPath)
    SeekBar(model, ink)
    TransportPager(model, compact)
}

/** Title, artist and album, the heart, and the quality badge with the format beside it. */
@Composable
private fun SongInfo(model: DeckModel, ink: DeckInk, compact: Boolean, onPath: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                model.title.ifBlank { "Nothing playing" },
                color = ink.main,
                fontSize = if (compact) 16.sp else 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ArtistAlbumLine(model, ink.muted, 12.sp)
        }
        model.favorite?.let { fav ->
            IconButton(onClick = model.onFavorite, modifier = Modifier.size(40.dp)) {
                Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", tint = if (fav) Retro.Orange else ink.muted)
            }
        }
    }
    val path = model.path
    if (path != null) {
        // The badge and the format; the rest of the signal path is a tap on the badge away.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QualityBadge(path.quality, onPath)
            // One line: the format fits beside the badge; anything longer is cut short.
            Text(
                model.status.lines().first(),
                color = ink.muted,
                fontSize = 12.sp,
                softWrap = false,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clickable(onClick = onPath),
            )
        }
    } else {
        StatusLine(model.status, if (model.statusColor == Retro.OnMetalMuted) ink.muted else model.statusColor)
    }
}

/**
 * The transport keys; swipe them left for −10 −5 +5 +10. Two dots show which is up.
 */
@Composable
internal fun TransportPager(model: DeckModel, compact: Boolean) {
    val keySize = if (compact) 44.dp else 54.dp
    val pager = rememberPagerState { 2 }
    // The keys plus the strip around them.
    HorizontalPager(pager, Modifier.fillMaxWidth().height(keySize + 14.dp)) { page ->
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // As many square keys as the row holds, shrunk if the width is short.
            val n = if (page == 0) (if (compact) 3 else 2 + (if (model.shuffle != null) 1 else 0) + (if (model.repeat != null) 1 else 0) + 1)
            else 4 + (if (compact && model.shuffle != null) 1 else 0) + (if (compact && model.repeat != null) 1 else 0)
            val fit = (maxWidth - 12.dp - 5.dp * (n - 1)) / n
            val k = minOf(keySize, fit)
            if (page == 0) TransportRow(model, k, compact) else SkipRow(model, k, compact)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        repeat(2) { i ->
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (pager.currentPage == i) Retro.Orange else Retro.MetalEdge))
        }
    }
}

/**
 * One row of square keys in their grey strip, as on the Walkman's side: PLAY is the orange one, and
 * it stays pressed in while the tape runs.
 */
@Composable
private fun TransportRow(model: DeckModel, k: Dp, compact: Boolean) {
    KeyStrip {
        // Beside a cover on a phone held sideways there's only room for the three keys;
        // shuffle and repeat move to the second page there.
        if (!compact) ShuffleKey(model, k)
        // Hold to rewind / fast-forward; tap for previous / next.
        DeckKey(Icons.Filled.SkipPrevious, "Previous", "REW", k, onHold = { model.onSkip(-5_000) }, onClick = model.onPrevious)
        DeckKey(if (model.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", if (model.playing) "PAUSE" else "PLAY", k, orange = true, latched = model.playing, onClick = model.onToggle)
        DeckKey(Icons.Filled.SkipNext, "Next", "FF", k, onHold = { model.onSkip(5_000) }, onClick = model.onNext)
        if (!compact) RepeatKey(model, k)
    }
}

@Composable
internal fun ShuffleKey(model: DeckModel, k: Dp) {
    model.shuffle?.let { on ->
        DeckKey(Icons.Filled.Shuffle, "Shuffle", "SHUF", k, led = on, onClick = model.onShuffle)
    }
}

@Composable
internal fun RepeatKey(model: DeckModel, k: Dp) {
    model.repeat?.let { r ->
        DeckKey(
            if (r == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat", if (r == RepeatMode.ONE) "RPT 1" else "RPT", k,
            led = r != RepeatMode.OFF, onClick = model.onRepeat,
        )
    }
}

/** The cover, or the lyrics in its place; one tap swaps them. */
@Composable
private fun ArtSquare(model: DeckModel, modifier: Modifier, shape: Shape = RoundedCornerShape(8.dp), hintAlign: Alignment = Alignment.BottomEnd, showHint: Boolean = true) {
    Box(
        modifier
            .clip(shape)
            .background(Color(0xFF111111))
            .clickable(onClick = model.onToggleLyrics),
    ) {
        Crossfade(model.showLyrics, animationSpec = androidx.compose.animation.core.tween(DECK_GLIDE_MS), label = "art-lyrics") { lyricsSide ->
            if (lyricsSide) {
                LyricsSquare(model.lyrics, model.positionMs, model.playing, model.onRetryLyrics, model.onSeek, Modifier.fillMaxSize())
            } else {
                val art = model.art
                if (art != null) {
                    Image(art, "Cover", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize().background(Retro.Shell), contentAlignment = Alignment.Center) {
                        Text("A", color = Retro.Orange, fontSize = 72.sp, fontWeight = FontWeight.Black)
                    }
                }
            }
        }
        // A hint for what a tap does, and that lyrics exist at all.
        val hint = if (!showHint) null else when {
            model.showLyrics -> "COVER"
            model.lyrics.lyrics != null -> "LYRICS"
            model.lyrics.loading -> "LYRICS…"
            else -> null
        }
        if (hint != null) {
            Text(
                hint,
                color = Color.White,
                fontFamily = Retro.Mono,
                fontSize = 10.sp,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(hintAlign)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f))
                    // Timed lyric lines take taps themselves (to jump), so the chip swaps back too.
                    .clickable(onClick = model.onToggleLyrics)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/** ◀◀◀ ◀◀ ▶▶ ▶▶▶: 10 and 5 seconds back and forward (with shuffle and repeat at the ends when compact). */
@Composable
private fun SkipRow(model: DeckModel, k: Dp, compact: Boolean) {
    KeyStrip {
        if (compact) ShuffleKey(model, k)
        for (step in listOf(-10_000L, -5_000L, 5_000L, 10_000L)) WindKey(step, k, model.onSkip)
        if (compact) RepeatKey(model, k)
    }
}

/** A square wind key: a tap jumps [stepMs], holding it winds (see [PianoWind]). */
@Composable
internal fun WindKey(stepMs: Long, k: Dp, onSkip: (Long) -> Unit, turn: Float = 0f) {
    val label = (if (stepMs < 0) "Back " else "Forward ") + "${kotlin.math.abs(stepMs) / 1000} seconds (hold to wind)"
    SquareKey(k, onHold = { onSkip(stepMs) }, onClick = { onSkip(stepMs) }, modifier = Modifier.semantics { contentDescription = label }) { _ ->
        // [turn]: the Stereo lying sideways turns its symbols upright.
        WindMark(stepMs > 0, windTriangles(stepMs), Retro.OnMetal, if (k < 44.dp) 11.dp else 13.dp, Modifier.rotate(turn))
    }
}

@Composable
internal fun StatusLine(text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(
            text,
            color = color,
            fontFamily = Retro.Mono,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

/**
 * A seek bar's hand: the finger's spot while dragging, then the spot it was let go at until the
 * player reports being there. Without that the bar showed, for a frame, the song's old spot (the
 * player's answer arrives a moment after the finger lifts) and flicked back and forth.
 */
@androidx.compose.runtime.Stable
internal class SeekHand {
    var dragging by mutableStateOf<Float?>(null)
    var landing by mutableStateOf<Float?>(null)

    /** Where the bar is drawn, [actual] being where the song is (0..1 of [durationMs]). */
    fun shown(actual: Float, durationMs: Long): Float {
        dragging?.let { return it }
        val l = landing ?: return actual
        // Close enough (the player got there): follow the song again.
        return if (kotlin.math.abs(l - actual) * durationMs.coerceAtLeast(1) < 1500) actual else l
    }

    /** The finger lifted: seek to where it was, and keep the bar there. */
    fun release(durationMs: Long, onSeek: (Long) -> Unit) {
        val f = dragging ?: return
        landing = f
        dragging = null
        onSeek((f * durationMs).toLong())
    }
}

@Composable
internal fun rememberSeekHand(): SeekHand {
    val hand = remember { SeekHand() }
    // Let the spot go after a moment whatever happens (a seek the player clamped, an error).
    LaunchedEffect(hand.landing) {
        if (hand.landing != null) {
            kotlinx.coroutines.delay(1500)
            hand.landing = null
        }
    }
    return hand
}

@Composable
private fun SeekBar(model: DeckModel, ink: DeckInk) {
    val hand = rememberSeekHand()
    val duration = model.durationMs.coerceAtLeast(1)
    val value = hand.shown((model.positionMs.toFloat() / duration).coerceIn(0f, 1f), duration)
    Column {
        Slider(
            value = value,
            onValueChange = { hand.dragging = it },
            onValueChangeFinished = { hand.release(duration, model.onSeek) },
            enabled = model.durationMs > 0,
            colors = SliderDefaults.colors(
                thumbColor = Retro.Orange,
                activeTrackColor = Retro.Orange,
                inactiveTrackColor = ink.muted.copy(alpha = 0.45f),
                disabledInactiveTrackColor = ink.muted.copy(alpha = 0.3f),
            ),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime((value * duration).toLong()), color = ink.muted, fontFamily = Retro.Mono, fontSize = 11.sp)
            Text(if (model.durationMs > 0) formatTime(model.durationMs) else "--:--", color = ink.muted, fontFamily = Retro.Mono, fontSize = 11.sp)
        }
    }
}

/**
 * A square key with its name printed under the symbol. [orange] is the one orange key; [latched]
 * keeps it pressed in (PLAY while the tape runs); [led] gives it a little light, orange when on.
 */
@Composable
internal fun DeckKey(
    icon: ImageVector, label: String, caption: String, k: Dp = 54.dp,
    orange: Boolean = false, latched: Boolean = false, led: Boolean? = null,
    onHold: (() -> Unit)? = null, onClick: () -> Unit,
) {
    SquareKey(k, orange = orange, latched = latched, led = led, onHold = onHold, onClick = onClick) { _ ->
        val ink = if (orange) Color.White else Retro.OnMetal
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = label, tint = ink, modifier = Modifier.size(if (k < 46.dp) 18.dp else 22.dp))
            if (k >= 40.dp) Text(caption, color = if (orange) Color.White else Retro.OnMetalMuted, fontFamily = Retro.Mono, fontSize = 7.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
        }
    }
}

/**
 * The square key itself: it sinks while held (and stays down while [latched]). A tap is a tap;
 * with [onHold], holding repeats it every 250 ms like a tape deck's FF/REW.
 */
@Composable
internal fun SquareKey(
    k: Dp, modifier: Modifier = Modifier, orange: Boolean = false, latched: Boolean = false, led: Boolean? = null,
    onHold: (() -> Unit)? = null, onClick: () -> Unit, content: @Composable (down: Boolean) -> Unit,
) = KeyBox(modifier.size(k), orange, latched, led, onHold, onClick, content)

/** A key of any size [modifier] gives it, pressed and held as [SquareKey]. */
@Composable
internal fun KeyBox(
    modifier: Modifier, orange: Boolean = false, latched: Boolean = false, led: Boolean? = null,
    onHold: (() -> Unit)? = null, onClick: () -> Unit, content: @Composable (down: Boolean) -> Unit,
) {
    val hold by rememberUpdatedState(onHold)
    val click by rememberUpdatedState(onClick)
    var pressed by remember { mutableStateOf(false) }
    val down = pressed || latched
    Box(
        modifier
            .padding(top = if (down) 1.5.dp else 0.dp, bottom = if (down) 0.dp else 1.5.dp)
            .keyFace(orange = orange, down = down)
            .then(if (led != null) Modifier.drawWithContent { drawContent(); drawKeyLed(led) } else Modifier)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown()
                    pressed = true
                    try {
                        keyGesture(hold, click)
                    } finally {
                        pressed = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) { content(down) }
}

/** After the press: a tap, or (with [hold]) a hold that repeats every 250 ms. */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.keyGesture(hold: (() -> Unit)?, click: () -> Unit) {
    if (hold == null) {
        if (waitForUpOrCancellation() != null) click()
        return
    }
    // A timeout and a cancelled touch both come back as null, so tell them apart: a swipe that
    // starts on the key (the pager or a list taking the touch) must not count as holding it.
    var ended = KeyEnd.HELD
    withTimeoutOrNull(400) { ended = if (waitForUpOrCancellation() != null) KeyEnd.UP else KeyEnd.CANCELLED }
    when (ended) {
        KeyEnd.UP -> { click(); return }
        KeyEnd.CANCELLED -> return
        KeyEnd.HELD -> {}
    }
    while (true) {
        hold()
        ended = KeyEnd.HELD
        withTimeoutOrNull(250) { ended = if (waitForUpOrCancellation() != null) KeyEnd.UP else KeyEnd.CANCELLED }
        if (ended != KeyEnd.HELD || currentEvent.changes.all { !it.pressed }) return
    }
}

private enum class KeyEnd { HELD, UP, CANCELLED }

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
