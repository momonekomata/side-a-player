package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.layout.Arrangement
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
import io.github.momonekomata.sidea.sources.NasKind
import io.github.momonekomata.sidea.sources.NasServer

@Composable
fun NasServerDialog(
    existing: NasServer?,
    others: List<NasServer>,
    onSave: (NasServer) -> Unit,
    onDelete: (NasServer) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name ?: "My NAS") }
    var kind by remember { mutableStateOf(existing?.kind ?: NasKind.WEBDAV) }
    var address by remember { mutableStateOf(existing?.address.orEmpty()) }
    var port by remember { mutableStateOf(existing?.port?.takeIf { it > 0 }?.toString().orEmpty()) }
    var implicit by remember { mutableStateOf(existing?.implicitTls ?: false) }
    var basePath by remember { mutableStateOf(existing?.basePath ?: "/") }
    var user by remember { mutableStateOf(existing?.username.orEmpty()) }
    var pass by remember { mutableStateOf(existing?.password.orEmpty()) }
    var selfSigned by remember { mutableStateOf(existing?.allowSelfSigned ?: false) }

    val addressOk = when (kind) {
        NasKind.WEBDAV -> address.startsWith("https://") || address.startsWith("http://")
        NasKind.FTPS -> address.isNotBlank() && !address.contains("://")
    }
    val nameTaken = others.any { it.name == name && it.id != existing?.id }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add NAS" else "Edit ${existing.name}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(kind == NasKind.WEBDAV, { kind = NasKind.WEBDAV }, label = { Text("WebDAV") })
                    FilterChip(kind == NasKind.FTPS, { kind = NasKind.FTPS }, label = { Text("FTPS") })
                }
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, isError = nameTaken)
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
                if (kind == NasKind.FTPS) {
                    OutlinedTextField(
                        port, { port = it.filter(Char::isDigit) },
                        label = { Text("Port (blank = ${if (implicit) 990 else 21})") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    LabeledSwitch("Implicit TLS (port 990)", implicit) { implicit = it }
                    OutlinedTextField(basePath, { basePath = it }, label = { Text("Start folder") }, singleLine = true)
                }
                OutlinedTextField(user, { user = it }, label = { Text("User name") }, singleLine = true)
                OutlinedTextField(
                    pass, { pass = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                LabeledSwitch("NAS uses a self-signed certificate", selfSigned) { selfSigned = it }
                if (selfSigned) {
                    Text("On first connect you'll be shown its fingerprint to confirm. After that only that certificate is accepted.", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = addressOk && name.isNotBlank() && !nameTaken,
                onClick = {
                    val base = existing ?: NasServer(name = name, kind = kind, address = address)
                    val changedEndpoint = existing != null && (existing.address != address || existing.kind != kind)
                    onSave(
                        base.copy(
                            name = name.trim(),
                            kind = kind,
                            address = address,
                            port = port.toIntOrNull() ?: 0,
                            implicitTls = implicit,
                            basePath = basePath.ifBlank { "/" },
                            username = user,
                            password = pass,
                            allowSelfSigned = selfSigned,
                            // A different server must earn its own pin.
                            pinnedSha256 = if (changedEndpoint || !selfSigned) null else base.pinnedSha256,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton({ onDelete(existing) }) { Text("Delete") }
                TextButton(onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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
