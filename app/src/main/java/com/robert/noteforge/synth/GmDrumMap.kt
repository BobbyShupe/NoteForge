package com.robert.noteforge.synth

import com.robert.noteforge.synth.DrumType

/**
 * General MIDI drum map: MIDI note -> DrumType.
 * Channel 10 (index 9) uses these pitches for drum sounds.
 */
object GmDrumMap {
    fun drumTypeForPitch(pitch: Int): DrumType? {
        return when (pitch) {
            35, 36 -> DrumType.KICK        // Acoustic Bass Drum, Bass Drum 1
            37 -> DrumType.RIM             // Side Stick
            38, 39 -> DrumType.SNARE       // Acoustic Snare, Electric Snare
            40 -> DrumType.SNARE          // Electric Snare (alt)
            41, 43 -> DrumType.TOM        // Low Floor Tom, High Floor Tom
            42 -> DrumType.HAT_CLOSED     // Closed Hi-Hat
            44 -> DrumType.HAT_CLOSED     // Pedal Hi-Hat
            45, 47 -> DrumType.TOM        // Low Tom, Low-Mid Tom
            46 -> DrumType.HAT_OPEN       // Open Hi-Hat
            48, 50 -> DrumType.TOM        // Hi-Mid Tom, High Tom
            49 -> DrumType.CYMBAL         // Crash Cymbal 1
            51 -> DrumType.RIM            // Ride Cymbal 1 (use rim as placeholder)
            52 -> DrumType.CYMBAL         // Chinese Cymbal
            53 -> DrumType.RIM            // Ride Bell
            54 -> DrumType.TOM            // Tambourine (use tom)
            55 -> DrumType.CYMBAL         // Splash Cymbal
            56 -> DrumType.CLAP           // Cowbell (use clap)
            57 -> DrumType.CYMBAL         // Crash Cymbal 2
            59 -> DrumType.RIM            // Ride Cymbal 2
            69 -> DrumType.CLAP           // Cabasa (use clap)
            70 -> DrumType.RIM            // Maracas
            else -> null
        }
    }

    /** Human-readable name for a drum pitch. */
    fun nameForPitch(pitch: Int): String {
        return when (pitch) {
            35 -> "Acoustic Kick"; 36 -> "Kick"
            37 -> "Side Stick"; 38 -> "Snare"; 39 -> "Electric Snare"; 40 -> "Snare 2"
            41 -> "Low Tom"; 42 -> "Closed Hat"; 43 -> "High Floor Tom"
            44 -> "Pedal Hat"; 45 -> "Low Tom"; 46 -> "Open Hat"; 47 -> "Low-Mid Tom"
            48 -> "Hi-Mid Tom"; 49 -> "Crash"; 50 -> "High Tom"
            51 -> "Ride"; 52 -> "Chinese"; 53 -> "Ride Bell"
            54 -> "Tambourine"; 55 -> "Splash"; 56 -> "Cowbell"; 57 -> "Crash 2"
            59 -> "Ride 2"; 69 -> "Cabasa"; 70 -> "Maracas"
            else -> "Drum $pitch"
        }
    }
}
