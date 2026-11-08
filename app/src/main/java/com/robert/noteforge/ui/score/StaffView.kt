package com.robert.noteforge.ui.score

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import android.graphics.Typeface
import com.robert.noteforge.data.ScoreNote
import com.robert.noteforge.data.Clef
import com.robert.noteforge.data.Track
import com.robert.noteforge.ui.theme.PlayheadRed
import com.robert.noteforge.ui.theme.StaffLine
import com.robert.noteforge.ui.theme.TrackColors
import com.robert.noteforge.util.Midi
import kotlin.math.abs
import kotlin.math.roundToInt

private val BEAT_WIDTH = 56.dp
private val LINE_SPACING = 12.dp         // vertical distance between adjacent staff lines
private val NOTE_RADIUS = 5.dp
private val NOTE_X_OFFSET = 12.dp
private val CLEF_AREA = 68.dp



/** Natural-note MIDI pitch for a tapped display step on a lane with [clef]. */
private fun pitchForStep(displayStep: Int, clef: Clef): Int =
    Midi.stepToNaturalMidi(displayStep - clef.offsetSteps)


/** Loads Bravura once per composition; null if the asset is ever missing. */
@Composable
private fun rememberMusicTypeface(): Typeface? {
    val context = LocalContext.current
    return remember {
        try {
            Typeface.createFromAsset(context.assets, "fonts/Bravura.otf")
        } catch (_: Exception) {
            null
        }
    }
}

/** Paint for Bravura glyphs: one staff space is 0.25 em, so em = 8 half-steps. */


/** UI-local note shown while press-hold placing in Write mode; committed on release. */
private data class GhostNote(val beat: Double, val pitch: Int)

/**
 * Stable holder for the box-select auto-scroll callback. The callback is
 * refreshed every recomposition (fresh scroll offsets/viewport), while the
 * holder identity stays stable so lane gesture detectors never restart.
 */
private class BoxAutoScrollHost {
    var fn: (suspend (canvasCoords: androidx.compose.ui.layout.LayoutCoordinates?, pos: Offset) -> Unit)? = null
}

/** Tracks which horizontal edge the box finger dwells on, and since when. */
private class EdgeDwell {
    var key: Int = 0
    var enterMs: Long = 0L
}

/**
 * One staff lane per track, stacked vertically. All lanes share a single
 * horizontal scroll so beats stay aligned; each lane only shows its own
 * track's notes. Note taps and empty-space taps are forwarded so the caller
 * can apply the active edit tool. In Write mode a long-pressed note can be
 * dragged to a new position (scrolling pauses while dragging).
 */
