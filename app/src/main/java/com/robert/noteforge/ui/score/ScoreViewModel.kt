package com.robert.noteforge.ui.score

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert.noteforge.audio.AudioEngine
import com.robert.noteforge.audio.Sequencer
import com.robert.noteforge.synth.patchFromJson
import org.json.JSONObject
import com.robert.noteforge.data.Instrument
import com.robert.noteforge.data.InstrumentRepository
import com.robert.noteforge.data.ProjectData
import com.robert.noteforge.data.ProjectFileCodec
import com.robert.noteforge.data.ProjectNoteData
import com.robert.noteforge.data.ProjectSettings
import com.robert.noteforge.data.ProjectSettingsData
import com.robert.noteforge.data.ProjectSettingsRepository
import com.robert.noteforge.data.ScoreNote
import com.robert.noteforge.data.ScoreRepository
import com.robert.noteforge.data.SongTrackConfig
import com.robert.noteforge.data.SongTrackConfigRepository
import com.robert.noteforge.data.Track
import com.robert.noteforge.data.TrackRepository
import com.robert.noteforge.util.Midi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class NoteDuration(val beats: Double, val label: String) {
    WHOLE(4.0, "1"), HALF(2.0, "1/2"), QUARTER(1.0, "1/4"), EIGHTH(0.5, "1/8"), SIXTEENTH(0.25, "1/16")
}

enum class ScoreTool(val label: String) {
    WRITE("Write"), ERASE("Erase"), SHARP("Sharp"), FLAT("Flat"), SELECT("Select")
}

enum class SnapLevel(val beats: Double, val label: String) {
    BAR(-1.0, "Bar"),
    HALF(2.0, "1/2"),
    QUARTER(1.0, "1/4"),
    EIGHTH(0.5, "1/8"),
    SIXTEENTH(0.25, "1/16"),
    THIRTY_SECOND(0.125, "1/32"),
    OFF(0.0, "Off")
}

data class ScoreUiState(
    val tracks: List<Track> = emptyList(),
    val notes: List<ScoreNote> = emptyList(),
    val instruments: List<Instrument> = emptyList(),
    val settings: ProjectSettings = ProjectSettings(),
    val selectedTrackId: Long? = null,
    val selectedDuration: NoteDuration = NoteDuration.QUARTER
)

