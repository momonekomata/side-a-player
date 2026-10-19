package io.github.momonekomata.sidea.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.momonekomata.sidea.audio.AutoEqParser
import io.github.momonekomata.sidea.audio.Biquad
import io.github.momonekomata.sidea.audio.EqBand
import io.github.momonekomata.sidea.audio.EqConfig
import io.github.momonekomata.sidea.audio.EqPresets
import io.github.momonekomata.sidea.audio.FilterType
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow

private const val MAX_BANDS = 10
private const val CURVE_RATE = 96_000 // response is drawn at a hi-res rate so the top octave isn't bent by Nyquist

/** Full-screen parametric EQ. Every change applies to the playing song straight away. */
@Composable
fun EqScreen(config: EqConfig, onChange: (EqConfig) -> Unit, onDismiss: () -> Unit) {
    var pasting by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .fillMaxSize()
                .background(Retro.Background)
                .safeDrawingPadding()
                .padding(horizontal = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                Text("Parametric EQ", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black, modifier = Modifier.weight(1f))
                Switch(config.enabled, { onChange(config.copy(enabled = it)) })
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = Retro.Muted) }
            }
            Text(
                if (config.isActive) "On · playback shows ENHANCED; DSD plays as PCM while it's on"
                else "Off or flat · the bit-perfect path is untouched",
                color = if (config.isActive) Color(0xFF6FB8FF) else Retro.Muted,
                fontFamily = Retro.Mono,
                fontSize = 11.sp,
            )
            ResponseCurve(config, Modifier.fillMaxWidth().height(170.dp).padding(vertical = 10.dp))

            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EqPresets.all.forEach { (name, bands) ->
                    AssistChip(onClick = { onChange(config.copy(enabled = true, bands = bands, autoPreamp = true)) }, label = { Text(name) })
                }
                AssistChip(onClick = { pasting = true }, label = { Text("Paste AutoEQ…") })
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("Preamp ${signed(config.effectivePreampDb)} dB", color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text("Auto", color = Retro.Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                Switch(config.autoPreamp, { onChange(config.copy(autoPreamp = it, preampDb = config.effectivePreampDb)) })
            }
            if (!config.autoPreamp) {
                EqSlider(config.preampDb.toFloat(), -24f..6f) { onChange(config.copy(preampDb = it.toDouble())) }
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(config.bands) { i, band ->
                    BandEditor(i + 1, band,
                        onChange = { b -> onChange(config.copy(bands = config.bands.toMutableList().also { it[i] = b })) },
                        onDelete = { onChange(config.copy(bands = config.bands.toMutableList().also { it.removeAt(i) })) },
                    )
                }
                if (config.bands.size < MAX_BANDS) {
                    item {
                        TextButton(onClick = { onChange(config.copy(enabled = true, bands = config.bands + EqBand())) }) {
                            Text("+ Add band", color = Retro.Orange)
                        }
                    }
                }
            }
        }
    }
    if (pasting) AutoEqPasteDialog(onImport = { onChange(it); pasting = false }, onDismiss = { pasting = false })
}

@Composable
private fun BandEditor(number: Int, band: EqBand, onChange: (EqBand) -> Unit, onDelete: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Retro.Panel)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$number", color = Retro.Orange, fontFamily = Retro.Mono, fontWeight = FontWeight.Bold, modifier = Modifier.width(24.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterType.entries.forEach { t ->
                    FilterChip(band.type == t, { onChange(band.copy(type = t)) }, label = { Text(t.label, fontSize = 12.sp) })
                }
            }
            Switch(band.enabled, { onChange(band.copy(enabled = it)) })
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Remove band", tint = Retro.Muted) }
        }
        LabeledValue("Frequency", formatHz(band.freqHz))
        // Logarithmic: equal slider travel per octave.
        EqSlider((log10(band.freqHz) - 1.3).toFloat() / 3f, 0f..1f) { onChange(band.copy(freqHz = roundHz(10.0.pow(1.3 + it * 3.0)))) }
        if (band.type.hasGain) {
            LabeledValue("Gain", "${signed(band.gainDb)} dB")
            EqSlider(band.gainDb.toFloat(), -15f..15f) { onChange(band.copy(gainDb = (it * 10).toInt() / 10.0)) }
        }
        LabeledValue("Q", "%.2f".format(band.q))
        EqSlider((ln(band.q / 0.1) / ln(100.0)).toFloat(), 0f..1f) { onChange(band.copy(q = (0.1 * 100.0.pow(it.toDouble()) * 100).toInt() / 100.0)) }
    }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, color = Retro.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontFamily = Retro.Mono, fontSize = 12.sp)
    }
}

