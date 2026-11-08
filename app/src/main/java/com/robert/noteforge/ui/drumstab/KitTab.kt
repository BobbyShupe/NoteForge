package com.robert.noteforge.ui.drumstab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.robert.noteforge.synth.DrumPatch
import com.robert.noteforge.synth.DrumSynth
import com.robert.noteforge.synth.GmDrumMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Kit tab: assign drum patches to GM drum map notes.
 * Each row shows a GM note (pitch + name) and its assigned drum sound.
 * Tap to pick a different sound from the user's collection.
 */
@Composable
fun KitTab(
    kit: DrumKit,
    drums: List<DrumSound>,
    onKitChanged: (DrumKit) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var assigningPitch by remember { mutableStateOf<Int?>(null) }

    // All GM drum pitches, sorted
    val gmPitches = (35..81).filter { GmDrumMap.drumTypeForPitch(it) != null }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Tap a pad to assign a drum sound. The kit is used when a MIDI song has drums.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        items(gmPitches, key = { it }) { pitch ->
            val patch = kit.getPatch(pitch)
            val drumName = drums.firstOrNull { it.patch.seed == patch?.seed }?.name
                ?: patch?.type?.name?.lowercase()?.replace('_', ' ')
                ?: "Unassigned"
            val gmName = GmDrumMap.nameForPitch(pitch)

            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { assigningPitch = pitch }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "$pitch • $gmName",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            drumName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(
                        onClick = {
                            patch?.let {
                                scope.launch(Dispatchers.Default) {
                                    val pcm = DrumSynth.renderToShort(it, 22050)
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        playPcm(context, pcm, 22050) {}
                                    }
                                }
                            }
                        }
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Preview")
                    }
                }
            }
        }
    }

    // Assignment dialog: pick from user's drums
    assigningPitch?.let { pitch ->
        DrumPickerDialog(
            drums = drums,
            onDismiss = { assigningPitch = null },
            onSelect = { drum ->
                val newMap = kit.assignments.toMutableMap()
                newMap[pitch] = drum.patch
                onKitChanged(DrumKit(newMap))
                assigningPitch = null
            }
        )
    }
}

@Composable
fun DrumPickerDialog(
    drums: List<DrumSound>,
    onDismiss: () -> Unit,
    onSelect: (DrumSound) -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose drum sound") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(drums, key = { it.id }) { drum ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onSelect(drum) }
                    ) {
                        Text(
                            drum.name,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                if (drums.isEmpty()) {
                    item {
                        Text("No drum sounds yet. Generate some in the Sounds tab first!")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