@Composable
fun MultiStaffView(
    notes: List<ScoreNote>,
    tracks: List<Track>,
    instrumentNames: Map<Long, String> = emptyMap(),
    selectedTrackId: Long?,
    selectedNoteIds: Set<Long>,
    soundingNoteIds: Set<Long>,
    snapBeats: Double,
    measureBeats: Int,
    timeSigNum: Int,
    timeSigDen: Int,
    playheadBeat: Double,
    isPlaying: Boolean,
    measureCount: Int,
    dragEnabled: Boolean,
    onSelectTrack: (Long) -> Unit,
    onDeselectTrack: () -> Unit = {},
    onToggleSolo: (Track) -> Unit,
    onTapNote: (ScoreNote) -> Unit,
    onLongPressNote: (ScoreNote?) -> Unit,
    onDragNote: (noteId: Long, beat: Double, pitchMidi: Int) -> Unit,
    onGhostPreview: (trackId: Long, pitchMidi: Int) -> Unit,
    onGhostCommit: (trackId: Long, beat: Double, pitchMidi: Int) -> Unit,
    onTapEmpty: (trackId: Long, beat: Double, pitchMidi: Int) -> Unit,
    onSeekPlayhead: (beat: Double) -> Unit,
    boxSelectEnabled: Boolean,
    onBoxSelect: (trackId: Long, ids: Set<Long>) -> Unit,
    onTrackMenu: (Track) -> Unit,
    modifier: Modifier = Modifier,
    beamVersion: Int = 0
) {

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val zoomScope = rememberCoroutineScope()
    var scrollLocked by remember { mutableStateOf(false) }
    // Uniform zoom (aspect ratio preserved): scales beat width and staff
    // geometry together, so zooming out fits more tracks and zooming in
    // enlarges the notes.
    var zoom by remember { mutableFloatStateOf(1f) }
    var viewportW by remember { mutableStateOf(0) }
    var viewportH by remember { mutableStateOf(0) }
    // Measured sizes (px) of the scrollable lanes content and its vertical
    // viewport, for an exact zoom-fit calculation.
    var contentW by remember { mutableStateOf(0) }
    var contentH by remember { mutableStateOf(0) }
    var lanesViewportH by remember { mutableStateOf(0) }
    var rootCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val autoScrollHost = remember { BoxAutoScrollHost() }
    val density = LocalDensity.current
    val geometry = remember(density, zoom) {
        with(density) {
            StaffGeometry(
                beatWidthPx = BEAT_WIDTH.toPx() * zoom,
                pxPerStep = LINE_SPACING.toPx() / 2f * zoom,
                noteRadiusPx = NOTE_RADIUS.toPx() * zoom,
                noteXOffsetPx = (NOTE_X_OFFSET + CLEF_AREA).toPx() * zoom,
                hitRadiusPx = 18.dp.toPx(),
                clefInsetPx = CLEF_AREA.toPx() * zoom
            )
        }
    }
    val widthDp = BEAT_WIDTH * zoom * (measureBeats * measureCount) + CLEF_AREA * zoom
    val notesByTrack = remember(notes) { notes.groupBy { it.trackId } }
    // Adaptive lane height (Bobby 2026-10-14): each lane is only as tall as
    // its own content needs — the staff span union the lane's note range,
    // padded by 6 steps on each side so new notes can still be tapped or
    // dragged above/below the current extremes. Kills the dead air between
    // tracks; wide-range tracks keep up to the old 160dp of vertical reach.
    val stepDp = LINE_SPACING / 2
    fun laneHeightDpFor(track: Track): androidx.compose.ui.unit.Dp {
        val clef = Clef.fromName(track.clef)
        var lo = BOTTOM_LINE_STEP
        var hi = TOP_LINE_STEP
        notesByTrack[track.id]?.forEach { n ->
            val s = staffStepFor(n, clef)
            if (s < lo) lo = s
            if (s > hi) hi = s
        }
        val steps = (hi - lo + 1 + 12).coerceIn(16, 26)
        return stepDp * steps * zoom
    }
    val music = rememberMusicTypeface()

    // Page-turn follow during playback: when the playhead leaves the
    // visible page, jump a full page ahead (parked near the left edge)
    // like turning a sheet-music page, instead of smooth scrolling.
    LaunchedEffect(playheadBeat, isPlaying, viewportW, geometry) {
        if (!isPlaying || viewportW == 0) return@LaunchedEffect
        val x = geometry.beatToX(playheadBeat)
        val minVisible = hScroll.value.toFloat()
        val maxVisible = minVisible + viewportW
        if (x >= maxVisible - 4f || x < minVisible) {
            hScroll.scrollTo((x - viewportW * 0.12f).toInt().coerceAtLeast(0))
        }
    }

    fun colorForTrack(trackId: Long): Color {
        val track = tracks.find { it.id == trackId }
        track?.noteColorArgb?.let { return Color(it) }
        val idx = tracks.indexOfFirst { it.id == trackId }.coerceAtLeast(0)
        return TrackColors[idx % TrackColors.size]
    }

    // Box-select edge auto-scroll: refreshed every recomposition so it
    // always sees current scroll offsets and viewport size. Scrolling only
    // kicks in after the finger dwells near the edge for a second, and runs
    // at half speed. Vertical is disabled: selection never spans tracks.
    val edgePx = with(density) { 40.dp.toPx() }
    val stepPx = with(density) { 12.dp.toPx() }
    val edgeDwell = remember { EdgeDwell() }
    autoScrollHost.fn = { coords, pos ->
        val root = rootCoords
        if (coords != null && root != null && coords.isAttached && root.isAttached) {
            val rel = coords.localToRoot(pos) - root.localToRoot(Offset.Zero)
            var dx = 0f
            if (rel.x < edgePx) dx = -stepPx
            else if (rel.x > viewportW - edgePx) dx = stepPx
            val key = when {
                dx < 0f -> -1
                dx > 0f -> 1
                else -> 0
            }
            val now = System.currentTimeMillis()
            if (key == 0) {
                edgeDwell.key = 0
            } else {
                if (edgeDwell.key != key) {
                    edgeDwell.key = key
                    edgeDwell.enterMs = now
                }
                if (now - edgeDwell.enterMs >= 1000L) {
                    hScroll.scrollTo((hScroll.value + dx).toInt().coerceAtLeast(0))
                }
            }
        }
    }

    Column(
        modifier = modifier
            .onSizeChanged {
                viewportW = it.width
                viewportH = it.height
            }
            .onGloballyPositioned { rootCoords = it }
            .pointerInput(hScroll, vScroll, measureBeats, measureCount, tracks.size) {
                // Pinch to zoom that works wherever the fingers land inside
                // the score view. Single-finger gestures are never consumed
                // here so the scroll modifiers (and lane tap/drag) keep
                // working; only two-finger moves are consumed for zoom.
                // Zoom stays centered on the pinch centroid.
                awaitEachGesture {
                    try {
                    awaitFirstDown(requireUnconsumed = false)
                    var zooming = false
                    var prevDist = 0f
                    var prevCentroid = Offset.Zero
                    // Snapshot scroll offsets at pinch start so centering math
                    // stays in the coroutine-safe scope below.
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size < 2) {
                            if (zooming) break
                            if (pressed.isEmpty()) break
                            // Single finger: yield to scroll/tap handling.
                            continue
                        }
                        val a = pressed[0].position
                        val b = pressed[1].position
                        val dist = (a - b).getDistance().coerceAtLeast(1f)
                        val centroid = (a + b) / 2f
                        if (!zooming) {
                            zooming = true
                            prevDist = dist
                            prevCentroid = centroid
                            // Lock lane scrolling/drag while pinching.
                            scrollLocked = true
                        } else {
                            val zoomChange = dist / prevDist
                            if (zoomChange != 1f) {
                                val oldZoom = zoom

                                // The floor IS the fit point: zooming out stops
                                // exactly when the whole score is visible, never
                                // into empty space. Fit is measured, not
                                // estimated — content sizes are exact at the
                                // current zoom, so dividing the zoom back out
                                // gives the true fit on both axes (timeline
                                // horizontally, track count vertically).
                                // Capped at 1x so scores that already fit can't
                                // zoom out at all.
                                // Min zoom stops when all track heights are visible.
                                // Horizontal fit is NOT a limit — the timeline
                                // scrolls instead of forcing the zoom out.
                                val vFit = if (contentH > 0 && zoom > 0f && lanesViewportH > 0)
                                    lanesViewportH * zoom / contentH else 1f
                                // 4% margin: headers don't scale with zoom, so the
                                // exact fit can clip the bottom lane's low notes.
                                val minZoom = (vFit * 0.96f).coerceIn(0.01f, 1f)
                                val newZoom =
                                    (oldZoom * zoomChange).coerceIn(minZoom, 2.5f)
                                val actual = newZoom / oldZoom
                                if (actual != 1f) {
                                    val oldH = hScroll.value
                                    val oldV = vScroll.value
                                    val startCentroid = prevCentroid
                                    val endCentroid = centroid
                                    zoom = newZoom
                                    // Keep the content point under the
                                    // centroid stable across the zoom.
                                    val newH =
                                        ((oldH + startCentroid.x) * actual - endCentroid.x)
                                            .toInt().coerceAtLeast(0)
                                    val newV =
                                        ((oldV + startCentroid.y) * actual - endCentroid.y)
                                            .toInt().coerceAtLeast(0)
                                    zoomScope.launch {
                                        hScroll.scrollTo(newH)
                                        vScroll.scrollTo(newV)
                                    }
                                }
                                pressed.forEach { it.consume() }
                            }
                            prevDist = dist
                            prevCentroid = centroid
                        }
                        if (event.changes.all { !it.pressed }) break
                    }
                    } finally {
                        scrollLocked = false
                    }
                }
            }
    ) {
        // Time strip fixed at the top: tick marks follow the selected
        // snap-to level, and tapping seeks the playhead. It shares the
        // horizontal scroll state so it stays aligned with the lanes.
        Box(
            modifier = Modifier
                .horizontalScroll(hScroll, enabled = !scrollLocked)
        ) {
            TimeStrip(
                geometry = geometry,
                widthDp = widthDp,
                measureBeats = measureBeats,
                measureCount = measureCount,
                snapBeats = snapBeats,
                playheadBeat = playheadBeat,
                onSeek = onSeekPlayhead
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .onSizeChanged { lanesViewportH = it.height }
                .verticalScroll(vScroll, enabled = !scrollLocked && vScroll.maxValue > 0)
        ) {
                Box(
                    modifier = Modifier
                        .horizontalScroll(hScroll, enabled = !scrollLocked)
                ) {
                    Column(
                        modifier = Modifier.onSizeChanged {
                            contentW = it.width
                            contentH = it.height
                        }
                    ) {
                    androidx.compose.runtime.key(beamVersion) {
                    tracks.forEach { track ->
                        TrackLane(
                            track = track,
                            laneNotes = notesByTrack[track.id].orEmpty(),
                            instrumentName = instrumentNames[track.instrumentId] ?: "",
                            hScroll = hScroll,
                            color = colorForTrack(track.id),
                            selected = track.id == selectedTrackId && !isPlaying,
                            selectedNoteIds = selectedNoteIds,
                            soundingNoteIds = soundingNoteIds,
                            geometry = geometry,
                            widthDp = widthDp,
                            measureBeats = measureBeats,
                            timeSigNum = timeSigNum,
                            timeSigDen = timeSigDen,
                            measureCount = measureCount,
                            music = music,
                            snapBeats = snapBeats,
                            playheadBeat = playheadBeat,
                            laneHeightDp = laneHeightDpFor(track),
                            dragEnabled = dragEnabled,
                            boxSelectEnabled = boxSelectEnabled,
                            autoScrollHost = autoScrollHost,
                            onTapNote = onTapNote,
                            onLongPressNote = onLongPressNote,
                            onSelectLane = { onSelectTrack(track.id) },
                            onTrackMenu = { onTrackMenu(track) },
                            onBoxSelect = { ids -> onBoxSelect(track.id, ids) },
                            onDragStart = { scrollLocked = true },
                            onDragEnd = { scrollLocked = false },
                            onDragNote = onDragNote,
                            onGhostPreview = onGhostPreview,
                            onGhostCommit = onGhostCommit,
                            onToggleSolo = { onToggleSolo(track) },
                            onTapEmpty = { beat, pitch ->
                                onSelectTrack(track.id)
                                onTapEmpty(track.id, beat, pitch)
                            }
                        )
                    }
                    }
                }
            }
            // Single playhead line spanning all lanes.
            if (playheadBeat > 0.0 && contentH > 0) {
                val px = geometry.beatToX(playheadBeat) - hScroll.value
                val lineH = maxOf(contentH, lanesViewportH)
                Box(
                    modifier = Modifier
                        .offset { IntOffset(px.roundToInt(), 0) }
                        .width(1.dp)
                        .height(with(density) { lineH.toDp() })
                        .background(PlayheadRed)
                )
            }
        }
    }
}

