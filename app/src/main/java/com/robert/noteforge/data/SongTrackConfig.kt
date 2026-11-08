package com.robert.noteforge.data

import androidx.room.Entity

@Entity(tableName = "song_track_configs", primaryKeys = ["songKey", "trackIndex"])
data class SongTrackConfig(
    val songKey: String,
    val trackIndex: Int,
    val instrumentId: Long,
    val volume: Float,
    val muted: Boolean,
    val solo: Boolean,
    val clef: String,
    val noteColorArgb: Int?
)
