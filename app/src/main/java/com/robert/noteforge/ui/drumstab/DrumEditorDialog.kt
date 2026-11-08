package com.robert.noteforge.ui.drumstab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.robert.noteforge.synth.DrumBias
import com.robert.noteforge.synth.DrumPatch
import com.robert.noteforge.synth.DrumType
import kotlin.random.Random

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun DrumEditorDialog(
    drum: DrumSound,
    onDismiss: () -> Unit,
    onSave: (DrumSound) -> Unit,
    onPreview: (DrumSound) -> Unit,
    isPlaying: Boolean
) {
    var name by remember { mutableStateOf(drum.name) }
    var patch by remember { mutableStateOf(drum.patch) }
    var bias by remember { mutableStateOf(DrumBias()) }

    // Re-roll with current bias
    fun reroll() {
        patch = DrumPatch.random(Random.nextLong(), patch.type, bias)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Drum Editor", modifier = Modifier.weight(1f))
                IconButton(onClick = { reroll() }) {
                    Icon(Icons.Filled.Casino, contentDescription = "Re-roll")
                }
                IconButton(onClick = { onPreview(drum.copy(name = name, patch = patch)) }) {
                    Icon(
                        if (isPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = "Preview"
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Drum type selector (FlowRow wraps naturally)
                Text("Type", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(4.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val typeLabels = mapOf(
                        DrumType.KICK to "Kick",
                        DrumType.SNARE to "Snare",
                        DrumType.HAT_CLOSED to "Hat Closed",
                        DrumType.HAT_OPEN to "Hat Open",
                        DrumType.TOM to "Tom",
                        DrumType.CLAP to "Clap",
                        DrumType.CYMBAL to "Cymbal",
                        DrumType.RIM to "Rim"
                    )
                    for ((t, label) in typeLabels) {
                        FilterChip(
                            selected = patch.type == t,
                            onClick = {
                                patch = DrumPatch.random(Random.nextLong(), t, bias)
                            },
                            label = { Text(label) }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))

                // Randomness bias controls
                Text("Dice Bias", style = MaterialTheme.typography.labelMedium)
                BiasSlider("Punchy ↔ Soft", bias.punchy) { bias = bias.copy(punchy = it) }
                BiasSlider("Acoustic ↔ Electronic", bias.acoustic) { bias = bias.copy(acoustic = it) }
                BiasSlider("Bright ↔ Dark", bias.bright) { bias = bias.copy(bright = it) }
                BiasSlider("Tight ↔ Loose", bias.tight) { bias = bias.copy(tight = it) }
                Spacer(modifier = Modifier.height(12.dp))

                // Parameter sliders
                Text("Parameters", style = MaterialTheme.typography.labelMedium)
                ParamSlider("Base Freq", patch.baseFreq, 30f, 8000f, true) {
                    patch = patch.copy(baseFreq = it)
                }
                ParamSlider("Pitch Env Amt", patch.pitchEnvAmt, 0f, 1f, false) {
                    patch = patch.copy(pitchEnvAmt = it)
                }
                ParamSlider("Pitch Env Decay", patch.pitchEnvDecay, 0f, 1f, false) {
                    patch = patch.copy(pitchEnvDecay = it)
                }
                ParamSlider("Tone Level", patch.toneLevel, 0f, 1f, false) {
                    patch = patch.copy(toneLevel = it)
                }
                ParamSlider("Tone Decay", patch.toneDecay, 0f, 1f, false) {
                    patch = patch.copy(toneDecay = it)
                }
                ParamSlider("Noise Level", patch.noiseLevel, 0f, 1f, false) {
                    patch = patch.copy(noiseLevel = it)
                }
                ParamSlider("Noise Decay", patch.noiseDecay, 0f, 1f, false) {
                    patch = patch.copy(noiseDecay = it)
                }
                ParamSlider("Noise Filter", patch.noiseFilterFreq, 0f, 1f, false) {
                    patch = patch.copy(noiseFilterFreq = it)
                }
                ParamSlider("Click Level", patch.clickLevel, 0f, 1f, false) {
                    patch = patch.copy(clickLevel = it)
                }
                ParamSlider("Attack", patch.attack, 0f, 1f, false) {
                    patch = patch.copy(attack = it)
                }
                ParamSlider("Decay", patch.decay, 0f, 1f, false) {
                    patch = patch.copy(decay = it)
                }
                ParamSlider("Drive", patch.drive, 0f, 1f, false) {
                    patch = patch.copy(drive = it)
                }
                ParamSlider("Body", patch.body, 0f, 1f, false) {
                    patch = patch.copy(body = it)
                }
                ParamSlider("Metallic", patch.metallic, 0f, 1f, false) {
                    patch = patch.copy(metallic = it)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(drum.copy(name = name, patch = patch)) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun BiasSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column {
        Text(
            "$label: ${(value * 100).toInt()}%",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = value,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun ParamSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    log: Boolean,
    onChange: (Float) -> Unit
) {
    // For log sliders, map 0..1 to log scale
    val sliderVal = if (log) {
        // value is in Hz, map to 0..1 log
        ((kotlin.math.ln(value / min) / kotlin.math.ln(max / min)).toFloat()).coerceIn(0f, 1f)
    } else {
        ((value - min) / (max - min)).coerceIn(0f, 1f)
    }
    Column {
        Text(
            "$label: ${if (log) "${value.toInt()} Hz" else "%.2f".format(value)}",
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = sliderVal,
            onValueChange = { v ->
                val newVal = if (log) {
                    (min * (max / min).pow(v)).coerceIn(min, max)
                } else {
                    (min + v * (max - min)).coerceIn(min, max)
                }
                onChange(newVal)
            },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun Float.pow(e: Float): Float = Math.pow(this.toDouble(), e.toDouble()).toFloat()