@HiltViewModel
class ScoreViewModel @Inject constructor(
    private val trackRepository: TrackRepository,
    private val scoreRepository: ScoreRepository,
    private val instrumentRepository: InstrumentRepository,
    private val settingsRepository: ProjectSettingsRepository,
    private val songTrackConfigRepository: SongTrackConfigRepository,
    private val sequencer: Sequencer,
    private val audioEngine: AudioEngine,
    private val videoExporter: com.robert.noteforge.audio.VideoExporter
) : ViewModel() {
    /** Key of the currently loaded song (MIDI only), for per-song track configs. */
    private var currentSongKey: String? = null

    private val _sampleRate = MutableStateFlow(com.robert.noteforge.audio.QualityPrefs.SR_SMOOTH)
    val sampleRate: StateFlow<Int> = _sampleRate.asStateFlow()

    init {
        // Sync with the sequencer's restored quality.
        _sampleRate.value = com.robert.noteforge.audio.Sequencer.RENDER_SR
    }

    /** Sets playback render quality; takes effect on the next playback. */
    fun setQuality(sampleRate: Int, context: android.content.Context) {
        com.robert.noteforge.audio.QualityPrefs.setSampleRate(context, sampleRate)
        sequencer.setQuality(sampleRate)
        _sampleRate.value = sampleRate
    }

    // Render state comes from the foreground RenderService
    val renderProgress: StateFlow<com.robert.noteforge.audio.VideoExporter.Progress?> =
        com.robert.noteforge.audio.RenderService.progress
    val renderUri: StateFlow<android.net.Uri?> =
        com.robert.noteforge.audio.RenderService.resultUri
    val renderError: StateFlow<String?> =
        com.robert.noteforge.audio.RenderService.error
    val isRendering: Boolean
        get() = com.robert.noteforge.audio.RenderService.isRendering

    /**
     * Starts the foreground render service. Keeps running with screen off.
     */
    fun startVideoRender(context: android.content.Context) {
        if (isRendering) return
        val s = uiState.value
        if (s.notes.isEmpty()) return
        // Pass data via static holder (avoids Parcelable overhead)
        com.robert.noteforge.audio.RenderRequest.notes = s.notes
        com.robert.noteforge.audio.RenderRequest.tracks = s.tracks
        com.robert.noteforge.audio.RenderRequest.measureBeats = s.settings.beatsPerMeasure
        com.robert.noteforge.audio.RenderRequest.timeSigNum = s.settings.timeSigNum
        com.robert.noteforge.audio.RenderRequest.timeSigDen = s.settings.timeSigDen
        com.robert.noteforge.audio.RenderRequest.instrumentNames =
            s.instruments.associate { it.id to it.name }
        val intent = android.content.Intent(
            context,
            com.robert.noteforge.audio.RenderService::class.java
        ).apply {
            action = com.robert.noteforge.audio.RenderService.ACTION_START
            putExtra(
                com.robert.noteforge.audio.RenderService.EXTRA_TEMPO,
                s.settings.tempoBpm
            )
            putExtra(
                com.robert.noteforge.audio.RenderService.EXTRA_SONG_NAME,
                projectName.value ?: "score"
            )
        }
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun clearRenderState() {
        com.robert.noteforge.audio.RenderService.clearResult()
    }

    /**
     * Cancels an in-progress render by stopping the foreground service.
     */
    fun cancelRender(context: android.content.Context) {
        val intent = android.content.Intent(
            context,
            com.robert.noteforge.audio.RenderService::class.java
        ).apply {
            action = com.robert.noteforge.audio.RenderService.ACTION_CANCEL
        }
        context.startService(intent)
    }

    private val selectedTrackId = MutableStateFlow<Long?>(null)
    private val selectedDuration = MutableStateFlow(NoteDuration.QUARTER)

    private val _activeTool = MutableStateFlow(ScoreTool.WRITE)
    val activeTool: StateFlow<ScoreTool> = _activeTool.asStateFlow()

    private val _beamVersion = MutableStateFlow(0)
    val beamVersion: StateFlow<Int> = _beamVersion.asStateFlow()

    fun bumpBeamVersion() { _beamVersion.value += 1 }

    /** Save beam settings to song file (Bobby 2026-10-14). */
    fun saveBeamSettings(quantizeGrid: Double, ioiThreshold: Double, gapThreshold: Double) {
        viewModelScope.launch {
            val current = uiState.value.settings
            val updated = current.copy(
                beamQuantizeGrid = quantizeGrid,
                beamIoiThreshold = ioiThreshold,
                beamGapThreshold = gapThreshold
            )
            settingsRepository.upsert(updated)
        }
    }

    private val _selectedSnap = MutableStateFlow(SnapLevel.QUARTER)
    val selectedSnap: StateFlow<SnapLevel> = _selectedSnap.asStateFlow()


    private val _selectedNoteIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedNoteIds: StateFlow<Set<Long>> = _selectedNoteIds.asStateFlow()

    private val _propsNoteId = MutableStateFlow<Long?>(null)
    val propsNoteId: StateFlow<Long?> = _propsNoteId.asStateFlow()

    private data class ClipboardNote(
        val trackId: Long,
        val pitchMidi: Int,
        val startBeat: Double,
        val durationBeats: Double,
        val accidental: Int,
        val velocity: Float
    )

    private val _clipboard = mutableListOf<ClipboardNote>()
    private val _hasClipboard = MutableStateFlow(false)
    val hasClipboard: StateFlow<Boolean> = _hasClipboard.asStateFlow()

    private val _projectName = MutableStateFlow<String?>(null)
    val projectName: StateFlow<String?> = _projectName.asStateFlow()

    private var lastDragPreviewMs = 0L
    private var lastDragPreviewPitch = -1

    val isPlaying: StateFlow<Boolean> = sequencer.isPlaying
    val playheadBeat: StateFlow<Double> = sequencer.playheadBeat
    val playbackClock: StateFlow<Sequencer.PlaybackClock?> = sequencer.playbackClock
    val soundingNoteIds: StateFlow<Set<Long>> = sequencer.soundingIds

    val uiState: StateFlow<ScoreUiState> = combine(
        trackRepository.observeAll(),
        scoreRepository.observeAll(),
        instrumentRepository.observeAll(),
        settingsRepository.observe(),
        selectedTrackId,
        selectedDuration
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val tracks = values[0] as List<Track>
        @Suppress("UNCHECKED_CAST")
        val notes = values[1] as List<ScoreNote>
        @Suppress("UNCHECKED_CAST")
        val instruments = values[2] as List<Instrument>
        val settings = values[3] as ProjectSettings? ?: ProjectSettings()
        val selTrack = (values[4] as Long?) ?: tracks.firstOrNull()?.id
        val selDuration = values[5] as NoteDuration
        ScoreUiState(tracks, notes, instruments, settings, selTrack, selDuration)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ScoreUiState())

    fun deselectTrack() {
        selectedTrackId.value = null
        _selectedNoteIds.value = emptySet()
    }

    fun selectTrack(trackId: Long) {
        selectedTrackId.value = trackId
        // Selection is restricted to the current track only.
        val byId = uiState.value.notes.associateBy { it.id }
        _selectedNoteIds.value = _selectedNoteIds.value.filter { byId[it]?.trackId == trackId }.toSet()
    }
    fun selectDuration(duration: NoteDuration) { selectedDuration.value = duration }
    fun selectSnap(snap: SnapLevel) { _selectedSnap.value = snap }

    fun selectTool(tool: ScoreTool) { _activeTool.value = tool }

    fun addTrack(instrumentId: Long, name: String) {
        viewModelScope.launch {
            val order = trackRepository.getAllOnce().size
            val id = trackRepository.insert(Track(instrumentId = instrumentId, name = name, orderIndex = order))
            selectedTrackId.value = id
        }
    }

    fun toggleMute(track: Track) {
        val muted = !track.muted
        // Live: sounding voices stop immediately.
        sequencer.setTrackMuted(track.id, muted)
        viewModelScope.launch { trackRepository.update(track.copy(muted = muted)) }
        persistSongTrackConfigs()
    }

    fun toggleSolo(track: Track) {
        val solo = !track.solo
        // Live: re-evaluates all sounding voices.
        sequencer.setTrackSolo(track.id, solo)
        viewModelScope.launch { trackRepository.update(track.copy(solo = solo)) }
        persistSongTrackConfigs()
    }

    fun removeTrack(track: Track) {
        viewModelScope.launch { deleteTrackById(track.id) }
    }

    /** Full track removal: notes first, then the track, then tidy ordering. */
    private suspend fun deleteTrackById(trackId: Long) {
        scoreRepository.deleteByTrack(trackId)
        trackRepository.getAllOnce().find { it.id == trackId }?.let { trackRepository.delete(it) }
        _selectedNoteIds.value = emptySet()
        if (_propsNoteId.value != null &&
            uiState.value.notes.none { it.id == _propsNoteId.value }
        ) {
            _propsNoteId.value = null
        }
        trackRepository.getAllOnce().sortedBy { it.orderIndex }.forEachIndexed { index, track ->
            if (track.orderIndex != index) trackRepository.update(track.copy(orderIndex = index))
        }
        if (selectedTrackId.value == trackId) {
            selectedTrackId.value = trackRepository.getAllOnce().firstOrNull()?.id
        }
    }

    fun deleteTrack(trackId: Long) {
        viewModelScope.launch { deleteTrackById(trackId) }
    }

    /** Duplicates a track (with its notes) right after the original. */
    fun duplicateTrack(trackId: Long) {
        viewModelScope.launch {
            val tracks = trackRepository.getAllOnce().sortedBy { it.orderIndex }
            val orig = tracks.find { it.id == trackId } ?: return@launch
            val insertAt = orig.orderIndex + 1
            tracks.filter { it.orderIndex >= insertAt }.forEach {
                trackRepository.update(it.copy(orderIndex = it.orderIndex + 1))
            }
            val newId = trackRepository.insert(
                orig.copy(id = 0, name = "${orig.name} copy".take(64), orderIndex = insertAt)
            )
            scoreRepository.getByTrack(trackId).forEach { note ->
                scoreRepository.insert(note.copy(id = 0, trackId = newId))
            }
            selectTrack(newId)
        }
    }

    /** Moves a track up (toward the top) or down by swapping order indices. */
    fun moveTrack(trackId: Long, up: Boolean) {
        viewModelScope.launch {
            val tracks = trackRepository.getAllOnce().sortedBy { it.orderIndex }
            val i = tracks.indexOfFirst { it.id == trackId }
            val j = if (up) i - 1 else i + 1
            if (i < 0 || j !in tracks.indices) return@launch
            val a = tracks[i]
            val b = tracks[j]
            trackRepository.update(a.copy(orderIndex = b.orderIndex))
            trackRepository.update(b.copy(orderIndex = a.orderIndex))
        }
    }

    /** Plays a one-shot preview of an instrument's synth patch. */
    fun previewInstrument(instrument: Instrument) {
        val patch = instrument.synthPatchJson?.let {
            try { patchFromJson(JSONObject(it)) } catch (_: Exception) { null }
        } ?: return
        audioEngine.playSynthPreview(patch, instrument.rootNoteMidi, 1f)
    }

    fun setTrackInstrument(trackId: Long, instrumentId: Long) {
        // Live: future notes use the new instrument immediately.
        sequencer.setTrackInstrument(trackId, instrumentId)
        viewModelScope.launch {
            trackRepository.getAllOnce().find { it.id == trackId }?.let {
                trackRepository.update(it.copy(instrumentId = instrumentId))
            }
        }
        persistSongTrackConfigs()
    }

    fun setTrackNoteColor(trackId: Long, argb: Int?) {
        viewModelScope.launch {
            trackRepository.getAllOnce().find { it.id == trackId }?.let {
                if (it.noteColorArgb != argb) trackRepository.update(it.copy(noteColorArgb = argb))
            }
        }
        persistSongTrackConfigs()
    }

    fun setTrackVolume(trackId: Long, volume: Float) {
        val clean = volume.coerceIn(0f, 1f)
        // Live: sounding voices change immediately, no restart needed.
        sequencer.setTrackVolume(trackId, clean)
        viewModelScope.launch {
            trackRepository.getAllOnce().find { it.id == trackId }?.let {
                if (it.volume != clean) trackRepository.update(it.copy(volume = clean))
            }
        }
        persistSongTrackConfigs()
    }

    fun setTrackClef(trackId: Long, clefName: String) {
        viewModelScope.launch {
            trackRepository.getAllOnce().find { it.id == trackId }?.let {
                if (it.clef != clefName) trackRepository.update(it.copy(clef = clefName))
            }
        }
        persistSongTrackConfigs()
    }

    fun renameTrack(trackId: Long, name: String) {
        val clean = name.trim().take(64)
        if (clean.isEmpty()) return
        viewModelScope.launch {
            trackRepository.getAllOnce().find { it.id == trackId }?.let {
                if (it.name != clean) trackRepository.update(it.copy(name = clean))
            }
        }
    }

    fun placeNote(trackId: Long, pitchMidi: Int, startBeat: Double, durationBeats: Double) {
        val note = ScoreNote(
            trackId = trackId,
            pitchMidi = pitchMidi,
            startBeat = startBeat,
            durationBeats = durationBeats
        )
        viewModelScope.launch { scoreRepository.insert(note) }
        previewNoteAudio(note)
    }

    /** Auditions a pitch on a track's instrument (used while dragging). Throttled. */
    fun auditionPitch(trackId: Long, pitchMidi: Int) {
        val now = System.currentTimeMillis()
        if (pitchMidi == lastDragPreviewPitch && now - lastDragPreviewMs <= 200) return
        lastDragPreviewPitch = pitchMidi
        lastDragPreviewMs = now
        val snapshot = uiState.value
        val track = snapshot.tracks.find { it.id == trackId } ?: return
        val instrument = snapshot.instruments.find { it.id == track.instrumentId } ?: return
        val patch = instrument.synthPatchJson?.let {
            try { patchFromJson(JSONObject(it)) } catch (_: Exception) { null }
        } ?: return
        audioEngine.playSynthPreview(patch, pitchMidi, track.volume)
    }

    /** Commits a press-hold placed note to the score (already auditioned). */
    fun commitNote(trackId: Long, pitchMidi: Int, startBeat: Double, durationBeats: Double) {
        selectTrack(trackId)
        viewModelScope.launch {
            scoreRepository.insert(
                ScoreNote(
                    trackId = trackId,
                    pitchMidi = pitchMidi,
                    startBeat = startBeat,
                    durationBeats = durationBeats
                )
            )
        }
    }

    fun auditionNote(noteId: Long) {
        val note = uiState.value.notes.find { it.id == noteId } ?: return
        previewNoteAudio(note)
    }

    fun removeNote(note: ScoreNote) {
        _selectedNoteIds.value -= note.id
        if (_propsNoteId.value == note.id) _propsNoteId.value = null
        viewModelScope.launch { scoreRepository.deleteById(note.id) }
    }

    fun deleteSelectedNotes() {
        val ids = _selectedNoteIds.value
        if (ids.isEmpty()) return
        if (_propsNoteId.value in ids) _propsNoteId.value = null
        _selectedNoteIds.value = emptySet()
        viewModelScope.launch { ids.forEach { scoreRepository.deleteById(it) } }
    }

    fun clearNoteSelection() {
        _selectedNoteIds.value = emptySet()
    }

    /** Copies the current selection into the in-memory clipboard (kept selected). */
    fun copySelectedNotes() {
        val ids = _selectedNoteIds.value
        if (ids.isEmpty()) return
        _clipboard.clear()
        uiState.value.notes.filter { it.id in ids }.forEach { note ->
            _clipboard.add(
                ClipboardNote(
                    trackId = note.trackId,
                    pitchMidi = note.pitchMidi,
                    startBeat = note.startBeat,
                    durationBeats = note.durationBeats,
                    accidental = note.accidental,
                    velocity = note.velocity
                )
            )
        }
        _hasClipboard.value = _clipboard.isNotEmpty()
    }

    /** Cuts the current selection into the clipboard (copies, then deletes). */
    fun cutSelectedNotes() {
        val ids = _selectedNoteIds.value
        if (ids.isEmpty()) return
        copySelectedNotes()
        if (_propsNoteId.value in ids) _propsNoteId.value = null
        _selectedNoteIds.value = emptySet()
        viewModelScope.launch { ids.forEach { scoreRepository.deleteById(it) } }
    }

    /**
     * Pastes the clipboard into the selected track at the current playhead
     * position: the earliest copied note lands exactly on the playhead, the
     * rest keep their relative offsets and pitches. Pasted notes become
     * the new selection.
     */
    fun pasteClipboard() {
        if (_clipboard.isEmpty()) return
        val snapshot = uiState.value
        val targetTrack = snapshot.selectedTrackId ?: snapshot.tracks.firstOrNull()?.id ?: return
        val minBeat = _clipboard.minOf { it.startBeat }
        val anchor = sequencer.playheadBeat.value.coerceAtLeast(0.0)
        val delta = anchor - minBeat
        viewModelScope.launch {
            val newIds = mutableSetOf<Long>()
            _clipboard.forEach { clip ->
                val id = scoreRepository.insert(
                    ScoreNote(
                        trackId = targetTrack,
                        pitchMidi = clip.pitchMidi,
                        startBeat = (clip.startBeat + delta).coerceAtLeast(0.0),
                        durationBeats = clip.durationBeats,
                        accidental = clip.accidental,
                        velocity = clip.velocity
                    )
                )
                newIds.add(id)
            }
            _selectedNoteIds.value = newIds
        }
    }

    /** Adds note IDs to the edit selection (e.g. from box-select). */
    fun selectNotes(ids: Set<Long>) {
        if (ids.isEmpty()) return
        _selectedNoteIds.value += ids
    }

    fun toggleNoteSelection(note: ScoreNote) {
        if (note.trackId != selectedTrackId.value) selectTrack(note.trackId)
        _selectedNoteIds.value =
            if (note.id in _selectedNoteIds.value) _selectedNoteIds.value - note.id
            else _selectedNoteIds.value + note.id
    }

    /**
     * Box-select: focuses the boxed lane's track and adds its notes. Notes
     * from any other track are never retained (single-track selection).
     */
    fun selectNotes(trackId: Long, ids: Set<Long>) {
        if (ids.isEmpty()) return
        val byId = uiState.value.notes.associateBy { it.id }
        val valid = ids.filter { byId[it]?.trackId == trackId }.toSet()
        if (valid.isEmpty()) return
        if (trackId != selectedTrackId.value) {
            selectedTrackId.value = trackId
            _selectedNoteIds.value = valid
        } else {
            _selectedNoteIds.value += valid
        }
    }

    /**
     * Long-press on a note adds it to the edit selection; long-pressing a
     * note that is already selected opens its properties dialog instead.
     * Long-pressing empty space clears the selection.
     */
    fun onNoteLongPressed(note: ScoreNote?) {
        if (note == null) {
            clearNoteSelection()
            return
        }
        if (note.trackId != selectedTrackId.value) selectTrack(note.trackId)
        if (note.id in _selectedNoteIds.value) {
            _propsNoteId.value = note.id
        } else {
            _selectedNoteIds.value += note.id
        }
    }

    fun closeNoteProps() { _propsNoteId.value = null }

    fun setNoteDuration(noteId: Long, durationBeats: Double) {
        val note = uiState.value.notes.find { it.id == noteId } ?: return
        viewModelScope.launch {
            scoreRepository.update(note.copy(durationBeats = durationBeats))
        }
    }

    /** Raises/lowers a note by a semitone, keeping sharp/flat spelling from the tool used. */
    fun transpose(note: ScoreNote, semitones: Int) {
        val newPitch = (note.pitchMidi + semitones).coerceIn(0, 127)
        if (newPitch == note.pitchMidi) return
        val spelling = if (Midi.isSharp(newPitch)) semitones.coerceIn(-1, 1) else 0
        val updated = note.copy(pitchMidi = newPitch, accidental = spelling)
        viewModelScope.launch {
            scoreRepository.update(updated)
        }
        previewNoteAudio(updated)
    }

    /** Tool-aware tap on a notehead. Tapping focuses the note's track first. */
    fun onNoteTapped(note: ScoreNote) {
        if (note.trackId != selectedTrackId.value) selectTrack(note.trackId)
        when (_activeTool.value) {
            ScoreTool.WRITE -> toggleNoteSelection(note)
            ScoreTool.SELECT -> toggleNoteSelection(note)
            ScoreTool.ERASE -> removeNote(note)
            ScoreTool.SHARP -> {
                transpose(note, +1)
                selectOnly(note)
            }
            ScoreTool.FLAT -> {
                transpose(note, -1)
                selectOnly(note)
            }
        }
    }

    private fun selectOnly(note: ScoreNote) {
        if (note.id !in _selectedNoteIds.value) _selectedNoteIds.value += note.id
    }

    /** Tool-aware tap on empty staff space. */
    fun onEmptyTapped(trackId: Long, startBeat: Double, pitchMidi: Int) {
        if (_activeTool.value != ScoreTool.WRITE) return
        selectTrack(trackId)
        placeNote(trackId, pitchMidi, startBeat, selectedDuration.value.beats)
    }

    /**
     * Live drag-move of a note within its lane. Dragged notes snap back to
     * natural spelling (staff positions are naturals). Auditions the new
     * pitch, throttled so a fast drag doesn't spam the audio engine.
     */
    fun moveNoteTo(noteId: Long, startBeat: Double, pitchMidi: Int) {
        val note = uiState.value.notes.find { it.id == noteId } ?: return
        val beat = startBeat.coerceAtLeast(0.0)
        val pitch = pitchMidi.coerceIn(0, 127)
        if (beat == note.startBeat && pitch == note.pitchMidi) return
        val updated = note.copy(startBeat = beat, pitchMidi = pitch, accidental = 0)
        viewModelScope.launch { scoreRepository.update(updated) }
        val now = System.currentTimeMillis()
        if (pitch != lastDragPreviewPitch || now - lastDragPreviewMs > 200) {
            lastDragPreviewPitch = pitch
            lastDragPreviewMs = now
            previewNoteAudio(updated)
        }
    }

    private fun previewNoteAudio(note: ScoreNote) {
        val snapshot = uiState.value
        val track = snapshot.tracks.find { it.id == note.trackId } ?: return
        val instrument = snapshot.instruments.find { it.id == track.instrumentId } ?: return
        val patch = instrument.synthPatchJson?.let {
            try { patchFromJson(JSONObject(it)) } catch (_: Exception) { null }
        } ?: return
        audioEngine.playSynthPreview(patch, note.pitchMidi, track.volume)
    }

    fun setTempo(bpm: Int) {
        viewModelScope.launch {
            val current = uiState.value.settings
            settingsRepository.upsert(current.copy(tempoBpm = bpm))
        }
    }

    /** Global time signature (next to tempo): the numerator sets beats per
     * measure for barlines, the denominator is display-only. */
    fun setTimeSignature(num: Int, den: Int) {
        val cleanNum = num.coerceIn(1, 12)
        val cleanDen = den.coerceIn(2, 16)
        viewModelScope.launch {
            val current = uiState.value.settings
            settingsRepository.upsert(
                current.copy(
                    beatsPerMeasure = cleanNum,
                    timeSigNum = cleanNum,
                    timeSigDen = cleanDen
                )
            )
        }
    }

    fun togglePlayPause() {
        val state = uiState.value
        if (sequencer.isPlaying.value) {
            sequencer.pause()
            return
        }
        val from = sequencer.playheadBeat.value
        val end = state.notes.maxOfOrNull { it.startBeat + it.durationBeats } ?: 0.0
        val start = if (end > 0 && from >= end) 0.0 else from
        sequencer.play(state.notes, state.tracks, state.settings.tempoBpm, start)
    }

    fun rewindOneMeasure() {
        val state = uiState.value
        val target = (sequencer.playheadBeat.value - state.settings.beatsPerMeasure).coerceAtLeast(0.0)
        if (sequencer.isPlaying.value) {
            sequencer.play(state.notes, state.tracks, state.settings.tempoBpm, target)
        } else {
            sequencer.seek(target)
        }
    }

    fun rewindToStart() {
        val state = uiState.value
        if (sequencer.isPlaying.value) {
            sequencer.play(state.notes, state.tracks, state.settings.tempoBpm, 0.0)
        } else {
            sequencer.seek(0.0)
        }
    }

    /**
     * Moves the playhead (e.g. from a tap on the time strip). While playing,
     * playback restarts from the new position so the move is audible
     * immediately; while stopped it just repositions the marker.
     */
    fun seekTo(beat: Double) {
        val target = beat.coerceAtLeast(0.0)
        if (sequencer.isPlaying.value) {
            val state = uiState.value
            sequencer.play(state.notes, state.tracks, state.settings.tempoBpm, target)
        } else {
            sequencer.seek(target)
        }
    }

    // ---- File operations (project save/open/new/close/import) ----

    fun setProjectName(name: String?) {
        _projectName.value = name?.take(64)?.ifEmpty { null }
    }

    /** Serializes the current score (tracks, notes, settings) to JSON. */
    fun exportProjectJson(): String {
        val snapshot = uiState.value
        val orderedTracks = snapshot.tracks.sortedBy { it.orderIndex }
        val indexById = orderedTracks.mapIndexed { index, track -> track.id to index }.toMap()
        return ProjectFileCodec.encode(
            ProjectData(
                settings = ProjectSettingsData(
                    tempoBpm = snapshot.settings.tempoBpm,
                    beatsPerMeasure = snapshot.settings.beatsPerMeasure,
                    timeSigNum = snapshot.settings.timeSigNum,
                    timeSigDen = snapshot.settings.timeSigDen
                ),
                tracks = orderedTracks.map { track ->
                    com.robert.noteforge.data.ProjectTrackData(
                        instrumentId = track.instrumentId,
                        name = track.name,
                        orderIndex = track.orderIndex,
                        muted = track.muted,
                        solo = track.solo,
                        noteColorArgb = track.noteColorArgb,
                        volume = track.volume,
                        clef = track.clef
                    )
                },
                notes = snapshot.notes.mapNotNull { note ->
                    val idx = indexById[note.trackId] ?: return@mapNotNull null
                    ProjectNoteData(
                        trackIndex = idx,
                        pitchMidi = note.pitchMidi,
                        startBeat = note.startBeat,
                        durationBeats = note.durationBeats,
                        velocity = note.velocity,
                        accidental = note.accidental
                    )
                }
            )
        )
    }

    /**
     * Replaces the current score with [data] (Open). Tracks are re-inserted
     * so row IDs are fresh; notes are remapped by track index. Tracks whose
     * instrument no longer exists fall back to the first instrument.
     * Throws IllegalStateException when there is no instrument to map to.
     */
    /** Saves the current tracks' configs under the current song key. */
    private suspend fun saveSongTrackConfigs() {
        val key = currentSongKey ?: return
        val tracks = trackRepository.getAllOnce().sortedBy { it.orderIndex }
        if (tracks.isEmpty()) return
        songTrackConfigRepository.upsertAll(
            tracks.map { t ->
                SongTrackConfig(
                    songKey = key,
                    trackIndex = t.orderIndex,
                    instrumentId = t.instrumentId,
                    volume = t.volume,
                    muted = t.muted,
                    solo = t.solo,
                    clef = t.clef,
                    noteColorArgb = t.noteColorArgb
                )
            }
        )
    }

    /** Persists configs in the background after a track setting changes. */
    private fun persistSongTrackConfigs() {
        if (currentSongKey == null) return
        viewModelScope.launch {
            try { saveSongTrackConfigs() } catch (_: Exception) {}
        }
    }

    suspend fun replaceProject(data: ProjectData, songKey: String? = null) {
        val instruments = uiState.value.instruments
        if (instruments.isEmpty() && data.tracks.isNotEmpty()) {
            throw IllegalStateException("Create an instrument first, then open the project.")
        }
        val fallbackId = instruments.firstOrNull()?.id ?: -1L
        val validIds = instruments.map { it.id }.toSet()
        if (sequencer.isPlaying.value) sequencer.stop() else sequencer.seek(0.0)
        // Remember the outgoing song's track configs before wiping.
        try { saveSongTrackConfigs() } catch (_: Exception) {}
        scoreRepository.deleteAll()
        trackRepository.deleteAll()
        _selectedNoteIds.value = emptySet()
        _propsNoteId.value = null
        val newIds = mutableListOf<Long>()
        // Drum kit instrument: for the "Drums" track from MIDI channel 10.
        // The kit maps GM pitches to drum patches; the sequencer does
        // pitch lookup during playback. Created once and reused.
        var drumKitInstrumentId: Long? = null
        suspend fun drumKitInstrument(): Long {
            drumKitInstrumentId?.let { return it }
            // Check if we already have a drum kit instrument
            val existing = instruments.firstOrNull { inst ->
                try {
                    val json = org.json.JSONObject(inst.synthPatchJson ?: "")
                    json.optBoolean("isDrumKit", false)
                } catch (_: Exception) { false }
            }
            if (existing != null) {
                drumKitInstrumentId = existing.id
                return existing.id
            }
            // Create a new drum kit instrument (empty kit; user assigns in Drums > Kit tab)
            val kitJson = org.json.JSONObject().apply {
                put("isDrumKit", true)
            }.toString()
            val newInst = com.robert.noteforge.data.Instrument(
                name = "Drum Kit",
                audioFileId = -1,
                synthPatchJson = kitJson,
                syncUid = java.util.UUID.randomUUID().toString(),
                updatedAt = System.currentTimeMillis()
            )
            val id = instrumentRepository.insert(newInst)
            drumKitInstrumentId = id
            return id
        }
        // For MIDI: apply remembered per-song configs if we've seen this song before.
        val remembered = if (songKey != null) {
            try { songTrackConfigRepository.getForSong(songKey) } catch (_: Exception) { emptyList() }
        } else emptyList()
        val rememberedByIndex = remembered.associateBy { it.trackIndex }
        data.tracks.sortedBy { it.orderIndex }.forEachIndexed { index, t ->
            val cfg = rememberedByIndex[index]
            val cfgInstrumentOk = cfg != null && cfg.instrumentId in validIds
            // Drum kit track gets the drum kit instrument (auto-created if needed)
            val isDrumKitTrack = t.drumType == "KIT"
            val id = trackRepository.insert(
                Track(
                    instrumentId = when {
                        cfgInstrumentOk -> cfg.instrumentId
                        isDrumKitTrack -> drumKitInstrument()
                        t.instrumentId in validIds -> t.instrumentId
                        else -> fallbackId
                    },
                    name = t.name,
                    orderIndex = index,
                    muted = cfg?.muted ?: t.muted,
                    solo = cfg?.solo ?: t.solo,
                    noteColorArgb = cfg?.noteColorArgb ?: t.noteColorArgb,
                    volume = cfg?.volume ?: t.volume,
                    clef = cfg?.clef ?: t.clef
                )
            )
            newIds.add(id)
        }
        currentSongKey = songKey
        data.notes.forEach { n ->
            val trackId = newIds.getOrNull(n.trackIndex) ?: return@forEach
            scoreRepository.insert(
                ScoreNote(
                    trackId = trackId,
                    pitchMidi = n.pitchMidi,
                    startBeat = n.startBeat,
                    durationBeats = n.durationBeats,
                    velocity = n.velocity,
                    accidental = n.accidental
                )
            )
        }
        settingsRepository.upsert(
            ProjectSettings(
                tempoBpm = data.settings.tempoBpm,
                beatsPerMeasure = data.settings.beatsPerMeasure,
                timeSigNum = data.settings.timeSigNum,
                timeSigDen = data.settings.timeSigDen
            )
        )
        selectedTrackId.value = newIds.firstOrNull()
    }

    /**
     * Merges [data] into the current score (Import). Existing content is
     * kept; imported tracks are appended. Settings are left untouched.
     */
    suspend fun mergeProject(data: ProjectData) {
        val instruments = uiState.value.instruments
        if (instruments.isEmpty() && data.tracks.isNotEmpty()) {
            throw IllegalStateException("Create an instrument first, then import.")
        }
        val fallbackId = instruments.firstOrNull()?.id ?: -1L
        val validIds = instruments.map { it.id }.toSet()
        val orderBase = trackRepository.getAllOnce().size
        val newIds = mutableListOf<Long>()
        data.tracks.sortedBy { it.orderIndex }.forEachIndexed { index, t ->
            val id = trackRepository.insert(
                Track(
                    instrumentId = if (t.instrumentId in validIds) t.instrumentId else fallbackId,
                    name = t.name,
                    orderIndex = orderBase + index,
                    muted = t.muted,
                    solo = t.solo,
                    noteColorArgb = t.noteColorArgb,
                    volume = t.volume,
                    clef = t.clef
                )
            )
            newIds.add(id)
        }
        data.notes.forEach { n ->
            val trackId = newIds.getOrNull(n.trackIndex) ?: return@forEach
            scoreRepository.insert(
                ScoreNote(
                    trackId = trackId,
                    pitchMidi = n.pitchMidi,
                    startBeat = n.startBeat,
                    durationBeats = n.durationBeats,
                    velocity = n.velocity,
                    accidental = n.accidental
                )
            )
        }
        if (selectedTrackId.value == null) selectedTrackId.value = newIds.firstOrNull()
    }

    /** Clears the score to an empty project (New). Instruments are kept. */
    suspend fun newProject() {
        if (sequencer.isPlaying.value) sequencer.stop() else sequencer.seek(0.0)
        scoreRepository.deleteAll()
        trackRepository.deleteAll()
        _selectedNoteIds.value = emptySet()
        _propsNoteId.value = null
        selectedTrackId.value = null
        settingsRepository.upsert(ProjectSettings())
        _projectName.value = null
    }

    /** Closes the current view: stops playback, parks the playhead, clears selection. */
    fun closeProject() {
        sequencer.stop()
        _selectedNoteIds.value = emptySet()
        _propsNoteId.value = null
    }
}
