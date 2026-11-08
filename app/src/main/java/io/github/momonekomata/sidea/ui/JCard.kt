package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.launch

/** A panel of the player's aluminium: a header printed with its title, then a ruled list. */
@Composable
fun JCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    art: ImageBitmap? = null,
    listState: LazyListState? = null,
    letters: List<Pair<Char, Int>> = emptyList(),
    /** The letters a script mark among [letters] opens up to (ก → ก ข ค …), from [subLetterIndex]. */
    subLetters: Map<Char, List<Pair<Char, Int>>> = emptyMap(),
    /** Stays put above the list while it scrolls (search, the queue's buttons). */
    pinned: (@Composable () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    JCardFrame(title, subtitle, modifier, art) {
        pinned?.let {
            it()
            HorizontalDivider(color = Retro.CreamShade, thickness = 1.dp)
        }
        val list = listState ?: rememberLazyListState()
        var active by remember { mutableStateOf<String?>(null) }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, content = content)
            if (letters.size > 1) {
                LetterIndex(letters, subLetters, list, { active = it }, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp))
            }
            active?.let { c ->
                Box(
                    Modifier.align(Alignment.Center).size(64.dp).background(Retro.Orange, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(c, color = Color.White, fontFamily = Retro.Logo, fontWeight = FontWeight.Black, fontStyle = FontStyle.Italic, fontSize = if (c.length > 1) 22.sp else 34.sp) }
            }
        }
    }
}

/** One mark on the index: a letter and the list item it jumps to, or a script that opens up, or the way back to A–Z. */
private class Mark(val label: String, val item: Int, val opens: Char? = null, val back: Boolean = false)

/**
 * The index down the card's edge: touch or slide along it to jump to the first entry of a
 * letter. A script's mark with letters of its own (ก, あ, 한 …) sits on a key: tapping it opens
 * the bar up to that script's letters (ก ข ค … ฮ), with A–Z at the top to come back. Scrolling
 * the list away from that script closes it too.
 */
@Composable
private fun LetterIndex(letters: List<Pair<Char, Int>>, subLetters: Map<Char, List<Pair<Char, Int>>>, list: LazyListState, onActive: (String?) -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var height by remember { mutableIntStateOf(1) }
    var open by remember(letters, subLetters) { mutableStateOf<Char?>(null) }
    val marks = remember(letters, subLetters, open) {
        val subs = open?.let { subLetters[it] }
        if (subs != null) listOf(Mark("A-Z", -1, back = true)) + subs.map { (c, i) -> Mark(c.toString(), i) }
        else letters.map { (c, i) -> Mark(c.toString(), i, opens = c.takeIf { subLetters.containsKey(it) }) }
    }
    // Scrolled away from the open script's rows (they're all off screen): back to A–Z.
    LaunchedEffect(open) {
        val g = open ?: return@LaunchedEffect
        val start = letters.firstOrNull { it.first == g }?.second ?: return@LaunchedEffect
        val end = letters.map { it.second }.filter { it > start }.minOrNull() ?: Int.MAX_VALUE
        androidx.compose.runtime.snapshotFlow {
            val shown = list.layoutInfo.visibleItemsInfo
            if (shown.isEmpty()) true else shown.first().index < end && shown.last().index >= start
        }.collect { inView -> if (!inView) open = null }
    }
    var active by remember { mutableStateOf<Mark?>(null) }
    // Each mark has a slot of its own, the same height (at most 28 dp, so a short list of letters
    // isn't strung out down the whole edge), and the marks sit centred: where one is drawn is
    // where a touch finds it.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val slot = minOf(height.toFloat() / marks.size, with(density) { 28.dp.toPx() })
    val top = (height - slot * marks.size) / 2
    fun markAt(y: Float) = marks[((y - top) / slot).toInt().coerceIn(0, marks.size - 1)]
    fun touch(m: Mark) {
        if (m === active) return
        active = m
        onActive(if (m.back) "A–Z" else m.label)
        if (!m.back) scope.launch { list.scrollToItem(m.item) }
    }
    Column(
        modifier
            .width(26.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(marks, slot, top) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val first = markAt(down.position.y)
                    touch(first)
                    var slid = false
                    do {
                        val e = awaitPointerEvent()
                        e.changes.forEach {
                            if (it.pressed) {
                                val m = markAt(it.position.y)
                                if (m !== first) slid = true
                                touch(m)
                                it.consume()
                            }
                        }
                    } while (e.changes.any { it.pressed })
                    val last = active
                    active = null
                    onActive(null)
                    when {
                        // Let go on A–Z: the whole index again.
                        last?.back == true -> open = null
                        // A tap on a script's key (not a slide past it): its letters.
                        !slid && first.opens != null -> open = first.opens
                    }
                }
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val slotDp = with(density) { slot.toDp() }
        marks.forEach { m ->
            val on = m === active
            Box(Modifier.height(slotDp).fillMaxWidth(), contentAlignment = Alignment.Center) { when {
                m.back -> Text(
                    "A-Z",
                    color = Color.White,
                    fontFamily = Retro.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 8.sp,
                    lineHeight = 8.sp,
                    modifier = Modifier.background(if (on) Retro.Ink else Retro.Orange, RoundedCornerShape(3.dp)).padding(horizontal = 2.dp, vertical = 3.dp),
                )
                m.opens != null -> Text(
                    // A key to press: it opens up.
                    m.label,
                    color = if (on) Color.White else Retro.Ink,
                    fontFamily = Retro.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    modifier = Modifier.background(if (on) Retro.Orange else Retro.CreamShade, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp, vertical = 1.dp),
                )
                else -> Text(
                    m.label,
                    color = if (on) Retro.Orange else Retro.InkFaint,
                    fontFamily = Retro.Mono,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    lineHeight = 10.sp,
                )
            } }
        }
    }
}

