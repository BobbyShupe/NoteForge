package com.robert.noteforge.data

import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioFileRepository @Inject constructor(private val dao: AudioFileDao) {
    fun observeAll(): Flow<List<AudioFile>> = dao.observeAll()
    suspend fun getById(id: Long) = dao.getById(id)
    suspend fun insert(audioFile: AudioFile) = dao.insert(audioFile)
    suspend fun delete(audioFile: AudioFile) = dao.delete(audioFile)
}

@Singleton
class InstrumentRepository @Inject constructor(private val dao: InstrumentDao) {
    fun observeAll(): Flow<List<Instrument>> = dao.observeAll()
    suspend fun getById(id: Long) = dao.getById(id)
    suspend fun insert(instrument: Instrument) = dao.insert(instrument)
    suspend fun update(instrument: Instrument) = dao.update(instrument)
    suspend fun delete(instrument: Instrument) = dao.delete(instrument)
}

@Singleton
class TrackRepository @Inject constructor(private val dao: TrackDao) {
    fun observeAll(): Flow<List<Track>> = dao.observeAll()
    suspend fun getAllOnce() = dao.getAllOnce()
    suspend fun insert(track: Track) = dao.insert(track)
    suspend fun update(track: Track) = dao.update(track)
    suspend fun delete(track: Track) = dao.delete(track)
    suspend fun deleteAll() = dao.deleteAll()
}

@Singleton
class SongTrackConfigRepository @Inject constructor(private val dao: SongTrackConfigDao) {
    suspend fun getForSong(songKey: String) = dao.getForSong(songKey)
    suspend fun upsertAll(configs: List<SongTrackConfig>) = dao.upsertAll(configs)
    suspend fun deleteForSong(songKey: String) = dao.deleteForSong(songKey)
}

@Singleton
class ScoreRepository @Inject constructor(private val dao: ScoreNoteDao) {
    fun observeAll(): Flow<List<ScoreNote>> = dao.observeAll()
    suspend fun getAllOnce() = dao.getAllOnce()
    suspend fun insert(note: ScoreNote) = dao.insert(note)
    suspend fun update(note: ScoreNote) = dao.update(note)
    suspend fun delete(note: ScoreNote) = dao.delete(note)
    suspend fun deleteById(id: Long) = dao.deleteById(id)
    suspend fun getByTrack(trackId: Long) = dao.getByTrack(trackId)
    suspend fun deleteByTrack(trackId: Long) = dao.deleteByTrack(trackId)
    suspend fun deleteAll() = dao.deleteAll()
}

@Singleton
class ProjectSettingsRepository @Inject constructor(private val dao: ProjectSettingsDao) {
    fun observe(): Flow<ProjectSettings?> = dao.observe()
    suspend fun upsert(settings: ProjectSettings) = dao.upsert(settings)
}
