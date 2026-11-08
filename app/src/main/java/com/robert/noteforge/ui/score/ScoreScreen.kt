package com.robert.noteforge.ui.score

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.robert.noteforge.data.Clef
import com.robert.noteforge.data.Instrument
import com.robert.noteforge.data.ScoreNote
import com.robert.noteforge.data.Track
import com.robert.noteforge.ui.theme.TrackColors
import com.robert.noteforge.util.Midi
import kotlin.math.ceil

@Composable
fun ScoreScreen(modifier: Modifier = Modifier, viewModel: ScoreViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState()
    val playbackClock by viewModel.playbackClock.collectAsState()
    // Playhead drawn independently every frame from the wall clock.
    // No timer granularity, no jitter from StateFlow emission timing.
    var smoothPlayheadBeat by remember { mutableStateOf(0.0) }
    LaunchedEffect(playbackClock) {
        val clock = playbackClock
        if (clock != null) {
            while (true) {
                withFrameNanos {
                    val e = System.currentTimeMillis() - clock.startTimeMillis
                    smoothPlayheadBeat =
                        (clock.fromBeat + e / clock.msPerBeat).coerceAtMost(clock.endBeat)
                }
            }
        }
    }
    // Fall back to the sequencer's beat when not playing (seek/pause).
    val playheadBeat = if (playbackClock != null) smoothPlayheadBeat
        else viewModel.playheadBeat.collectAsState().value
    val activeTool by viewModel.activeTool.collectAsState()
    val selectedSnap by viewModel.selectedSnap.collectAsState()
    val beamVersion by viewModel.beamVersion.collectAsState()
    var showBeamTune by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var quantizeGrid by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.25f) }
    var ioiThreshold by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.75f) }
    var gapTolerance by androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
    // Load beam settings from song file (Bobby 2026-10-14)
    androidx.compose.runtime.LaunchedEffect(state.settings) {
        val s = state.settings
        com.robert.noteforge.ui.score.BeamTune.quantizeGrid = s.beamQuantizeGrid
        com.robert.noteforge.ui.score.BeamTune.ioiThreshold = s.beamIoiThreshold
        com.robert.noteforge.ui.score.BeamTune.restThreshold = s.beamGapThreshold
        com.robert.noteforge.ui.score.BeamTune.minDuration = s.beamGapThreshold * 0.5
        quantizeGrid = s.beamQuantizeGrid.toFloat()
        ioiThreshold = s.beamIoiThreshold.toFloat()
        gapTolerance = s.beamGapThreshold.toFloat()
        viewModel.bumpBeamVersion()
    }

    // Auto-scroll to beam controls when they appear (Bobby 2026-10-14).
    val selectedNoteIds by viewModel.selectedNoteIds.collectAsState()
    val hasClipboard by viewModel.hasClipboard.collectAsState()
    val soundingNoteIds by viewModel.soundingNoteIds.collectAsState()
    val propsNoteId by viewModel.propsNoteId.collectAsState()
    val projectName by viewModel.projectName.collectAsState()
    var showAddTrackDialog by remember { mutableStateOf(false) }
    var showTimeSigDialog by remember { mutableStateOf(false) }
    var menuTrackId by remember { mutableStateOf<Long?>(null) }

    val snapBeats = if (selectedSnap == SnapLevel.BAR) {
        state.settings.beatsPerMeasure.toDouble()
    } else {
        selectedSnap.beats
    }
    val endBeat = state.notes.maxOfOrNull { it.startBeat + it.durationBeats } ?: 0.0
    // Grow the staff past the last note so long songs stay visible and the
    // playhead can scroll past the old fixed 32-measure limit. A few extra
    // empty measures are kept for placing notes beyond the current end.
    val measureBeats = state.settings.beatsPerMeasure.coerceAtLeast(1)
    val measureCount = maxOf(32, ceil(endBeat / measureBeats).toInt() + 4)

    Scaffold(
        modifier = modifier,
        topBar = {
            // Compact 40dp title bar (default TopAppBar is 64dp).
            Row(
                modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    projectName
                        ?.removeSuffix(".json")
                        ?.removeSuffix(".mid")
                        ?.removeSuffix(".midi")
                        ?.ifEmpty { null }
                        ?: "Untitled",
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Text(
                    "${formatSongTime(playheadBeat, state.settings.tempoBpm)} / " +
                        formatSongTime(endBeat, state.settings.tempoBpm),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            // Compact transport + tempo (36dp buttons, no extra padding).
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.rewindToStart() }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.SkipPrevious,
                            contentDescription = "Rewind to start",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { viewModel.rewindOneMeasure() }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Filled.FastRewind,
                            contentDescription = "Back one measure",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = { viewModel.togglePlayPause() }, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { showTimeSigDialog = true },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("${state.settings.timeSigNum}/${state.settings.timeSigDen}")
                    }
                    TextButton(
                        onClick = { viewModel.setTempo((state.settings.tempoBpm - 5).coerceAtLeast(20)) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("–")
                    }
                    Text(
                        "${state.settings.tempoBpm} BPM",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                    TextButton(
                        onClick = { viewModel.setTempo((state.settings.tempoBpm + 5).coerceAtMost(300)) },
                        modifier = Modifier.height(32.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("+")
                    }
                }
            }

            // Track chips
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(state.tracks, key = { it.id }) { track ->
                        val idx = state.tracks.indexOf(track).coerceAtLeast(0)
                        TrackChip(
                            track = track,
                            color = track.noteColorArgb?.let { Color(it) }
                                ?: TrackColors[idx % TrackColors.size],
                            selected = track.id == state.selectedTrackId,
                            onSelect = { viewModel.selectTrack(track.id) },
                            onToggleMute = { viewModel.toggleMute(track) },
                            onToggleSolo = { viewModel.toggleSolo(track) }
                        )
                    }
                }
                IconButton(onClick = { showAddTrackDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add track")
                }
            }

            // Compact tools panel: durations + tool icons share one
            // horizontally scrolling row, snap sits inline below it.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 112.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ChoiceRow(
                        options = NoteDuration.entries,
                        selected = state.selectedDuration,
                        onSelect = { viewModel.selectDuration(it) },
                        buttonHeight = 40.dp
                    ) { duration ->
                        NoteGlyph(
                            duration = duration,
                            selected = state.selectedDuration == duration
                        )
                    }
                    ToolIconRow(
                        activeTool = activeTool,
                        onSelect = { viewModel.selectTool(it) }
                    )
                }

                // Snap-to grid selector
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Snap",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    ChoiceRow(
                        options = SnapLevel.entries,
                        selected = selectedSnap,
                        onSelect = { viewModel.selectSnap(it) },
                        modifier = Modifier
                            .weight(1f)
                            .horizontalScroll(rememberScrollState()),
                        buttonHeight = 40.dp
                    ) { snap ->
                        Text(
                            snap.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1
                        )
                }
                    androidx.compose.material3.TextButton(onClick = { showBeamTune = !showBeamTune }) { Text(if (showBeamTune) "Hide" else "Beam") }
                }
                if (showBeamTune) {
                    androidx.compose.material3.Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        androidx.compose.foundation.layout.Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text("Quantize: %.3f".format(quantizeGrid), style = MaterialTheme.typography.labelSmall)
                            androidx.compose.material3.Slider(
                                value = quantizeGrid,
                                onValueChange = {
                                    quantizeGrid = it
                                    com.robert.noteforge.ui.score.BeamTune.quantizeGrid = it.toDouble()
                                    viewModel.bumpBeamVersion()
                                },
                                onValueChangeFinished = {
                                    viewModel.saveBeamSettings(
                                        quantizeGrid.toDouble(),
                                        ioiThreshold.toDouble(),
                                        gapTolerance.toDouble()
                                    )
                                },
                                valueRange = 0.125f..0.5f
                            )
                            Text("IOI: %.2f".format(ioiThreshold), style = MaterialTheme.typography.labelSmall)
                            androidx.compose.material3.Slider(
                                value = ioiThreshold,
                                onValueChange = {
                                    ioiThreshold = it
                                    com.robert.noteforge.ui.score.BeamTune.ioiThreshold = it.toDouble()
                                    viewModel.bumpBeamVersion()
                                },
                                onValueChangeFinished = {
                                    viewModel.saveBeamSettings(
                                        quantizeGrid.toDouble(),
                                        ioiThreshold.toDouble(),
                                        gapTolerance.toDouble()
                                    )
                                },
                                valueRange = 0.25f..1.0f
                            )
                            Text("Gap: %.2f".format(gapTolerance), style = MaterialTheme.typography.labelSmall)
                            androidx.compose.material3.Slider(
                                value = gapTolerance,
                                onValueChange = {
                                    gapTolerance = it
                                    com.robert.noteforge.ui.score.BeamTune.restThreshold = it.toDouble()
                                    com.robert.noteforge.ui.score.BeamTune.minDuration = (it * 0.5).toDouble()
                                    viewModel.bumpBeamVersion()
                                },
                                onValueChangeFinished = {
                                    viewModel.saveBeamSettings(
                                        quantizeGrid.toDouble(),
                                        ioiThreshold.toDouble(),
                                        gapTolerance.toDouble()
                                    )
                                },
                                valueRange = 0.0f..1.0f
                            )

                        }
                    }
                }
                Text(
                    when (activeTool) {
                        ScoreTool.WRITE -> "Tap note to select, empty to place, hold+drag to move."
                        ScoreTool.SELECT -> "Drag a box to select notes, tap a note to toggle it."
                        ScoreTool.ERASE -> "Tap a note to delete it."
                        ScoreTool.SHARP -> "Tap a note to sharpen it (+1 semitone)."
                        ScoreTool.FLAT -> "Tap a note to flatten it (−1 semitone)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            // Edit-selection banner
            if (selectedNoteIds.isNotEmpty() || hasClipboard) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (selectedNoteIds.isNotEmpty()) "${selectedNoteIds.size} selected"
                        else "Clipboard ready",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectedNoteIds.isNotEmpty()) {
                            TextButton(onClick = { viewModel.copySelectedNotes() }) { Text("Copy") }
                            TextButton(onClick = { viewModel.cutSelectedNotes() }) { Text("Cut") }
                        }
                        TextButton(
                            onClick = { viewModel.pasteClipboard() },
                            enabled = hasClipboard
                        ) { Text("Paste") }
                        if (selectedNoteIds.isNotEmpty()) {
                            TextButton(onClick = { viewModel.deleteSelectedNotes() }) { Text("Delete") }
                            TextButton(onClick = { viewModel.clearNoteSelection() }) { Text("Clear") }
                        }
                    }
                }
            }

            if (state.tracks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Add a track to start composing.\nEach track plays through one of your instruments.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                MultiStaffView(
                    notes = state.notes,
                    tracks = state.tracks,
                    instrumentNames = state.instruments.associate { it.id to it.name },
                    selectedTrackId = state.selectedTrackId,
                    selectedNoteIds = selectedNoteIds,
                    soundingNoteIds = soundingNoteIds,
                    snapBeats = snapBeats,
                    measureBeats = state.settings.beatsPerMeasure,
                    timeSigNum = state.settings.timeSigNum,
                    timeSigDen = state.settings.timeSigDen,
                    playheadBeat = playheadBeat,
                    isPlaying = isPlaying,
                    measureCount = measureCount,
                    dragEnabled = activeTool == ScoreTool.WRITE,
                    boxSelectEnabled = activeTool == ScoreTool.SELECT,
                    onSelectTrack = { viewModel.selectTrack(it) },
                    onDeselectTrack = { viewModel.deselectTrack() },
                    onToggleSolo = { viewModel.toggleSolo(it) },
                    onTapNote = { viewModel.onNoteTapped(it) },
                    onLongPressNote = { viewModel.onNoteLongPressed(it) },
                    onBoxSelect = { trackId, ids -> viewModel.selectNotes(trackId, ids) },
                    onTrackMenu = { menuTrackId = it.id },
                    onDragNote = { id, beat, pitch -> viewModel.moveNoteTo(id, beat, pitch) },
                    onGhostPreview = { trackId, pitch -> viewModel.auditionPitch(trackId, pitch) },
                    onGhostCommit = { trackId, beat, pitch ->
                        viewModel.commitNote(trackId, pitch, beat, state.selectedDuration.beats)
                    },
                    onTapEmpty = { trackId, beat, pitch ->
                        if (activeTool == ScoreTool.SELECT) viewModel.clearNoteSelection()
                        else viewModel.onEmptyTapped(trackId, beat, pitch)
                    },
                    onSeekPlayhead = { viewModel.seekTo(it) },
                    beamVersion = beamVersion,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }

    if (showTimeSigDialog) {
        TimeSigDialog(
            num = state.settings.timeSigNum,
            den = state.settings.timeSigDen,
            onSelect = { num, den -> viewModel.setTimeSignature(num, den) },
            onDismiss = { showTimeSigDialog = false }
        )
    }

    if (showAddTrackDialog) {
        AddTrackDialog(
            instruments = state.instruments,
            onDismiss = { showAddTrackDialog = false },
            onAdd = { instrument ->
                viewModel.addTrack(instrument.id, instrument.name)
                showAddTrackDialog = false
            }
        )
    }

    menuTrackId?.let { id ->
        state.tracks.find { it.id == id }?.let { track ->
            val idx = state.tracks.indexOf(track)
            TrackMenuDialog(
                track = track,
                instruments = state.instruments,
                canMoveUp = idx > 0,
                canMoveDown = idx < state.tracks.size - 1,
                onDismiss = { menuTrackId = null },
                onRename = { viewModel.renameTrack(track.id, it) },
                autoColor = track.noteColorArgb?.let { Color(it) }
                    ?: TrackColors[idx % TrackColors.size],
                onNoteColor = { viewModel.setTrackNoteColor(track.id, it) },
                onVolume = { viewModel.setTrackVolume(track.id, it) },
                onClef = { viewModel.setTrackClef(track.id, it.name) },
                onDuplicate = { viewModel.duplicateTrack(track.id); menuTrackId = null },
                onDelete = { viewModel.deleteTrack(track.id); menuTrackId = null },
                onMoveUp = { viewModel.moveTrack(track.id, up = true) },
                onMoveDown = { viewModel.moveTrack(track.id, up = false) },
                onInstrument = {
                    viewModel.setTrackInstrument(track.id, it.id)
                    viewModel.previewInstrument(it)
                }
            )
        }
    }

    propsNoteId?.let { id ->
        state.notes.find { it.id == id }?.let { note ->
            val trackName = state.tracks.find { it.id == note.trackId }?.name ?: "Unknown track"
            NotePropsDialog(
                note = note,
                trackName = trackName,
                onDismiss = { viewModel.closeNoteProps() },
                onApply = { beats ->
                    viewModel.setNoteDuration(note.id, beats)
                    viewModel.closeNoteProps()
                }
            )
        }
    }
}

