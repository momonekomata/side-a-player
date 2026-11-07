package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.momonekomata.sidea.audio.Player
import io.github.momonekomata.sidea.cast.Cast

/**
 * Play on: this phone, or a UPnP/DLNA renderer (a Chord Poly, say) found on the Wi-Fi or the
 * phone's hotspot. Searching starts as it opens; one that won't show up can be typed in.
 */
@Composable
fun CastDialog(onDismiss: () -> Unit) {
    val devices by Cast.devices.collectAsStateWithLifecycle()
    val searching by Cast.searching.collectAsStateWithLifecycle()
    val note by Cast.searchNote.collectAsStateWithLifecycle()
    val target by Cast.target.collectAsStateWithLifecycle()
    var address by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { Cast.search() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Play on") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Choice(Icons.Filled.PhoneAndroid, "This phone", "Its own output, USB DAC included", target == null) {
                    Player.castTo(null)
                    onDismiss()
                }
                devices.forEach { d ->
                    val on = target?.key == d.key
                    Choice(if (on) Icons.Filled.CastConnected else Icons.Filled.Cast, d.name, listOf(d.desc.model, d.where).filter { it.isNotBlank() }.joinToString(" · "), on) {
                        Player.castTo(d)
                        onDismiss()
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (searching) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("Searching…", fontSize = 13.sp, color = Retro.Muted, modifier = Modifier.padding(start = 10.dp).weight(1f))
                    } else {
                        Text(
                            if (devices.isEmpty()) "No players found." else "${devices.size} found.",
                            fontSize = 13.sp, color = Retro.Muted, modifier = Modifier.weight(1f),
                        )
                        Text(
                            "SEARCH AGAIN", color = Retro.Ink, fontFamily = Retro.Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.keyFace().clickable { Cast.search() }.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                note?.let { Text(it, fontSize = 12.sp, color = Retro.Muted) }
                Text(
                    "Using the phone's hotspot? Join the player to it first. If it still doesn't show, type its address " +
                        "(the hotspot's list of connected devices has it):",
                    fontSize = 12.sp, color = Retro.Muted, modifier = Modifier.padding(top = 6.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        address, { address = it.trim() },
                        label = { Text("e.g. 192.168.43.17") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "FIND", color = Retro.Ink, fontFamily = Retro.Mono, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp).keyFace().clickable(enabled = address.isNotEmpty() && !searching) { Cast.search(address) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
                if (target != null) {
                    Text(
                        "While casting the player decodes the files itself, exactly as they are; WALKDECK's EQ doesn't apply. The volume keys set the player's volume.",
                        fontSize = 12.sp, color = Retro.Muted, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
    )
}

@Composable
private fun Choice(icon: ImageVector, name: String, detail: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (on) Retro.Orange else Retro.Muted, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(name, fontSize = 15.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, fontSize = 12.sp, color = Retro.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (on) Text("●", color = Retro.Orange, modifier = Modifier.width(16.dp))
    }
}
