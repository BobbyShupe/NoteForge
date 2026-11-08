package com.robert.noteforge.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * An instrument wraps one imported audio sample and describes how it should
 * be pitched and trimmed when triggered from the score at a given MIDI note.
 */
@Entity(tableName = "instruments")
data class Instrument(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val audioFileId: Long,
    val rootNoteMidi: Int = 60,      // the pitch (MIDI) the raw sample plays back at natively (C4)
    val sampleStartMs: Long = 0,     // trim start within the source file
    val sampleEndMs: Long = -1,      // trim end; -1 = end of file
    val loopEnabled: Boolean = false,
    val loopStartMs: Long = 0,   // loop region start in source-file ms
    val loopEndMs: Long = -1,    // loop region end; -1 = end of trimmed sample
    val gain: Float = 1.0f,          // 0.0 - 2.0
    val colorHex: String = "#66FCF1",
    val synthPatchJson: String? = null, // set for synth instruments (live patch); null = sample instrument
    val syncUid: String = "",   // stable UUID for Arch sync (backfilled on migrate)
    val updatedAt: Long = 0L    // epoch ms of last edit, for sync merge
)