/** High-precision song time: beats at the given tempo as M:SS.mmm. */
private fun formatSongTime(beats: Double, tempoBpm: Int): String {
    val totalSec = (beats * 60.0 / tempoBpm.coerceAtLeast(1)).coerceAtLeast(0.0)
    val minutes = (totalSec / 60).toInt()
    val seconds = (totalSec % 60).toInt()
    val millis = ((totalSec * 1000) % 1000).toInt()
    return "%d:%02d.%03d".format(minutes, seconds, millis)
}

@Composable
private fun NotePropsDialog(
    note: ScoreNote,
    trackName: String,
    onDismiss: () -> Unit,
    onApply: (durationBeats: Double) -> Unit
) {
    var beats by remember(note.id) { mutableFloatStateOf(note.durationBeats.toFloat()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note properties") },
        text = {
            Column {
                Text(
                    "${Midi.name(note.pitchMidi)} • beat ${"%.2f".format(note.startBeat)}",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    trackName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Length: ${"%.2f".format(beats)} beats",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                ChoiceRow(
                    options = NoteDuration.entries,
                    selected = NoteDuration.entries.find { it.beats.toFloat() == beats },
                    onSelect = { beats = it.beats.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                    buttonHeight = 40.dp,
                    expand = true
                ) { duration ->
                    NoteGlyph(
                        duration = duration,
                        selected = beats == duration.beats.toFloat()
                    )
                }
                Slider(
                    value = beats,
                    onValueChange = { beats = it },
                    valueRange = 0.25f..4f,
                    steps = 14,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(beats.toDouble()) }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Single-choice option row without the segmented-button check mark. */
@Composable
private fun <T> ChoiceRow(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    buttonHeight: Dp = 32.dp,
    expand: Boolean = false,
    content: @Composable (option: T) -> Unit
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { option ->
            val sel = option == selected
            val bm = if (expand) Modifier.weight(1f) else Modifier
            if (sel) {
                Button(
                    onClick = { onSelect(option) },
                    modifier = bm.height(buttonHeight),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) { content(option) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(option) },
                    modifier = bm.height(buttonHeight),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) { content(option) }
            }
        }
    }
}

/** Note-value icon (whole/half/quarter/eighth/sixteenth) for duration buttons. */
@Composable
private fun NoteGlyph(
    duration: NoteDuration,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    val color =
        // Selected buttons use the solid primary fill, so draw the note in
        // the on-primary color to stay visible.
        if (selected) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier = modifier.size(width = 24.dp, height = 30.dp)) {
        val cx = size.width * 0.36f
        val cy = size.height * 0.70f
        val rx = size.width * 0.27f
        val ry = size.height * 0.15f
        val hollow = duration.beats >= 2.0
        drawOval(
            color = color,
            topLeft = Offset(cx - rx, cy - ry),
            size = Size(rx * 2f, ry * 2f),
            style = if (hollow) Stroke(width = 2.5f) else Fill
        )
        if (duration.beats >= 4.0) return@Canvas
        val sx = cx + rx - 1f
        val tipY = cy - size.height * 0.58f
        drawLine(color, Offset(sx, cy - 1f), Offset(sx, tipY), strokeWidth = 2.5f)
        val flags = when {
            duration.beats < 0.4 -> 2
            duration.beats < 0.75 -> 1
            else -> 0
        }
        repeat(flags) { i ->
            val fy = tipY + i * (size.height * 0.11f)
            drawLine(
                color,
                Offset(sx, fy),
                Offset(sx + size.width * 0.30f, fy + size.height * 0.13f),
                strokeWidth = 3f
            )
        }
    }
}

/** Compact icon buttons for the edit tools (write/erase/select/sharp/flat). */
@Composable
private fun ToolIconRow(
    activeTool: ScoreTool,
    onSelect: (ScoreTool) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ScoreTool.entries.forEach { tool ->
            val selected = activeTool == tool
            val tint =
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            IconButton(
                onClick = { onSelect(tool) },
                modifier = Modifier.size(36.dp)
            ) {
                when (tool) {
                    ScoreTool.WRITE -> Icon(
                        Icons.Filled.Edit,
                        contentDescription = tool.label,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                    ScoreTool.ERASE -> Icon(
                        Icons.Filled.Delete,
                        contentDescription = tool.label,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                    ScoreTool.SELECT -> Icon(
                        Icons.Filled.SelectAll,
                        contentDescription = tool.label,
                        tint = tint,
                        modifier = Modifier.size(20.dp)
                    )
                    ScoreTool.SHARP -> Text(
                        "#",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = tint
                    )
                    ScoreTool.FLAT -> Text(
                        "b",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = tint
                    )
                }
            }
        }
    }
}

@Composable
private fun TrackMenuDialog(
    track: Track,
    instruments: List<Instrument>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    autoColor: Color,
    onNoteColor: (Int?) -> Unit,
    onVolume: (Float) -> Unit,
    onClef: (Clef) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onInstrument: (Instrument) -> Unit
) {
    var name by remember(track.id) { mutableStateOf(track.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(track.name) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Track name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Note color",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                ColorWheel(
                    initialArgb = track.noteColorArgb,
                    fallback = autoColor,
                    onColor = onNoteColor,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Volume: ${(track.volume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                Slider(
                    value = track.volume.coerceIn(0f, 1f),
                    onValueChange = onVolume,
                    valueRange = 0f..1f,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Clef",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                ChoiceRow(
                    options = Clef.entries,
                    selected = Clef.fromName(track.clef),
                    onSelect = onClef,
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    buttonHeight = 40.dp
                ) { clef ->
                    Text(
                        clef.label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = onDuplicate) { Text("Duplicate") }
                    TextButton(onClick = onDelete) { Text("Delete") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TextButton(onClick = onMoveUp, enabled = canMoveUp) { Text("Move up") }
                    TextButton(onClick = onMoveDown, enabled = canMoveDown) { Text("Move down") }
                }
                Text(
                    "Instrument",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                if (instruments.isEmpty()) {
                    Text(
                        "No instruments yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    instruments.forEach { instrument ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onInstrument(instrument) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = instrument.id == track.instrumentId,
                                onClick = { onInstrument(instrument) }
                            )
                            Text(
                                instrument.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clean = name.trim()
                    if (clean.isNotEmpty() && clean != track.name) onRename(clean)
                    onDismiss()
                }
            ) { Text("Done") }
        }
    )
}

@Composable
private fun TimeSigDialog(
    num: Int,
    den: Int,
    onSelect: (num: Int, den: Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Time signature") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Beats per measure",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                ChoiceRow(
                    options = listOf(1, 2, 3, 4, 5, 6, 7, 9, 12),
                    selected = num,
                    onSelect = { onSelect(it, den) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    buttonHeight = 40.dp
                ) { n ->
                    Text(n.toString(), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
                Text(
                    "Beat value",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
                ChoiceRow(
                    options = listOf(2, 4, 8, 16),
                    selected = den,
                    onSelect = { onSelect(num, it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    buttonHeight = 40.dp
                ) { d ->
                    Text(d.toString(), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } }
    )
}

@Composable
private fun TrackChip(
    track: Track,
    color: androidx.compose.ui.graphics.Color,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleSolo: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onSelect,
        label = { Text(track.name) },
        leadingIcon = {
            IconButton(onClick = onToggleMute, modifier = Modifier.height(20.dp)) {
                Icon(
                    if (track.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = "Mute",
                    tint = if (track.muted) MaterialTheme.colorScheme.onSurfaceVariant else color
                )
            }
        },
        trailingIcon = {
            IconButton(onClick = onToggleSolo, modifier = Modifier.height(20.dp)) {
                Text(
                    "S",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (track.solo) FontWeight.Bold
                        else FontWeight.Normal
                    ),
                    color = if (track.solo) color else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}

@Composable
private fun AddTrackDialog(
    instruments: List<Instrument>,
    onDismiss: () -> Unit,
    onAdd: (Instrument) -> Unit
) {
    var selectedId by remember(instruments) { mutableStateOf(instruments.firstOrNull()?.id) }
    val selected = instruments.find { it.id == selectedId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add track") },
        text = {
            if (instruments.isEmpty()) {
                Text("Build an instrument first, in the Instruments tab.")
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    instruments.forEach { instrument ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedId = instrument.id }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = instrument.id == selectedId,
                                onClick = { selectedId = instrument.id }
                            )
                            Text(
                                instrument.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { selected?.let(onAdd) }, enabled = selected != null) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
