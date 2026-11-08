package com.robert.noteforge.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "project_settings")
data class ProjectSettings(
    @PrimaryKey val id: Int = 0,
    val tempoBpm: Int = 120,
    val beatsPerMeasure: Int = 4,
    val snapDivision: Int = 4, // 4 = sixteenth-note grid (quarter beat / 4)
    /** Global time signature numerator; kept in sync with [beatsPerMeasure]. */
    val timeSigNum: Int = 4,
    /** Global time signature denominator (displayed; spacing follows the numerator). */
    val timeSigDen: Int = 4,
    /** Beam quantization grid in beats (Bobby 2026-10-14). */
    val beamQuantizeGrid: Double = 0.25,
    /** Beam IOI threshold in beats. */
    val beamIoiThreshold: Double = 0.75,
    /** Beam gap/rest threshold in beats. */
    val beamGapThreshold: Double = 0.5
)
