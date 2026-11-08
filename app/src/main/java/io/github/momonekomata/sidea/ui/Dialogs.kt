package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.momonekomata.sidea.CertPrompt
import io.github.momonekomata.sidea.Prefs
import io.github.momonekomata.sidea.sources.NasKind
import io.github.momonekomata.sidea.sources.NasServer
import io.github.momonekomata.sidea.sources.redirectUri

/** Adds or edits a server: a NAS over any of its protocols, or a cloud account. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NasServerDialog(
    existing: NasServer?,
    others: List<NasServer>,
    onSave: (NasServer) -> Unit,
    onSignIn: (NasServer) -> Unit,
    onDelete: (NasServer) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "My NAS") }
    var kind by remember { mutableStateOf(existing?.kind ?: NasKind.SMB) }
    var address by remember { mutableStateOf(existing?.address.orEmpty()) }
    var port by remember { mutableStateOf(existing?.port?.takeIf { it > 0 }?.toString().orEmpty()) }
    var implicit by remember { mutableStateOf(existing?.implicitTls ?: false) }
    var basePath by remember { mutableStateOf(existing?.basePath ?: "/") }
    var user by remember { mutableStateOf(existing?.username.orEmpty()) }
    var pass by remember { mutableStateOf(existing?.password.orEmpty()) }
    var selfSigned by remember { mutableStateOf(existing?.allowSelfSigned ?: false) }
    var clientId by remember { mutableStateOf(existing?.clientId.orEmpty()) }
    var clientSecret by remember { mutableStateOf(existing?.clientSecret.orEmpty()) }

    val addressOk = when {
        kind.cloud -> clientId.isNotBlank() && (kind != NasKind.GDRIVE || clientSecret.isNotBlank())
        kind == NasKind.WEBDAV -> address.startsWith("https://") || address.startsWith("http://")
        else -> address.isNotBlank() && !address.contains("://")
    }
    val nameTaken = others.any { it.name == name && it.id != existing?.id }

    fun built(): NasServer {
        val base = existing ?: NasServer(name = name, kind = kind, address = address)
        val changedEndpoint = existing != null && (existing.address != address || existing.kind != kind || existing.port != (port.toIntOrNull() ?: 0))
        val keepsPin = !changedEndpoint && (kind == NasKind.SFTP || (kind in listOf(NasKind.WEBDAV, NasKind.FTPS) && selfSigned))
        val keepsToken = existing != null && existing.kind == kind && existing.clientId == clientId.trim()
        return base.copy(
            name = name.trim(),
            kind = kind,
            address = address,
            port = port.toIntOrNull() ?: 0,
            implicitTls = implicit,
            basePath = basePath.ifBlank { "/" },
            username = user,
            password = pass,
            allowSelfSigned = selfSigned,
            // A different server must earn its own pin, and a different app its own sign-in.
            pinnedSha256 = if (keepsPin) base.pinnedSha256 else null,
            clientId = clientId.trim(),
            clientSecret = clientSecret.trim(),
            refreshToken = if (keepsToken) base.refreshToken else "",
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add a server or cloud" else "Edit ${existing.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NasKind.entries.forEach { k -> FilterChip(kind == k, { kind = k }, label = { Text(k.label) }) }
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, isError = nameTaken)
                if (kind.cloud) {
                    CloudFields(kind, clientId, { clientId = it.trim() }, clientSecret, { clientSecret = it.trim() }, signedIn = existing?.refreshToken?.isNotBlank() == true && existing.kind == kind)
                } else {
                    OutlinedTextField(
                        address, { address = it.trim() },
                        label = { Text(if (kind == NasKind.WEBDAV) "URL (https://nas.local:5006/music)" else "Host (nas.local or 192.168.1.10)") },
                        singleLine = true,
                        isError = address.isNotEmpty() && !addressOk,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    if (kind == NasKind.WEBDAV && address.startsWith("http://")) {
                        Text("Plain http sends your password unencrypted. Use https if the NAS supports it.", fontSize = 12.sp)
                    }
                    if (kind == NasKind.FTP) {
                        Text("FTP sends everything unencrypted, the password included. Prefer SMB, SFTP or FTPS when the NAS has them.", fontSize = 12.sp)
                    }
                    if (kind != NasKind.WEBDAV) {
                        val defaultPort = when (kind) {
                            NasKind.FTPS -> if (implicit) 990 else 21
                            NasKind.FTP -> 21
                            NasKind.SFTP -> 22
                            else -> 445
                        }
                        OutlinedTextField(
                            port, { port = it.filter(Char::isDigit) },
                            label = { Text("Port (blank = $defaultPort)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        if (kind == NasKind.FTPS) LabeledSwitch("Implicit TLS (port 990)", implicit) { implicit = it }
                        OutlinedTextField(
                            basePath, { basePath = it },
                            label = { Text(if (kind == NasKind.SMB) "Share and folder (Music or Media/Music)" else "Start folder") },
                            singleLine = true,
                        )
                    }
                    OutlinedTextField(user, { user = it }, label = { Text(if (kind == NasKind.SMB) "User name (blank = guest; DOMAIN\\user works)" else "User name") }, singleLine = true)
                    OutlinedTextField(
                        pass, { pass = it },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    )
                    if (kind == NasKind.WEBDAV || kind == NasKind.FTPS) {
                        LabeledSwitch("NAS uses a self-signed certificate", selfSigned) { selfSigned = it }
                        if (selfSigned) {
                            Text("On first connect you'll be shown its fingerprint to confirm. After that only that certificate is accepted.", fontSize = 12.sp)
                        }
                    }
                    if (kind == NasKind.SFTP) {
                        Text("The server's SSH key is trusted the first time and remembered; a different key later is refused.", fontSize = 12.sp)
                    }
                }
            }
        },
        confirmButton = {
            val ok = addressOk && name.isNotBlank() && !nameTaken
            if (kind.cloud) {
                TextButton(enabled = ok, onClick = { onSignIn(built()) }) { Text("Save & sign in") }
            } else {
                TextButton(enabled = ok, onClick = { onSave(built()) }) { Text("Save") }
            }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton({ onDelete(existing) }) { Text("Delete") }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** A cloud needs an app registration of your own; these are its ID (and secret) and where to send people back. */
