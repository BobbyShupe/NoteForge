package com.robert.noteforge.ui.filetab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.robert.noteforge.audio.RenderPrefs
import com.robert.noteforge.ui.theme.Accent
import com.robert.noteforge.ui.theme.Background

/**
 * Expandable card with video render settings:
 * resolution, frame rate, output type, video style, priority.
 */
/**
 * A settings chip themed to the app: selected chips get the cyan accent
 * fill with dark text instead of Material3's muddy default purple.
 */
@Composable
fun SettingChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = Accent,
            selectedLabelColor = Background,
            selectedLeadingIconColor = Background
        )
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun RenderSettingsCard() {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf(RenderPrefs.get(context)) }

    fun update(s: RenderPrefs.Settings) {
        settings = s
        RenderPrefs.set(context, s)
    }

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null
                )
                Text(
                    "Render Settings",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 8.dp).weight(1f)
                )
                Text(
                    RenderPrefs.resolutionLabel(settings.width, settings.height) +
                        " • ${settings.fps}fps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    // Resolution (landscape, portrait, square for mobile)
                    Text("Resolution", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for ((w, h, label) in RenderPrefs.RESOLUTIONS) {
                            SettingChip(
                                selected = settings.width == w && settings.height == h,
                                onClick = { update(settings.copy(width = w, height = h)) },
                                label = label
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Frame rate
                    Text("Frame rate", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (fps in listOf(24, 30, 60)) {
                            SettingChip(
                                selected = settings.fps == fps,
                                onClick = { update(settings.copy(fps = fps)) },
                                label = "$fps fps"
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Output type
                    Text("Output", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SettingChip(
                            selected = settings.output == RenderPrefs.OUTPUT_VIDEO_AUDIO,
                            onClick = { update(settings.copy(output = RenderPrefs.OUTPUT_VIDEO_AUDIO)) },
                            label = "Video + Audio"
                        )
                        SettingChip(
                            selected = settings.output == RenderPrefs.OUTPUT_AUDIO_ONLY,
                            onClick = { update(settings.copy(output = RenderPrefs.OUTPUT_AUDIO_ONLY)) },
                            label = "Audio only"
                        )
                        SettingChip(
                            selected = settings.output == RenderPrefs.OUTPUT_VIDEO_ONLY,
                            onClick = { update(settings.copy(output = RenderPrefs.OUTPUT_VIDEO_ONLY)) },
                            label = "Video only"
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))

                    // Video style (only if video included)
                    if (settings.output != RenderPrefs.OUTPUT_AUDIO_ONLY) {
                        Text("Video style", style = MaterialTheme.typography.labelMedium)
                        Spacer(modifier = Modifier.height(4.dp))
                        FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                            SettingChip(
                                selected = settings.style == RenderPrefs.STYLE_PIANO_ROLL,
                                onClick = { update(settings.copy(style = RenderPrefs.STYLE_PIANO_ROLL)) },
                                label = "Piano roll"
                            )
                            SettingChip(
                                selected = settings.style == RenderPrefs.STYLE_SCORE,
                                onClick = { update(settings.copy(style = RenderPrefs.STYLE_SCORE)) },
                                label = "Score notation"
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Priority
                    Text("Render priority", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SettingChip(
                            selected = settings.priority == RenderPrefs.PRIORITY_LOW,
                            onClick = { update(settings.copy(priority = RenderPrefs.PRIORITY_LOW)) },
                            label = "Low"
                        )
                        SettingChip(
                            selected = settings.priority == RenderPrefs.PRIORITY_NORMAL,
                            onClick = { update(settings.copy(priority = RenderPrefs.PRIORITY_NORMAL)) },
                            label = "Normal"
                        )
                        SettingChip(
                            selected = settings.priority == RenderPrefs.PRIORITY_HIGH,
                            onClick = { update(settings.copy(priority = RenderPrefs.PRIORITY_HIGH)) },
                            label = "High"
                        )
                    }
                    Text(
                        "Low = minimal CPU, quiet in background. High = fastest render.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    // Render range
                    Text("Render range", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SettingChip(
                            selected = settings.rangeFull,
                            onClick = { update(settings.copy(rangeFull = true)) },
                            label = "Full song"
                        )
                        SettingChip(
                            selected = !settings.rangeFull,
                            onClick = { update(settings.copy(rangeFull = false)) },
                            label = "Custom"
                        )
                    }
                    if (!settings.rangeFull) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            var startText by remember(settings.rangeStartSec) {
                                mutableStateOf(RenderPrefs.formatTime(settings.rangeStartSec))
                            }
                            var endText by remember(settings.rangeEndSec) {
                                mutableStateOf(
                                    if (settings.rangeEndSec <= 0f) ""
                                    else RenderPrefs.formatTime(settings.rangeEndSec)
                                )
                            }
                            OutlinedTextField(
                                value = startText,
                                onValueChange = { v ->
                                    startText = v
                                    RenderPrefs.parseTime(v)?.let {
                                        update(settings.copy(rangeStartSec = it))
                                    }
                                },
                                label = { Text("Start (M:SS)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                            OutlinedTextField(
                                value = endText,
                                onValueChange = { v ->
                                    endText = v
                                    if (v.isBlank()) {
                                        update(settings.copy(rangeEndSec = 0f))
                                    } else {
                                        RenderPrefs.parseTime(v)?.let {
                                            update(settings.copy(rangeEndSec = it))
                                        }
                                    }
                                },
                                label = { Text("End (blank=end)") },
                                modifier = Modifier.weight(1f),
                                singleLine = true
                            )
                        }
                        Text(
                            "Video starts at 0:00 with the playhead at your start point.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}
