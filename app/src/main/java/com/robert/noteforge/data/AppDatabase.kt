package com.robert.noteforge.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [AudioFile::class, Instrument::class, Track::class, ScoreNote::class, ProjectSettings::class, SongTrackConfig::class],
    version = 11,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun audioFileDao(): AudioFileDao
    abstract fun instrumentDao(): InstrumentDao
    abstract fun trackDao(): TrackDao
    abstract fun scoreNoteDao(): ScoreNoteDao
    abstract fun projectSettingsDao(): ProjectSettingsDao
    abstract fun songTrackConfigDao(): SongTrackConfigDao
}
