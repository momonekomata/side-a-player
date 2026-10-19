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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
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

/** The paper insert of a cassette case: header with the tape's title, then a ruled track list. */
@Composable
fun JCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    art: ImageBitmap? = null,
    listState: LazyListState? = null,
    letters: List<Pair<Char, Int>> = emptyList(),
    content: LazyListScope.() -> Unit,
) {
    JCardFrame(title, subtitle, modifier, art) {
        val list = listState ?: rememberLazyListState()
        var active by remember { mutableStateOf<Char?>(null) }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, content = content)
            if (letters.size > 1) {
                LetterIndex(letters, list, { active = it }, Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 6.dp))
            }
            active?.let { c ->
                Box(
                    Modifier.align(Alignment.Center).size(64.dp).background(Retro.Orange, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text(c.toString(), color = Retro.Cream, fontFamily = Retro.Hand, fontWeight = FontWeight.Bold, fontSize = 34.sp) }
            }
        }
    }
}

/** The index down the card's edge: touch or slide along it to jump to the first entry of a letter. */
@Composable
private fun LetterIndex(letters: List<Pair<Char, Int>>, list: LazyListState, onActive: (Char?) -> Unit, modifier: Modifier) {
    val scope = rememberCoroutineScope()
    var height by remember { mutableIntStateOf(1) }
    var active by remember { mutableStateOf<Char?>(null) }
    fun touch(y: Float) {
        val (c, item) = letters[(y / height * letters.size).toInt().coerceIn(0, letters.size - 1)]
        if (c == active) return
        active = c
        onActive(c)
        scope.launch { list.scrollToItem(item) }
    }
    Column(
        modifier
            .width(26.dp)
            .onSizeChanged { height = it.height.coerceAtLeast(1) }
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    touch(down.position.y)
                    do {
                        val e = awaitPointerEvent()
                        e.changes.forEach { if (it.pressed) { touch(it.position.y); it.consume() } }
                    } while (e.changes.any { it.pressed })
                    active = null
                    onActive(null)
                }
            },
        verticalArrangement = Arrangement.SpaceEvenly,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        letters.forEach { (c, _) ->
            Text(
                c.toString(),
                color = if (c == active) Retro.Orange else Retro.InkFaint,
                fontFamily = Retro.Mono,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                lineHeight = 10.sp,
            )
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
    fun rank(c: Char) = when {
        c == '#' -> 0
        c in 'A'..'Z' -> 1
        else -> 2
    }
    return first.entries.sortedWith(compareBy({ rank(it.key) }, { it.key })).map { it.key to it.value }
}

internal fun initialOf(label: String): Char {
    val text = label.trim().removePrefix("The ").removePrefix("the ").trimStart()
    val cp = text.codePoints().filter { Character.isLetterOrDigit(it) }.findFirst().orElse(-1)
    if (cp < 0 || Character.isDigit(cp)) return '#'
    // Accents off: É files under E.
    val plain = java.text.Normalizer.normalize(String(Character.toChars(cp)), java.text.Normalizer.Form.NFD)[0].uppercaseChar()
    if (plain in 'A'..'Z') return plain
    return when (Character.UnicodeScript.of(cp)) {
        Character.UnicodeScript.THAI -> 'ก'
        Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> 'あ'
        Character.UnicodeScript.HAN -> '漢'
        Character.UnicodeScript.HANGUL -> '한'
        Character.UnicodeScript.CYRILLIC -> 'Я'
        Character.UnicodeScript.GREEK -> 'Ω'
        else -> '#'
    }
}

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
        Box(Modifier.fillMaxWidth().height(6.dp).background(Retro.Orange))
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            // The album cover where a real J-card has its front panel.
            if (art != null) {
                Image(
                    art,
                    contentDescription = "Cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.padding(end = 12.dp).size(56.dp).clip(RoundedCornerShape(3.dp)),
                )
            }
            Column {
                Text(
                    title,
                    color = Retro.Ink,
                    fontFamily = Retro.Hand,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(subtitle, color = Retro.InkFaint, fontFamily = Retro.Mono, fontSize = 11.sp, maxLines = 1)
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
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(if (current) Retro.Orange.copy(alpha = 0.18f) else Retro.Cream)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
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
