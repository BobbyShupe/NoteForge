package com.robert.noteforge.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single placed note on the score.
 * startBeat / durationBeats are expressed in quarter-note beats so the
 * score is tempo-independent; the sequencer converts to milliseconds
 * using the project tempo at playback time.
 * accidental records enharmonic spelling for chromatic notes:
 * 0 = natural (or sharp pitch shown as sharp), +1 = sharp, -1 = flat.
 */
@Entity(tableName = "score_notes")
data class ScoreNote(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackId: Long,
    val pitchMidi: Int,
    val startBeat: Double,
    val durationBeats: Double,
    val velocity: Float = 1.0f,
    @ColumnInfo(defaultValue = "0") val accidental: Int = 0
)