/**
 * Time ruler above the lanes. Ticks are ruler-graded by level: longest at
 * every bar, medium at every beat, shortest at the current snap-to level
 * ([snapBeats], already resolved so BAR == [measureBeats] and OFF == 0 →
 * quarter-beat ticks). Tapping seeks the playhead, snapped to the grid.
 */
@Composable
private fun TimeStrip(
    geometry: StaffGeometry,
    widthDp: androidx.compose.ui.unit.Dp,
    measureBeats: Int,
    measureCount: Int,
    snapBeats: Double,
    playheadBeat: Double,
    onSeek: (beat: Double) -> Unit
) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val fg = MaterialTheme.colorScheme.onSurfaceVariant
    val totalBeats = measureBeats * measureCount
    // OFF (0.0) still shows a readable quarter-beat grid.
    val minorBeats = 1.0 // Strip shows quarter-note ticks only

    fun snappedBeatAt(x: Float): Double {
        val rawBeat = geometry.xToBeat(x)
        if (rawBeat < 0) return 0.0
        return if (snapBeats > 0) {
            ((rawBeat / snapBeats).roundToInt() * snapBeats)
                .coerceIn(0.0, totalBeats.toDouble())
        } else {
            rawBeat.coerceIn(0.0, totalBeats.toDouble())
        }
    }

    Canvas(
        modifier = Modifier
            .width(widthDp + 32.dp)
            .height(40.dp)
            .pointerInput(snapBeats, geometry, totalBeats) {
                detectTapGestures { offset ->
                    onSeek(snappedBeatAt(offset.x))
                }
            }
    ) {
        // Shared with the video renderer so exports look exactly like the UI.
        DrawScopeScoreCanvas(this).drawScoreTimeStrip(
            ScoreTimeStripParams(
                geometry = geometry,
                widthPx = size.width,
                heightPx = size.height,
                measureBeats = measureBeats,
                measureCount = measureCount,
                bg = bg,
                fg = fg,
                playheadBeat = playheadBeat
            )
        )
    }
}