@Composable
private fun CloudFields(kind: NasKind, clientId: String, onId: (String) -> Unit, secret: String, onSecret: (String) -> Unit, signedIn: Boolean) {
    val where = when (kind) {
        NasKind.GDRIVE -> "Google Cloud Console: an OAuth client of type Desktop app, with the Drive API switched on."
        NasKind.ONEDRIVE -> "Azure portal, App registrations: a Mobile and desktop app, personal and work accounts, with this redirect address."
        else -> "Dropbox App Console: an app with files.metadata.read, files.content.read and files.content.write, and this redirect address."
    }
    Text("$where Steps are in the README under Cloud storage.", fontSize = 12.sp)
    if (kind != NasKind.GDRIVE) Text("Redirect address: ${kind.redirectUri()}", fontSize = 12.sp, fontFamily = Retro.Mono)
    OutlinedTextField(clientId, onId, label = { Text(if (kind == NasKind.DROPBOX) "App key" else "Client ID") }, singleLine = true)
    if (kind == NasKind.GDRIVE) {
        OutlinedTextField(
            secret, onSecret,
            label = { Text("Client secret") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
    }
    Text(
        if (signedIn) "Signed in. Save & sign in again only if it stops working."
        else "Save & sign in opens the sign-in page in your browser; come back here once it says you're signed in.",
        fontSize = 12.sp,
    )
}

@Composable
fun SettingsDialog(prefs: Prefs, onChange: (Prefs) -> Unit, onSources: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Where the Library's music comes from: folders on the phone, NAS and cloud.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Retro.Orange)
                        .clickable(onClick = onSources)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Music sources", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text("Phone folders, NAS and cloud for the Library", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp)
                    }
                    Text("›", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
                // The look: one key per theme, the chosen one orange and pressed in.
                Text("Theme", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    io.github.momonekomata.sidea.AppTheme.entries.forEach { t ->
                        val on = prefs.theme == t
                        Text(
                            t.label, color = if (on) Color.White else Retro.Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            modifier = Modifier.keyFace(orange = on, down = on).clickable { onChange(prefs.copy(theme = t)) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                Note(prefs.theme.note)
                LabeledSwitch("Exclusive USB driver", prefs.exclusiveUsb) { onChange(prefs.copy(exclusiveUsb = it)) }
                Note("For phones whose Android can't send USB audio bit-perfect (most Samsungs): WALKDECK drives the DAC itself, at each file's own rate, after you allow USB access. Other apps can't use the DAC while WALKDECK holds it. Volume is then WALKDECK's own: the volume keys drive it, and 100 % is bit-perfect.")
                LabeledSwitch("Gapless playback", prefs.gapless) { onChange(prefs.copy(gapless = it)) }
                Note("Opens the next song early and joins it to the current one with no gap, when both share a sample rate.")
                // What follows a folder: one key per choice, like the theme's.
                Text("When a folder ends", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    io.github.momonekomata.sidea.FolderEnd.entries.forEach { e ->
                        val on = prefs.folderEnd == e
                        Text(
                            e.label, color = if (on) Color.White else Retro.Ink, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            modifier = Modifier.keyFace(orange = on, down = on).clickable { onChange(prefs.copy(folderEnd = e)) }.padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                Note(prefs.folderEnd.note + " (Not with repeat on.)")
                LabeledSwitch("DSD as DoP to the USB DAC", prefs.dop) { onChange(prefs.copy(dop = it)) }
                Note("Native DSD for DACs that understand DoP. Only used when the bit-perfect path is available; otherwise DSD is converted to 176.4 kHz PCM. Turn off if your DAC plays DSD as noise.")
                LabeledSwitch("Find lyrics online", prefs.onlineLyrics) { onChange(prefs.copy(onlineLyrics = it)) }
                Note("When a song has no lyrics in its tags or a .lrc file beside it, its title, artist and length are sent to the sources below, in order, until one has them.")
                if (prefs.onlineLyrics) {
                    LabeledSwitch("LRCLIB · timed lyrics, open", prefs.lrclib) { onChange(prefs.copy(lrclib = it)) }
                    var key by remember(prefs.musixmatchKey) { mutableStateOf(prefs.musixmatchKey) }
                    OutlinedTextField(
                        key,
                        { key = it.trim(); onChange(prefs.copy(musixmatchKey = key)) },
                        label = { Text("Musixmatch API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Note("Free key from developer.musixmatch.com. The free plan gives part of each song's lyrics; timed lyrics need a paid plan. Leave blank to skip Musixmatch.")
                    LabeledSwitch("lyrics.ovh · plain lyrics, open", prefs.lyricsOvh) { onChange(prefs.copy(lyricsOvh = it)) }
                    LabeledSwitch("Save found lyrics beside the songs", prefs.saveLyrics) { onChange(prefs.copy(saveLyrics = it)) }
                    Note("As a .lrc file with the song's name (timed when the lyrics are), in the same folder on the phone or the NAS. Most players read these. The audio files themselves are never changed, and a .lrc that's already there is never overwritten.")
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Done") } },
    )
}

@Composable
private fun Note(text: String) = Text(text, fontSize = 12.sp, color = Retro.Muted)

@Composable
internal fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 14.sp)
        Switch(checked, onChange)
    }
}

@Composable
fun CertificateDialog(prompt: CertPrompt, onTrust: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trust ${prompt.server.name}?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This server's certificate isn't signed by a known authority. Check that the fingerprint matches the one your NAS shows (usually under its certificate settings).")
                Text(prompt.subject, fontSize = 12.sp)
                Text(prompt.sha256, fontFamily = Retro.Mono, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onTrust) { Text("Trust") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}
