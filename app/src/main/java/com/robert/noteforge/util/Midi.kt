package com.robert.noteforge.util

/** MIDI note number <-> display name helpers (60 = C4, "middle C"). */
object Midi {
    private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    fun name(midi: Int): String {
        val octave = (midi / 12) - 1
        val note = NAMES[((midi % 12) + 12) % 12]
        return "$note$octave"
    }

    fun isSharp(midi: Int): Boolean = NAMES[((midi % 12) + 12) % 12].endsWith("#")

    /** Diatonic staff-line/space index within an octave for a natural note, used for staff Y placement.
     * Sharps are drawn on the line/space of their natural neighbor. */
    fun diatonicStep(midi: Int): Int {
        val stepsForPitchClass = intArrayOf(0, 0, 1, 1, 2, 3, 3, 4, 4, 5, 5, 6) // C..B -> 0..6
        val pitchClass = ((midi % 12) + 12) % 12
        val octave = (midi / 12) - 1
        return octave * 7 + stepsForPitchClass[pitchClass]
    }

    private val NATURAL_PITCH_CLASSES = intArrayOf(0, 2, 4, 5, 7, 9, 11) // C D E F G A B

    /** Inverse of [diatonicStep]: the natural-note MIDI pitch a given staff step represents. */
    fun stepToNaturalMidi(step: Int): Int {
        val idx = ((step % 7) + 7) % 7
        val octaveIndex = Math.floorDiv(step, 7)
        return (octaveIndex + 1) * 12 + NATURAL_PITCH_CLASSES[idx]
    }
}
