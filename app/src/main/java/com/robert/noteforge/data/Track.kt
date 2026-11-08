package com.robert.noteforge.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Staff clef for a track. The offset shifts diatonic steps so the clef's
 * reference pitch lands on its staff line (display steps 30..38 = treble
 * E4..F5). BASS staff (G2..A3) sits 12 steps below treble. The octave clefs
 * shift by a further diatonic octave (7 steps): HIGH_TREBLE reads high
 * instruments (written octave below sounding pitch), BASS_8VB reads low
 * instruments (written octave above sounding pitch). */
enum class Clef(val label: String, val offsetSteps: Int) {
    TREBLE("Treble", 0),
    TREBLE_8VA("High Treble", -7),
    BASS("Bass", 12),
    BASS_8VB("8vb Bass", 19);

    companion object {
        /** Unknown names (e.g. retired Alto/Tenor clefs) fall back to treble. */
        fun fromName(name: String?): Clef =
            entries.find { it.name == name } ?: TREBLE
    }
}

@Entity(tableName = "tracks")
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val instrumentId: Long,
    val name: String,
    val orderIndex: Int,
    val muted: Boolean = false,
    val solo: Boolean = false,
    /** Custom note color as ARGB int, or null for the automatic palette. */
    val noteColorArgb: Int? = null,
    /** Per-track playback volume multiplier, 0f..1f. */
    val volume: Float = 1f,
    /** Staff clef name ([Clef.name]), drawn at the start of the lane. */
    val clef: String = Clef.TREBLE.name
)
