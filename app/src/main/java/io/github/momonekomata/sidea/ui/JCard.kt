package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

/** The paper insert of a cassette case: header with the tape's title, then a ruled track list. */
@Composable
fun JCard(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    art: ImageBitmap? = null,
    listState: LazyListState? = null,
    content: LazyListScope.() -> Unit,
) {
    JCardFrame(title, subtitle, modifier, art) {
        LazyColumn(Modifier.fillMaxSize(), state = listState ?: rememberLazyListState(), content = content)
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

/** One ruled line on the card. */
@Composable
fun JCardRow(
    number: String,
    title: String,
    detail: String = "",
    current: Boolean = false,
    action: Pair<String, () -> Unit>? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
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