@Composable
private fun TrackLane(
    track: Track,
    laneNotes: List<ScoreNote>,
    instrumentName: String = "",
    hScroll: androidx.compose.foundation.ScrollState? = null,
    color: Color,
    selected: Boolean,
    selectedNoteIds: Set<Long>,
    soundingNoteIds: Set<Long>,
    geometry: StaffGeometry,
    widthDp: androidx.compose.ui.unit.Dp,
    measureBeats: Int,
    timeSigNum: Int,
    timeSigDen: Int,
    measureCount: Int,
    music: Typeface?,
    snapBeats: Double,
    playheadBeat: Double,
    laneHeightDp: androidx.compose.ui.unit.Dp,
    dragEnabled: Boolean,
    boxSelectEnabled: Boolean,
    autoScrollHost: BoxAutoScrollHost,
    onTapNote: (ScoreNote) -> Unit,
    onLongPressNote: (ScoreNote?) -> Unit,
    onSelectLane: () -> Unit,
    onTrackMenu: () -> Unit,
    onBoxSelect: (Set<Long>) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragNote: (noteId: Long, beat: Double, pitchMidi: Int) -> Unit,
    onGhostPreview: (trackId: Long, pitchMidi: Int) -> Unit,
    onGhostCommit: (trackId: Long, beat: Double, pitchMidi: Int) -> Unit,
    onToggleSolo: () -> Unit,
    onTapEmpty: (beat: Double, pitchMidi: Int) -> Unit
) {
    val haptics = LocalHapticFeedback.current
    var ghost by remember { mutableStateOf<GhostNote?>(null) }
    // Live drag preview: while a note is dragged we only show it at the
    // finger position locally and audition the pitch. The database update
    // (onDragNote) fires once on release, not on every move.
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragPreview by remember { mutableStateOf<GhostNote?>(null) }
    // Box-select rubber band (Select tool): canvas-space rect while dragging.
    var selectRect by remember { mutableStateOf<Rect?>(null) }
    var canvasCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    val boxScope = rememberCoroutineScope()
    val selectColor = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val clef = remember(track.clef) { Clef.fromName(track.clef) }

    fun hitNoteAt(offset: Offset, canvasHeight: Float): ScoreNote? {
        val hitR = geometry.hitRadiusPx
        return laneNotes.firstOrNull { note ->
            val nx = geometry.beatToX(note.startBeat)
            val ny = geometry.stepToY(staffStepFor(note, clef), canvasHeight)
            val dx = offset.x - nx
            val dy = offset.y - ny
            (dx * dx + dy * dy) <= hitR * hitR
        }
    }
    fun snappedBeatAt(x: Float): Double {
        val rawBeat = geometry.xToBeat(x)
        if (rawBeat < 0) return 0.0
        return if (snapBeats > 0) {
            ((rawBeat / snapBeats).roundToInt() * snapBeats).coerceAtLeast(0.0)
        } else {
            rawBeat
        }
    }
    fun pitchAtY(y: Float, canvasHeight: Float): Int {
        val step = geometry.yToStep(y, canvasHeight)
        return pitchForStep(step, clef)
    }
    Column(
        modifier = Modifier
            .width(widthDp + 32.dp)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) color else StaffLine.copy(alpha = 0.4f)
            )
            .padding(bottom = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .offset {
                    // Counter-scroll: the header stays pinned to the left
                    // viewport edge while the lane scrolls underneath.
                    IntOffset(hScroll?.value ?: 0, 0)
                }
                .pointerInput(track.id) {
                    detectTapGestures(
                        onTap = { onSelectLane() },
                        onLongPress = { onTrackMenu() }
                    )
                }
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
            Text(
                track.name +
                    (if (instrumentName.isNotEmpty()) " • $instrumentName" else "") +
                    when {
                        track.solo -> " (solo)"
                        track.muted -> " (muted)"
                        else -> ""
                    },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f).padding(start = 8.dp)
            )
            TextButton(
                onClick = onToggleSolo,
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.height(28.dp)
            ) {
                Text(
                    "S",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (track.solo) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (track.solo) color else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(laneHeightDp)
                .onGloballyPositioned { canvasCoords = it }
                .pointerInput(track.id, snapBeats, laneNotes, selectedNoteIds, geometry, boxSelectEnabled) {
                    // Taps + long-press selection. Framework slop/consumption
                    // handling: scrolls never trigger taps or selections.
                    // In box-select mode long-press starts the rubber band
                    // (handled below), so it is ignored here.
                    detectTapGestures(
                        onTap = { offset ->
                            val canvasHeight = size.height.toFloat()
                            val hit = hitNoteAt(offset, canvasHeight)
                            if (hit != null) {
                                onTapNote(hit)
                            } else {
                                val rawBeat = geometry.xToBeat(offset.x)
                                if (rawBeat >= 0) {
                                    onTapEmpty(snappedBeatAt(offset.x), pitchAtY(offset.y, canvasHeight))
                                }
                            }
                        },
                        onLongPress = { offset ->
                            if (boxSelectEnabled) return@detectTapGestures
                            val hit = hitNoteAt(offset, size.height.toFloat())
                            if (hit == null) {
                                onLongPressNote(null)
                            } else if (hit.id !in selectedNoteIds) {
                                onLongPressNote(hit)
                            }
                            // Already-selected: the drag tracker below defers
                            // to release-without-move to open properties.
                        }
                    )
                }
                .pointerInput(track.id, boxSelectEnabled, laneNotes, geometry) {
                    // Box-select (Select tool): drag a rubber band to add the
                    // enclosed notes of this lane to the selection. Yields to
                    // scroll when scroll claims the gesture, and aborts on
                    // pinch-zoom. Taps are handled by the tap detector above.
                    if (!boxSelectEnabled) {
                        selectRect = null
                        return@pointerInput
                    }
                    awaitEachGesture {
                        // Long-press claims box-select; plain drags are left
                        // alone so drag-to-scroll keeps working in this mode.
                        val down = awaitFirstDown()
                        val longPress =
                            awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val start = longPress.position
                        var rect: Rect? = null
                        var aborted = false
                        onDragStart()
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.all { !it.pressed }) break
                                // A second finger means pinch-zoom: abort, no select.
                                if (event.changes.size > 1) {
                                    aborted = true
                                    break
                                }
                                val change = event.changes.firstOrNull { it.pressed } ?: break
                                // Ancestor scroll claimed this move: yield to it.
                                if (change.consumed.positionChange) continue
                                val pos = change.position
                                change.consume()
                                rect = Rect(
                                    Offset(
                                        kotlin.math.min(start.x, pos.x),
                                        kotlin.math.min(start.y, pos.y)
                                    ),
                                    Size(
                                        kotlin.math.abs(pos.x - start.x),
                                        kotlin.math.abs(pos.y - start.y)
                                    )
                                )
                                selectRect = rect
                                // Edge auto-scroll: keep extending the box past
                                // the viewport while the finger dwells near it.
                                val scrollFn = autoScrollHost.fn
                                val coords = canvasCoords
                                if (scrollFn != null) {
                                    boxScope.launch { scrollFn(coords, pos) }
                                }
                            }
                            if (!aborted) {
                                rect?.let { r ->
                                    val canvasHeight = size.height.toFloat()
                                    val padded = r.inflate(geometry.hitRadiusPx)
                                    val ids = laneNotes.filter { note ->
                                        val nx = geometry.beatToX(note.startBeat)
                                        val ny = geometry.stepToY(staffStepFor(note, clef), canvasHeight)
                                        padded.contains(Offset(nx, ny))
                                    }.map { it.id }.toSet()
                                    if (ids.isNotEmpty()) onBoxSelect(ids)
                                }
                            }
                        } finally {
                            onDragEnd()
                            selectRect = null
                        }
                    }
                }
                .pointerInput(track.id, laneNotes, selectedNoteIds, dragEnabled, boxSelectEnabled, haptics, geometry) {
                    // Long-press drag tracker. Write mode: drag grabbed notes
                    // and press-hold ghost placement; other modes: track
                    // release-without-move to open note properties.
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val longPress =
                            awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        val press = longPress.position
                        val canvasHeight = size.height.toFloat()
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val target = hitNoteAt(press, canvasHeight)
                        if (target == null) {
                            if (!dragEnabled) return@awaitEachGesture
                            ghost = GhostNote(
                                beat = snappedBeatAt(press.x),
                                pitch = pitchAtY(press.y, canvasHeight)
                            )
                            ghost?.let { onGhostPreview(track.id, it.pitch) }
                            onDragStart()
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.all { !it.pressed }) break
                                    // A second finger means pinch-zoom: drop the
                                    // ghost silently and yield.
                                    if (event.changes.size > 1) {
                                        ghost = null
                                        break
                                    }
                                    val change = event.changes.firstOrNull { it.pressed } ?: break
                                    // Ancestor scroll claimed this move: yield.
                                    if (change.consumed.positionChange) continue
                                    change.consume()
                                    val pos = change.position
                                    val nb = snappedBeatAt(pos.x)
                                    val np = pitchAtY(pos.y, canvasHeight)
                                    val cur = ghost
                                    if (cur == null || cur.beat != nb || cur.pitch != np) {
                                        ghost = GhostNote(nb, np)
                                        onGhostPreview(track.id, np)
                                    }
                                }
                            } finally {
                                ghost?.let { onGhostCommit(track.id, it.beat, it.pitch) }
                                ghost = null
                                onDragEnd()
                            }
                            return@awaitEachGesture
                        }
                        val wasSelected = target.id in selectedNoteIds
                        val slop = viewConfiguration.touchSlop
                        var moved = false
                        var pinchInterrupted = false
                        dragId = target.id
                        dragPreview = null
                        if (dragEnabled) onDragStart()
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.changes.all { !it.pressed }) break
                                // A second finger means pinch-zoom: stop tracking
                                // and yield (no commit, no properties dialog).
                                if (event.changes.size > 1) {
                                    moved = true
                                    pinchInterrupted = true
                                    continue
                                }
                                val change = event.changes.firstOrNull { it.pressed } ?: break
                                // Ancestor scroll claimed this move: yield to it.
                                if (change.consumed.positionChange) {
                                    moved = true
                                    continue
                                }
                                val pos = change.position
                                if (!moved &&
                                    abs(pos.x - press.x) < slop &&
                                    abs(pos.y - press.y) < slop
                                ) {
                                    continue
                                }
                                moved = true
                                if (dragEnabled) {
                                    change.consume()
                                    // Preview only: do not commit until release.
                                    val nb = snappedBeatAt(pos.x)
                                    val np = pitchAtY(pos.y, canvasHeight)
                                    val cur = dragPreview
                                    if (cur == null || cur.beat != nb || cur.pitch != np) {
                                        dragPreview = GhostNote(nb, np)
                                        onGhostPreview(track.id, np)
                                    }
                                }
                            }
                            // Commit once on release (Write mode, real move,
                            // not interrupted by pinch-zoom).
                            if (moved && !pinchInterrupted && dragEnabled) {
                                dragPreview?.let { preview ->
                                    onDragNote(target.id, preview.beat, preview.pitch)
                                }
                            }
                            // Released without moving a previously-selected note:
                            // the "long-press again" properties gesture
                            // (disabled in box-select mode: long-press there
                            // starts the rubber band instead).
                            if (!moved && wasSelected && !boxSelectEnabled) {
                                onLongPressNote(target)
                            }
                        } finally {
                            dragId = null
                            dragPreview = null
                            if (dragEnabled) onDragEnd()
                        }
                    }
                }
        ) {
            val canvasHeight = size.height

            // Staff, clef, rests, barlines and notes: shared with the video
            // renderer so exports look exactly like the UI.
            // A note being dragged is drawn at its preview position until
            // release (the database updates only on release).
            val drawOf = { note: ScoreNote ->
                val preview = if (note.id == dragId) dragPreview else null
                if (preview != null) {
                    note.copy(startBeat = preview.beat, pitchMidi = preview.pitch, accidental = 0)
                } else note
            }
            val ordered = laneNotes.sortedBy { it.startBeat }
            // While a beamed note is dragged its group is drawn un-beamed so
            // the preview never fights the shared beam geometry.
            val draggingBeamed = (dragId?.let { beamGroups(ordered, measureBeats)[it] != null } ?: false) &&
                dragPreview != null
            DrawScopeScoreCanvas(this).drawScoreLane(
                ScoreLaneParams(
                    geometry = geometry,
                    laneWidthPx = size.width,
                    laneHeightPx = canvasHeight,
                    notes = ordered.map { drawOf(it) },
                    color = color,
                    clef = clef,
                    timeSigNum = timeSigNum,
                    timeSigDen = timeSigDen,
                    measureBeats = measureBeats,
                    measureCount = measureCount,
                    music = music,
                    ink = ink,
                    soundingNoteIds = soundingNoteIds,
                    selectedNoteIds = selectedNoteIds,
                    forceUnbeamed = draggingBeamed
                )
            )

            // Ghost note while press-hold placing in Write mode.
            ghost?.let { g ->
                drawStaffNote(
                    DrawScopeScoreCanvas(this),
                    ScoreNote(
                        trackId = track.id,
                        pitchMidi = g.pitch,
                        startBeat = g.beat,
                        durationBeats = 1.0
                    ),
                    color.copy(alpha = 0.55f),
                    geometry,
                    canvasHeight,
                    step = Midi.diatonicStep(g.pitch) + clef.offsetSteps,
                    glyph = null
                )
            }

            // Box-select rubber band.
            selectRect?.let { r ->
                drawRect(
                    color = selectColor.copy(alpha = 0.18f),
                    topLeft = r.topLeft,
                    size = r.size
                )
                drawRect(
                    color = selectColor,
                    topLeft = r.topLeft,
                    size = r.size,
                    style = Stroke(width = 2f)
                )
            }

        }
    }
}


/** Draws a (possibly multi-digit) time-signature value centered at (cx, cy). */
