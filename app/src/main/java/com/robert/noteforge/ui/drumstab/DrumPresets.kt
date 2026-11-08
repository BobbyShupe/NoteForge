package com.robert.noteforge.ui.drumstab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.robert.noteforge.synth.DrumBias
import com.robert.noteforge.synth.DrumPatch
import com.robert.noteforge.synth.DrumType

/** Factory preset: a named drum patch. */
data class DrumPreset(val name: String, val patch: DrumPatch)

/** 16 factory presets: 2 variations for each of the 8 drum types. */
fun getFactoryPresets(): List<DrumPreset> {
    return listOf(
        // Kicks
        DrumPreset("Deep Kick", DrumPatch.random(1001, DrumType.KICK, DrumBias(punchy = 0.8f, bright = 0.3f))),
        DrumPreset("Punchy Kick", DrumPatch.random(1002, DrumType.KICK, DrumBias(punchy = 1f, bright = 0.6f))),
        // Snares
        DrumPreset("Fat Snare", DrumPatch.random(2001, DrumType.SNARE, DrumBias(punchy = 0.7f, acoustic = 0.8f))),
        DrumPreset("Electro Snare", DrumPatch.random(2002, DrumType.SNARE, DrumBias(punchy = 0.9f, acoustic = 0.2f))),
        // Hats
        DrumPreset("Tight Hat", DrumPatch.random(3001, DrumType.HAT_CLOSED, DrumBias(tight = 1f, bright = 0.8f))),
        DrumPreset("Open Hat", DrumPatch.random(3002, DrumType.HAT_OPEN, DrumBias(tight = 0.3f, bright = 0.7f))),
        // Toms
        DrumPreset("Low Tom", DrumPatch.random(4001, DrumType.TOM, DrumBias(punchy = 0.6f, bright = 0.4f))),
        DrumPreset("High Tom", DrumPatch.random(4002, DrumType.TOM, DrumBias(punchy = 0.7f, bright = 0.6f))),
        // Claps
        DrumPreset("Big Clap", DrumPatch.random(5001, DrumType.CLAP, DrumBias(punchy = 0.8f, acoustic = 0.6f))),
        DrumPreset("Snap Clap", DrumPatch.random(5002, DrumType.CLAP, DrumBias(punchy = 1f, tight = 0.8f))),
        // Cymbals
        DrumPreset("Crash", DrumPatch.random(6001, DrumType.CYMBAL, DrumBias(bright = 0.9f, tight = 0.2f))),
        DrumPreset("Ride", DrumPatch.random(6002, DrumType.CYMBAL, DrumBias(bright = 0.7f, tight = 0.5f))),
        // Rims
        DrumPreset("Rim Click", DrumPatch.random(7001, DrumType.RIM, DrumBias(punchy = 1f, tight = 1f))),
        DrumPreset("Woodblock", DrumPatch.random(7002, DrumType.RIM, DrumBias(punchy = 0.7f, acoustic = 0.9f))),
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PresetGrid(
    onSelect: (DrumPreset) -> Unit,
    onPreview: (DrumPatch) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        for (preset in getFactoryPresets()) {
            FilterChip(
                selected = false,
                onClick = { onSelect(preset) },
                label = { Text(preset.name) }
            )
        }
    }
}