/**
 * The letters for [LetterIndex]: for each initial among [labels], the list item (counted from
 * [firstItem]) of its first entry. Digits and symbols file under #; scripts without an
 * alphabet order of their own (Thai, kana, kanji, hangul) get one mark each, after Z.
 */
fun letterIndex(labels: List<String>, firstItem: Int): List<Pair<Char, Int>> {
    val first = LinkedHashMap<Char, Int>()
    labels.forEachIndexed { i, label -> first.getOrPut(initialOf(label)) { firstItem + i } }
    val rank = io.github.momonekomata.sidea.library.LibOrder::rank
    return first.entries.sortedWith(compareBy({ rank(it.key) }, { it.key })).map { it.key to it.value }
}

/**
 * The letters inside each script mark of [letterIndex] that opens up (ก → ก ข ค …, あ → あ か さ …),
 * each with the list item of its first entry. Only scripts with two letters or more.
 */
fun subLetterIndex(labels: List<String>, firstItem: Int): Map<Char, List<Pair<Char, Int>>> {
    val out = LinkedHashMap<Char, LinkedHashMap<Char, Int>>()
    labels.forEachIndexed { i, label ->
        val sub = io.github.momonekomata.sidea.library.LibOrder.subInitialOf(label) ?: return@forEachIndexed
        out.getOrPut(initialOf(label)) { LinkedHashMap() }.getOrPut(sub) { firstItem + i }
    }
    return out.filterValues { it.size > 1 }.mapValues { (_, m) -> m.entries.sortedBy { it.value }.map { it.key to it.value } }
}

/** The letter [label] files under; the Library's lists are sorted by the same rule ([io.github.momonekomata.sidea.library.LibOrder]). */
internal fun initialOf(label: String): Char = io.github.momonekomata.sidea.library.LibOrder.initialOf(label)

/** The card's paper and header, with free-form content below. */
@Composable
fun JCardFrame(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    art: ImageBitmap? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .shadow(8.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(Retro.Cream),
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp).background(Retro.Orange))
        Row(Modifier.padding(horizontal = 18.dp, vertical = if (art != null) 8.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
            // The album cover where a real J-card has its front panel.
            if (art != null) {
                Image(
                    art,
                    contentDescription = "Cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(end = 12.dp).size(56.dp).clip(RoundedCornerShape(3.dp)),
                )
            }
            // Printed like the name on a Walkman's lid, heavy slanted capitals in navy, with what's
            // in it on the same line: one line, so the list gets the height.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title.uppercase(),
                    color = Retro.Ink,
                    fontFamily = Retro.Logo,
                    fontWeight = FontWeight.Black,
                    fontStyle = FontStyle.Italic,
                    letterSpacing = 1.sp,
                    fontSize = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // The title always shows; the counts beside it give way when it's narrow.
                    modifier = Modifier.widthIn(max = 240.dp),
                )
                Box(Modifier.padding(start = 10.dp).width(12.dp).height(2.dp).background(Retro.Orange))
                Text(subtitle, color = Retro.InkFaint, fontFamily = Retro.Mono, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
            }
        }
        HorizontalDivider(color = Retro.Ink.copy(alpha = 0.6f), thickness = 1.5.dp)
        content()
    }
}

/** One ruled line on the card; [onLongClick] opens a song's menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun JCardRow(
    number: String,
    title: String,
    detail: String = "",
    current: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
    onLongClick: (() -> Unit)? = null,
    /** Drawn at the end of the line, e.g. a drag handle. */
    trailing: (@Composable () -> Unit)? = null,
    /** Drawn in place of the number, e.g. the song's cover. */
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (current) Retro.Orange.copy(alpha = 0.18f) else Retro.Cream)
            .padding(horizontal = 18.dp, vertical = if (leading != null) 6.dp else 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.padding(end = 12.dp)) { leading() }
        } else Text(
            if (current) "▶" else number,
            color = if (current) Retro.Orange else Retro.InkFaint,
            fontFamily = Retro.Mono,
            fontSize = 12.sp,
            modifier = Modifier.width(30.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = Retro.Ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(detail, color = Retro.InkFaint, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        action?.let { (label, run) ->
            Text(
                label.uppercase(),
                color = Retro.Orange,
                fontFamily = Retro.Mono,
                fontSize = 11.sp,
                modifier = Modifier.clickable(onClick = run).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
            )
        }
        trailing?.invoke()
    }
    HorizontalDivider(color = Retro.CreamShade, thickness = 1.dp, modifier = Modifier.padding(horizontal = 18.dp))
}

/** A message or call to action written on the card instead of a track list. */
@Composable
fun JCardNote(text: String, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, color = Retro.Ink, fontSize = 14.sp)
        content()
        Spacer(Modifier.height(4.dp))
    }
}
