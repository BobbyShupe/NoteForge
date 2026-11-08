package com.robert.noteforge.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "audio_files")
data class AudioFile(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val filePath: String,       // absolute path in app-internal storage
    val durationMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val importedAtMs: Long
)