@Composable
private fun EqSlider(value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        valueRange = range,
        colors = SliderDefaults.colors(thumbColor = Retro.Orange, activeTrackColor = Retro.Orange, inactiveTrackColor = Retro.ShellEdge),
    )
}

/** The combined response, preamp included, on a log-frequency grid from 20 Hz to 20 kHz, ±15 dB. */
@Composable
private fun ResponseCurve(config: EqConfig, modifier: Modifier) {
    val filters = config.activeBands.map { Biquad.design(it, CURVE_RATE) }
    val pre = if (config.enabled) config.effectivePreampDb else 0.0
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Retro.Panel)) {
        val w = size.width
        val h = size.height
        fun y(db: Double) = (h / 2 - db / 15.0 * (h / 2 - 8)).toFloat()
        fun x(f: Double) = (log10(f / 20.0) / 3.0 * w).toFloat()
        listOf(-12.0, -6.0, 6.0, 12.0).forEach { drawLine(Retro.ShellEdge, Offset(0f, y(it)), Offset(w, y(it)), strokeWidth = 1f) }
        drawLine(Retro.Muted, Offset(0f, y(0.0)), Offset(w, y(0.0)), strokeWidth = 1.5f)
        listOf(100.0, 1000.0, 10000.0).forEach { drawLine(Retro.ShellEdge, Offset(x(it), 0f), Offset(x(it), h), strokeWidth = 1f) }
        if (!config.enabled) return@Canvas
        val curve = Path()
        val steps = 200
        for (i in 0..steps) {
            val f = 20.0 * 10.0.pow(3.0 * i / steps)
            val db = pre + filters.sumOf { it.responseDb(f, CURVE_RATE) }
            val px = x(f); val py = y(db.coerceIn(-15.0, 15.0))
            if (i == 0) curve.moveTo(px, py) else curve.lineTo(px, py)
        }
        drawPath(curve, Retro.Orange, style = Stroke(width = 3f))
        config.activeBands.forEach { b ->
            val gain = if (b.type.hasGain) b.gainDb else 0.0
            drawCircle(Color.White, 6f, Offset(x(b.freqHz.coerceIn(20.0, 20000.0)), y(gain.coerceIn(-15.0, 15.0))))
        }
    }
}

@Composable
private fun AutoEqPasteDialog(onImport: (EqConfig) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) { AutoEqParser.parse(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste AutoEQ") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("From autoeq.app, choose your headphones and copy the \"Parametric EQ\" text (Equalizer APO format).", fontSize = 12.sp)
                OutlinedTextField(text, { text = it }, minLines = 5, maxLines = 10, placeholder = { Text("Preamp: -6.2 dB\nFilter 1: ON PK Fc 105 Hz Gain -2.5 dB Q 0.70") })
                Text(
                    when {
                        text.isBlank() -> ""
                        parsed == null -> "No filters found in that text."
                        parsed.bands.size > MAX_BANDS -> "${parsed.bands.size} filters found; the first $MAX_BANDS will be used."
                        else -> "${parsed.bands.size} filters found."
                    },
                    fontSize = 12.sp,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = parsed != null, onClick = { parsed?.let { onImport(it.copy(bands = it.bands.take(MAX_BANDS))) } }) { Text("Use") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

private fun signed(db: Double) = (if (db > 0.05) "+" else "") + "%.1f".format(db)

private fun formatHz(f: Double) = if (f >= 1000) "%.2f kHz".format(f / 1000).replace(".00 ", " ") else "%.0f Hz".format(f)

/** Snaps to values people type: whole Hz below 1 kHz, 10 Hz steps above. */
private fun roundHz(f: Double) = if (f < 1000) Math.round(f).toDouble() else Math.round(f / 10) * 10.0
