package com.robert.noteforge.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioFileDao {
    @Query("SELECT * FROM audio_files ORDER BY importedAtMs DESC")
    fun observeAll(): Flow<List<AudioFile>>

    @Query("SELECT * FROM audio_files WHERE id = :id")
    suspend fun getById(id: Long): AudioFile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(audioFile: AudioFile): Long

    @Delete
    suspend fun delete(audioFile: AudioFile)
}

@Dao
interface InstrumentDao {
    @Query("SELECT * FROM instruments ORDER BY id DESC")
    fun observeAll(): Flow<List<Instrument>>

    @Query("SELECT * FROM instruments WHERE id = :id")
    suspend fun getById(id: Long): Instrument?

    @Query("SELECT * FROM instruments WHERE syncUid = :uid LIMIT 1")
    suspend fun getBySyncUid(uid: String): Instrument?


    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(instrument: Instrument): Long

    @Update
    suspend fun update(instrument: Instrument)

    @Delete
    suspend fun delete(instrument: Instrument)
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY orderIndex ASC")
    fun observeAll(): Flow<List<Track>>

    @Query("SELECT * FROM tracks ORDER BY orderIndex ASC")
    suspend fun getAllOnce(): List<Track>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: Track): Long

    @Update
    suspend fun update(track: Track)

    @Delete
    suspend fun delete(track: Track)

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()
}

@Dao
interface ScoreNoteDao {
    @Query("SELECT * FROM score_notes")
    fun observeAll(): Flow<List<ScoreNote>>

    @Query("SELECT * FROM score_notes")
    suspend fun getAllOnce(): List<ScoreNote>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(note: ScoreNote): Long

    @Update
    suspend fun update(note: ScoreNote)

    @Delete
    suspend fun delete(note: ScoreNote)

    @Query("DELETE FROM score_notes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM score_notes WHERE trackId = :trackId")
    suspend fun getByTrack(trackId: Long): List<ScoreNote>

    @Query("DELETE FROM score_notes WHERE trackId = :trackId")
    suspend fun deleteByTrack(trackId: Long)

    @Query("DELETE FROM score_notes")
    suspend fun deleteAll()
}

@Dao
interface ProjectSettingsDao {
    @Query("SELECT * FROM project_settings WHERE id = 0")
    fun observe(): Flow<ProjectSettings?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(settings: ProjectSettings)
}

@Dao
interface SongTrackConfigDao {
    @Query("SELECT * FROM song_track_configs WHERE songKey = :songKey ORDER BY trackIndex")
    suspend fun getForSong(songKey: String): List<SongTrackConfig>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(configs: List<SongTrackConfig>)

    @Query("DELETE FROM song_track_configs WHERE songKey = :songKey")
    suspend fun deleteForSong(songKey: String)
}
