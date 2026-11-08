package com.robert.noteforge.ui.score

import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import com.robert.noteforge.data.Clef
import com.robert.noteforge.data.ScoreNote
import com.robert.noteforge.ui.theme.PlayheadRed
import com.robert.noteforge.ui.theme.SoundingGreen
import com.robert.noteforge.ui.theme.StaffLine
import com.robert.noteforge.util.Midi
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// Shared score drawing: the exact same code renders the score in the UI
// (via DrawScopeScoreCanvas) and in exported video frames
// (via AndroidScoreCanvas). Any visual change here affects both identically.
// ---------------------------------------------------------------------------

// Layout constants, in diatonic staff steps: E4 is the bottom line, F5 the top line.
const val BOTTOM_LINE_STEP = 30  // E4
const val TOP_LINE_STEP = 38     // F5

/** Geometry resolved from the current density (and zoom), in raw pixels. */
data class StaffGeometry(
    val beatWidthPx: Float,
    val pxPerStep: Float,
    val noteRadiusPx: Float,
    val noteXOffsetPx: Float,
    val hitRadiusPx: Float,
    /** Leading reservation for the lane clef + time signature. Part of
     * [noteXOffsetPx], so strips and lanes share one beat mapping. */
    val clefInsetPx: Float
) {
    fun beatToX(beat: Double): Float = (beat * beatWidthPx).toFloat() + noteXOffsetPx
    fun xToBeat(x: Float): Double = ((x - noteXOffsetPx) / beatWidthPx).toDouble()
    fun stepToY(step: Int, canvasHeight: Float): Float {
        val centerY = canvasHeight / 2f
        val centerStep = (BOTTOM_LINE_STEP + TOP_LINE_STEP) / 2f
        return centerY - (step - centerStep) * pxPerStep
    }
    fun yToStep(y: Float, canvasHeight: Float): Int {
        val centerY = canvasHeight / 2f
        val centerStep = (BOTTOM_LINE_STEP + TOP_LINE_STEP) / 2f
        return (centerStep - (y - centerY) / pxPerStep).roundToInt()
    }
}

/** Minimal 2D drawing surface shared by the UI and the video renderer. */
interface ScorePath {
    fun moveTo(x: Float, y: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float)
    fun close()
}

interface ScoreCanvas {
    fun drawLine(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, strokeWidth: Float, roundCap: Boolean = false)
    fun drawCircle(color: Color, cx: Float, cy: Float, radius: Float)
    fun drawOval(color: Color, left: Float, top: Float, right: Float, bottom: Float, fill: Boolean, strokeWidth: Float = 1f)
    fun drawRect(color: Color, left: Float, top: Float, right: Float, bottom: Float, fill: Boolean, strokeWidth: Float = 1f)
    fun drawArc(color: Color, left: Float, top: Float, right: Float, bottom: Float, startAngleDeg: Float, sweepAngleDeg: Float, strokeWidth: Float)
    /** Text drawn with (x, y) as the baseline origin; centered horizontally when [center]. */
    fun drawText(text: String, x: Float, y: Float, color: Color, textSize: Float, typeface: Typeface?, bold: Boolean, center: Boolean)
    /** Text centered on (cx, cy) using measured glyph bounds (for music glyphs). */
    fun drawTextCentered(text: String, cx: Float, cy: Float, color: Color, textSize: Float, typeface: Typeface?)
    fun measureText(text: String, textSize: Float, typeface: Typeface?, bold: Boolean): Float
    fun withRotation(degrees: Float, cx: Float, cy: Float, block: ScoreCanvas.() -> Unit)
    fun createPath(block: ScorePath.() -> Unit): ScorePath
    fun drawPath(path: ScorePath, color: Color, fill: Boolean, strokeWidth: Float = 1f)
}

/** ScoreCanvas backed by a Compose DrawScope (the on-screen UI). */
class DrawScopeScoreCanvas(private val scope: DrawScope) : ScoreCanvas {
    private fun paintFor(color: Color, textSize: Float, typeface: Typeface?, bold: Boolean, center: Boolean) =
        android.graphics.Paint().apply {
            this.color = color.toArgb()
            this.textSize = textSize
            this.typeface = typeface
            this.isFakeBoldText = bold
            textAlign = if (center) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
            isAntiAlias = true
        }

    override fun drawLine(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, strokeWidth: Float, roundCap: Boolean) {
        scope.drawLine(color, Offset(x1, y1), Offset(x2, y2), strokeWidth = strokeWidth,
            cap = if (roundCap) StrokeCap.Round else StrokeCap.Butt)
    }

    override fun drawCircle(color: Color, cx: Float, cy: Float, radius: Float) {
        scope.drawCircle(color, radius, Offset(cx, cy))
    }

    override fun drawOval(color: Color, left: Float, top: Float, right: Float, bottom: Float, fill: Boolean, strokeWidth: Float) {
        scope.drawOval(color, Offset(left, top), Size(right - left, bottom - top),
            style = if (fill) Fill else Stroke(width = strokeWidth))
    }

    override fun drawRect(color: Color, left: Float, top: Float, right: Float, bottom: Float, fill: Boolean, strokeWidth: Float) {
        scope.drawRect(color, Offset(left, top), Size(right - left, bottom - top),
            style = if (fill) Fill else Stroke(width = strokeWidth))
    }

    override fun drawArc(color: Color, left: Float, top: Float, right: Float, bottom: Float, startAngleDeg: Float, sweepAngleDeg: Float, strokeWidth: Float) {
        scope.drawArc(color, startAngleDeg, sweepAngleDeg, false, Offset(left, top),
            Size(right - left, bottom - top), style = Stroke(width = strokeWidth))
    }

    override fun drawText(text: String, x: Float, y: Float, color: Color, textSize: Float, typeface: Typeface?, bold: Boolean, center: Boolean) {
        scope.drawContext.canvas.nativeCanvas.drawText(text, x, y, paintFor(color, textSize, typeface, bold, center))
    }

    override fun drawTextCentered(text: String, cx: Float, cy: Float, color: Color, textSize: Float, typeface: Typeface?) {
        val paint = paintFor(color, textSize, typeface, false, true)
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val dx = (bounds.left + bounds.right) / 2f
        val dy = (bounds.top + bounds.bottom) / 2f
        scope.drawContext.canvas.nativeCanvas.drawText(text, cx - dx, cy - dy, paint)
    }

    override fun measureText(text: String, textSize: Float, typeface: Typeface?, bold: Boolean): Float {
        return paintFor(Color.Black, textSize, typeface, bold, true).measureText(text)
    }

    override fun withRotation(degrees: Float, cx: Float, cy: Float, block: ScoreCanvas.() -> Unit) {
        scope.withTransform({ rotate(degrees, Offset(cx, cy)) }) {
            DrawScopeScoreCanvas(this).block()
        }
    }

    private inner class ComposeScorePath(val path: Path = Path()) : ScorePath {
        override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
            path.cubicTo(x1, y1, x2, y2, x3, y3)
        override fun close() = path.close()
    }

    override fun createPath(block: ScorePath.() -> Unit): ScorePath =
        ComposeScorePath().apply(block)

    override fun drawPath(path: ScorePath, color: Color, fill: Boolean, strokeWidth: Float) {
        val p = (path as ComposeScorePath).path
        scope.drawPath(p, color, style = if (fill) Fill else Stroke(width = strokeWidth, cap = StrokeCap.Round))
    }
}

/** BGR swap for video: the encoder swaps R and B channels, so pre-swap here. */
fun Color.toBgrArgb(): Int {
    val a = (alpha * 255).toInt() and 0xFF
    val r = (red * 255).toInt() and 0xFF
    val g = (green * 255).toInt() and 0xFF
    val b = (blue * 255).toInt() and 0xFF
    return (a shl 24) or (b shl 16) or (g shl 8) or r
}

/** ScoreCanvas backed by an android.graphics.Canvas (exported video frames). */
class AndroidScoreCanvas(private val canvas: android.graphics.Canvas) : ScoreCanvas {
    private fun paintFor(color: Color, textSize: Float, typeface: Typeface?, bold: Boolean, center: Boolean) =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toBgrArgb()
            this.textSize = textSize
            this.typeface = typeface
            this.isFakeBoldText = bold
            textAlign = if (center) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
        }

    private fun strokePaint(color: Color, strokeWidth: Float, roundCap: Boolean) =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toBgrArgb()
            style = android.graphics.Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = if (roundCap) android.graphics.Paint.Cap.ROUND else android.graphics.Paint.Cap.BUTT
        }

    override fun drawLine(color: Color, x1: Float, y1: Float, x2: Float, y2: Float, strokeWidth: Float, roundCap: Boolean) {
        canvas.drawLine(x1, y1, x2, y2, strokePaint(color, strokeWidth, roundCap))
    }

    override fun drawCircle(color: Color, cx: Float, cy: Float, radius: Float) {
        canvas.drawCircle(cx, cy, radius, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toBgrArgb()
            style = android.graphics.Paint.Style.FILL
        })
    }

    override fun drawOval(color: Color, left: Float, top: Float, right: Float, bottom: Float, fill: Boolean, strokeWidth: Float) {
        canvas.drawOval(left, top, right, bottom, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toBgrArgb()
            style = if (fill) android.graphics.Paint.Style.FILL else android.graphics.Paint.Style.STROKE
            if (!fill) this.strokeWidth = strokeWidth
        })
    }

    override fun drawRect(color: Color, left: Float, top: Float, right: Float, bottom: Float, fill: Boolean, strokeWidth: Float) {
        canvas.drawRect(left, top, right, bottom, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toBgrArgb()
            style = if (fill) android.graphics.Paint.Style.FILL else android.graphics.Paint.Style.STROKE
            if (!fill) this.strokeWidth = strokeWidth
        })
    }

    override fun drawArc(color: Color, left: Float, top: Float, right: Float, bottom: Float, startAngleDeg: Float, sweepAngleDeg: Float, strokeWidth: Float) {
        canvas.drawArc(left, top, right, bottom, startAngleDeg, sweepAngleDeg, false, strokePaint(color, strokeWidth, true))
    }

    override fun drawText(text: String, x: Float, y: Float, color: Color, textSize: Float, typeface: Typeface?, bold: Boolean, center: Boolean) {
        canvas.drawText(text, x, y, paintFor(color, textSize, typeface, bold, center))
    }

    override fun drawTextCentered(text: String, cx: Float, cy: Float, color: Color, textSize: Float, typeface: Typeface?) {
        val paint = paintFor(color, textSize, typeface, false, true)
        val bounds = android.graphics.Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val dx = (bounds.left + bounds.right) / 2f
        val dy = (bounds.top + bounds.bottom) / 2f
        canvas.drawText(text, cx - dx, cy - dy, paint)
    }

    override fun measureText(text: String, textSize: Float, typeface: Typeface?, bold: Boolean): Float {
        return paintFor(Color.Black, textSize, typeface, bold, true).measureText(text)
    }

    override fun withRotation(degrees: Float, cx: Float, cy: Float, block: ScoreCanvas.() -> Unit) {
        canvas.save()
        canvas.rotate(degrees, cx, cy)
        try {
            block()
        } finally {
            canvas.restore()
        }
    }

    private inner class AndroidScorePath(val path: android.graphics.Path = android.graphics.Path()) : ScorePath {
        override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
        override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
            path.cubicTo(x1, y1, x2, y2, x3, y3)
        override fun close() = path.close()
    }

    override fun createPath(block: ScorePath.() -> Unit): ScorePath =
        AndroidScorePath().apply(block)

    override fun drawPath(path: ScorePath, color: Color, fill: Boolean, strokeWidth: Float) {
        val p = (path as AndroidScorePath).path
        canvas.drawPath(p, if (fill) android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color.toBgrArgb()
            style = android.graphics.Paint.Style.FILL
        } else strokePaint(color, strokeWidth, true))
    }
}

// ---------------------------------------------------------------------------
// Engraved music symbols (Bravura codepoints; origins verified against the
// font's cmap and Bravura 1.482 metadata).
// ---------------------------------------------------------------------------

const val GLYPH_G_CLEF = "\uE050"
const val GLYPH_F_CLEF = "\uE062"
const val GLYPH_REST_WHOLE = "\uE4E3"
const val GLYPH_REST_HALF = "\uE4E4"
const val GLYPH_REST_QUARTER = "\uE4E5"
const val GLYPH_REST_8TH = "\uE4E6"
fun timeSigDigit(d: Int): String = String(Character.toChars(0xE080 + d.coerceIn(0, 9)))

/** Display staff step for a note: diatonic step shifted by the lane clef,
 * honoring flat spelling (Db sits on the D line). */
fun staffStepFor(note: ScoreNote, clef: Clef = Clef.TREBLE): Int {
    val base = if (note.accidental < 0) Midi.diatonicStep(note.pitchMidi + 1) else Midi.diatonicStep(note.pitchMidi)
    return base + clef.offsetSteps
}

/** Accidental sign to draw left of the notehead, or null for naturals. */
fun accidentalGlyph(note: ScoreNote): String? = when {
    note.accidental > 0 -> "#"
    note.accidental < 0 -> "b"
    Midi.isSharp(note.pitchMidi) -> "#"
    else -> null
}

/** Bravura text size for one staff space = 0.25 em, so em = 8 half-steps. */
fun musicTextSize(pxPerStep: Float): Float = 8f * pxPerStep

/** Draws a (possibly multi-digit) time-signature value centered at (cx, cy). */
fun drawTimeDigits(
    canvas: ScoreCanvas,
    value: Int,
    cx: Float,
    cy: Float,
    color: Color,
    textSize: Float,
    typeface: Typeface?
) {
    val text = value.coerceIn(1, 99).toString().map { timeSigDigit(it - '0') }.joinToString("")
    var total = 0f
    text.forEach { total += canvas.measureText(it.toString(), textSize, typeface, false) }
    var x = cx - total / 2f
    text.forEach {
        val s = it.toString()
        canvas.drawText(s, x, cy, color, textSize, typeface, false, center = false)
        x += canvas.measureText(s, textSize, typeface, false)
    }
}

/** A silence to fill with a rest glyph: center beat + rest kind. */
enum class RestKind { FULL, HALF, QUARTER, EIGHTH }
data class RestMark(val beat: Double, val kind: RestKind)

/**
 * Finds the silences in a lane that should show rests: a whole rest for an
 * empty measure, otherwise half/quarter/eighth rests filling gaps of at
 * least an eighth (smaller slivers are left alone to avoid clutter).
 */
fun computeRests(
    notes: List<ScoreNote>,
    measureBeats: Int,
    measureCount: Int
): List<RestMark> {
    val out = mutableListOf<RestMark>()
    val beats = measureBeats.toDouble()
    for (m in 0 until measureCount) {
        val mStart = m * beats
        val mEnd = mStart + beats
        val covered = mutableListOf<Pair<Double, Double>>()
        notes.forEach { n ->
            val s = maxOf(n.startBeat, mStart)
            val e = minOf(n.startBeat + n.durationBeats, mEnd)
            if (e - s > 1e-9) {
                val last = covered.lastOrNull()
                if (last != null && s <= last.second + 1e-9) {
                    covered[covered.lastIndex] = last.first to maxOf(last.second, e)
                } else {
                    covered.add(s to e)
                }
            }
        }
        if (covered.isEmpty()) {
            out.add(RestMark((mStart + mEnd) / 2.0, RestKind.FULL))
            continue
        }
        var cursor = mStart
        val gaps = mutableListOf<Pair<Double, Double>>()
        covered.forEach { (s, e) ->
            if (s - cursor > 1e-9) gaps.add(cursor to s)
            cursor = maxOf(cursor, e)
        }
        if (mEnd - cursor > 1e-9) gaps.add(cursor to mEnd)
        gaps.forEach { (gs, ge) ->
            var pos = gs
            var rem = ge - gs
            if (rem < BeamTune.restThreshold - 1e-9) return@forEach
            while (rem > 1e-9) {
                val d = when {
                    rem >= 2.0 - 1e-9 -> 2.0
                    rem >= 1.0 - 1e-9 -> 1.0
                    rem >= 0.5 - 1e-9 -> 0.5
                    else -> break
                }
                val kind = when (d) {
                    2.0 -> RestKind.HALF
                    1.0 -> RestKind.QUARTER
                    else -> RestKind.EIGHTH
                }
                out.add(RestMark(pos + d / 2.0, kind))
                pos += d
                rem -= d
            }
        }
    }
    return out
}

/**
 * Draws rest marks with the Bravura glyphs, ink-centered on their staff
 * targets: whole rests hang below the 4th line, half rests sit on the
 * middle line, shorter rests center on the staff.
 */
fun drawRestMarks(
    canvas: ScoreCanvas,
    rests: List<RestMark>,
    music: Typeface,
    geometry: StaffGeometry,
    canvasHeight: Float,
    ink: Color
) {
    if (rests.isEmpty()) return
    val u = geometry.pxPerStep
    rests.forEach { rest ->
        val glyph = when (rest.kind) {
            RestKind.FULL -> GLYPH_REST_WHOLE
            RestKind.HALF -> GLYPH_REST_HALF
            RestKind.QUARTER -> GLYPH_REST_QUARTER
            RestKind.EIGHTH -> GLYPH_REST_8TH
        }
        val x = geometry.beatToX(rest.beat)
        val targetY = when (rest.kind) {
            RestKind.FULL -> geometry.stepToY(36, canvasHeight) + 0.5f * u
            RestKind.HALF -> geometry.stepToY(34, canvasHeight) - 0.5f * u
            else -> geometry.stepToY(34, canvasHeight)
        }
        canvas.drawTextCentered(glyph, x, targetY, ink, musicTextSize(u), music)
    }
}

/**
 * Clef + time signature at the start of a lane, drawn with the engraved
 * Bravura glyphs when available (G/F clef on its reference line, SMuFL
 * time-signature digits). Falls back to stroke-drawn clefs + serif text so
 * the staff never renders tofu boxes.
 */
fun drawClefAndTimeSig(
    canvas: ScoreCanvas,
    clef: Clef,
    timeNum: Int,
    timeDen: Int,
    geometry: StaffGeometry,
    canvasHeight: Float,
    insetPx: Float,
    ink: Color,
    music: Typeface?
) {
    val u = geometry.pxPerStep
    val topY = geometry.stepToY(TOP_LINE_STEP, canvasHeight)
    val bottomY = geometry.stepToY(BOTTOM_LINE_STEP, canvasHeight)
    // Octave clefs share their base clef's reference line.
    val refStep = when (clef) {
        Clef.TREBLE, Clef.TREBLE_8VA -> 32
        Clef.BASS, Clef.BASS_8VB -> 36
    }
    val octaveMark = when (clef) {
        Clef.TREBLE_8VA -> "8va" to true
        Clef.BASS_8VB -> "8vb" to false
        else -> null
    }
    val clefCx = insetPx * 0.28f
    val timeCx = insetPx * 0.74f
    val serifBold = Typeface.create(Typeface.SERIF, Typeface.BOLD)
    val refY = geometry.stepToY(refStep, canvasHeight)
    if (music != null) {
        // SMuFL clef origins sit on the reference line: draw at (cx, refY).
        canvas.drawText(
            if (clef == Clef.TREBLE || clef == Clef.TREBLE_8VA) GLYPH_G_CLEF else GLYPH_F_CLEF,
            clefCx, refY, ink, musicTextSize(u), music, bold = false, center = true
        )
        // SMuFL time-signature digits are vertically centered on their
        // origin: stack multi-digit values around the staff-half centers.
        val staffH = bottomY - topY
        drawTimeDigits(canvas, timeNum, timeCx, topY + staffH * 0.25f, ink, musicTextSize(u), music)
        drawTimeDigits(canvas, timeDen, timeCx, topY + staffH * 0.75f, ink, musicTextSize(u), music)
    } else {
        if (clef == Clef.TREBLE || clef == Clef.TREBLE_8VA) {
            drawTrebleClef(canvas, ink, clefCx, refY, u)
        } else {
            drawBassClef(canvas, ink, clefCx, refY, u)
        }
        // Time signature: numerator over denominator on the staff.
        val timeSize = (geometry.pxPerStep * 4.2f).coerceAtMost(canvasHeight * 0.26f)
        val staffH = bottomY - topY
        canvas.drawText(
            timeNum.coerceIn(1, 12).toString(),
            timeCx, topY + staffH * 0.25f + timeSize * 0.35f, ink, timeSize, serifBold,
            bold = false, center = true
        )
        canvas.drawText(
            timeDen.coerceIn(2, 16).toString(),
            timeCx, topY + staffH * 0.75f + timeSize * 0.35f, ink, timeSize, serifBold,
            bold = false, center = true
        )
    }
    // Octave transposition mark above/below the staff.
    octaveMark?.let { (mark, above) ->
        val markSize = (u * 3.4f).coerceAtMost(canvasHeight * 0.2f)
        val markY = if (above) topY - markSize * 0.4f else bottomY + markSize * 1.1f
        canvas.drawText(mark, clefCx, markY, ink, markSize, serifBold, bold = false, center = true)
    }
}

/**
 * Engraved-style G clef centered on the G line ([lineY]): central stem with
 * a top loop, an upper bowl, a belly spiral wrapping the G line, and a
 * descender hook below the staff. All sizes derive from [u] (half a staff
 * space) so the clef scales with zoom like the staff.
 */
fun drawTrebleClef(canvas: ScoreCanvas, ink: Color, cx: Float, lineY: Float, u: Float) {
    val stroke = u * 0.6f
    val top = lineY - 7.4f * u
    val bottom = lineY + 5.0f * u
    // Central stem.
    canvas.drawLine(ink, cx, top, cx, bottom, stroke, roundCap = true)
    // Top loop.
    canvas.drawOval(ink, cx - 0.85f * u, lineY - 8.75f * u, cx + 0.85f * u, lineY - 7.05f * u, fill = false, strokeWidth = stroke)
    // Upper bowl.
    canvas.drawOval(ink, cx - 1.15f * u, lineY - 6.35f * u, cx + 1.15f * u, lineY - 3.25f * u, fill = false, strokeWidth = stroke)
    // Belly spiral wrapping the G line.
    canvas.drawOval(ink, cx - 1.7f * u, lineY - 1.6f * u, cx + 1.7f * u, lineY + 1.8f * u, fill = false, strokeWidth = stroke)
    // Descender hook below the staff.
    val tail = canvas.createPath {
        moveTo(cx, bottom)
        cubicTo(
            cx - 0.1f * u, bottom + 1.6f * u,
            cx - 2.1f * u, bottom + 1.2f * u,
            cx - 1.7f * u, bottom + 2.8f * u
        )
    }
    canvas.drawPath(tail, ink, fill = false, strokeWidth = stroke)
}

/**
 * Engraved-style F clef on the F line ([lineY]): head dot, swept curve
 * opening to the left, and the two dots straddling the F line.
 */
fun drawBassClef(canvas: ScoreCanvas, ink: Color, cx: Float, lineY: Float, u: Float) {
    val stroke = u * 0.65f
    // Head dot on the F line where the curve begins.
    canvas.drawCircle(ink, cx - 1.0f * u, lineY, 0.62f * u)
    // Swept curve: backward-C opening facing left/down.
    canvas.drawArc(
        ink,
        cx - 1.0f * u, lineY - 3.9f * u,
        cx + 3.0f * u, lineY + 1.3f * u,
        140f, 250f, stroke
    )
    // Twin dots straddling the F line, right of the curve.
    val dotR = 0.5f * u
    val dotX = cx + 3.3f * u
    canvas.drawCircle(ink, dotX, lineY - u, dotR)
    canvas.drawCircle(ink, dotX, lineY + u, dotR)
}

fun drawStaffNote(
    canvas: ScoreCanvas,
    note: ScoreNote,
    color: Color,
    geometry: StaffGeometry,
    canvasHeight: Float,
    step: Int,
    glyph: String?,
    editSelected: Boolean = false,
    sounding: Boolean = false,
    stemUpOverride: Boolean? = null,
    stemTipYOverride: Float? = null,
    drawFlags: Boolean = true,
    measureBeats: Int = 4,
    music: android.graphics.Typeface? = null
) {
    // Small gap after barlines: notes at a measure start don't touch the bar.
    val barGap = if (note.startBeat % measureBeats == 0.0) geometry.pxPerStep * 2.0f else 0f
    val qBeat = kotlin.math.round(note.startBeat / BeamTune.quantizeGrid) * BeamTune.quantizeGrid
    val x = geometry.beatToX(qBeat) + barGap
    val y = geometry.stepToY(step, canvasHeight)
    // Sounding notes glow green; selected notes are filled red.
    val noteColor = when {
        sounding -> SoundingGreen
        editSelected -> Color.Red
        else -> color
    }
    // Slanted elliptical notehead (~1.35:1), stem, and flags sized from the
    // staff geometry instead of fixed pixels.
    val rx = geometry.noteRadiusPx * 1.35f
    val ry = geometry.noteRadiusPx * 0.95f
    val stemLength = geometry.pxPerStep * 7f // one octave, the standard stem length
    // Scale factor from the staff geometry (1.0 at zoom=1): keeps stems and
    // flags proportional when zoomed out instead of fixed-pixel.
    val z = geometry.pxPerStep / 16f
    val stemWidth = 2.5f * z

    // Ledger lines for notes outside the 5-line staff.
    if (step < BOTTOM_LINE_STEP) {
        var s = BOTTOM_LINE_STEP - 2
        while (s >= step) {
            val ly = geometry.stepToY(s, canvasHeight)
            canvas.drawLine(StaffLine, x - rx - 4f, ly, x + rx + 4f, ly, 2f)
            s -= 2
        }
    } else if (step > TOP_LINE_STEP) {
        var s = TOP_LINE_STEP + 2
        while (s <= step) {
            val ly = geometry.stepToY(s, canvasHeight)
            canvas.drawLine(StaffLine, x - rx - 4f, ly, x + rx + 4f, ly, 2f)
            s += 2
        }
    }

    // Notehead: hollow for whole/half notes, filled otherwise. Sounding
    // (currently playing) notes are always filled so the green is obvious.
    val hollow = note.durationBeats >= 2.0 && !sounding
    canvas.withRotation(-35f, x, y) {
        drawOval(noteColor, x - rx, y - ry, x + rx, y + ry, fill = !hollow, strokeWidth = 2.5f)
    }

    // Sharp ("#") / flat ("b") sign left of the notehead. Plain ASCII is
    // used deliberately: the Unicode music glyphs are missing from some
    // device fonts and would render as tofu boxes.
    if (glyph != null) {
        val textSize = ry * 3f
        canvas.drawText(glyph, x - rx - 12f, y + textSize * 0.35f, noteColor, textSize, null, bold = true, center = true)
    }

    // Whole notes have no stem; beamed notes get their stem extended to
    // the shared beam (flags suppressed) by the caller.
    if (note.durationBeats >= 4.0) return

    val stemUp = stemUpOverride ?: (step < 34) // below the middle line -> stem up on the right side
    val sx = if (stemUp) x + rx * 0.85f else x - rx * 0.85f
    val tipY = stemTipYOverride ?: (if (stemUp) y - stemLength else y + stemLength)
    canvas.drawLine(noteColor, sx, y, sx, tipY, stemWidth)

    if (!drawFlags) return
    val fc = flagCount(note.durationBeats)
    if (fc > 0 && music != null) {
        // SMuFL flag glyphs (Bravura): engraved, not hand-drawn.
        val flagGlyph = when {
            fc >= 2 && stemUp -> "\uE242" // flag16thUp
            fc >= 2 && !stemUp -> "\uE243" // flag16thDown
            stemUp -> "\uE240" // flag8thUp
            else -> "\uE241" // flag8thDown
        }
        // Flag glyphs attach at the stem tip; scale to staff geometry.
        val flagSize = geometry.pxPerStep * 6f
        // SMuFL flags: origin at stem tip. For up-stems, glyph extends right;
        // for down-stems, it extends left (drawn mirrored via x-offset).
        val fx = if (stemUp) sx else sx
        canvas.drawText(flagGlyph, fx, tipY, noteColor, flagSize, music, bold = false, center = false)
    }
}

/** Flag count for a duration: 2 for sixteenths, 1 for eighths, else 0. */
data class BeamSettings(
    val quantizeGrid: Double = 0.25,
    val ioiThreshold: Double = 0.75, // lenient for human timing (Bobby 2026-10-14)
    val beamThickness: Float = 0.3f
)

/** Simple global beam config - UI updates this, version counter forces redraw. */
object BeamTune {
    @Volatile var quantizeGrid: Double = 0.25
    @Volatile var ioiThreshold: Double = 0.75
    @Volatile var restThreshold: Double = 0.5
    @Volatile var minDuration: Double = 0.25
}



fun flagCount(durationBeats: Double): Int {
    // Quantize to 16th grid for human recordings (Bobby 2026-10-14).
    val q = kotlin.math.round(durationBeats / 0.25) * 0.25
    return when {
        q < 0.375 -> 2 // sixteenth (quantized 0.25)
        q < 0.75 -> 1 // eighth (quantized 0.5)
        else -> 0
    }
}

/**
 * Groups contiguous flagged notes (eighths/sixteenths [ordered] by start
 * beat) for beaming: a run breaks on an unflagged note or any rest (gap).
 * Returns note id -> its group (only groups of 2+ are included).
 */
fun beamGroups(ordered: List<ScoreNote>, measureBeats: Int): Map<Long, List<ScoreNote>> {
    val out = HashMap<Long, List<ScoreNote>>()
    var run = mutableListOf<ScoreNote>()
    fun flush() {
        if (run.size >= 2) {
            val group = run.toList()
            group.forEach { out[it.id] = group }
        }
        run = mutableListOf()
    }
    // Quantize to 16th grid for human recordings (Bobby 2026-10-14).
    // Use IOI (inter-onset interval) not gap: staccato notes still beam.
    fun q(b: Double): Double = kotlin.math.round(b / BeamTune.quantizeGrid) * BeamTune.quantizeGrid
    ordered.forEach { note ->
        val qDur = maxOf(q(note.durationBeats), BeamTune.minDuration)
        if (flagCount(qDur) == 0) {
            flush()
        } else {
            val prev = run.lastOrNull()
            if (prev != null) {
                val ioi = q(note.startBeat) - q(prev.startBeat)
                val newMeasure = (q(note.startBeat) % measureBeats) < 0.001
                // Break if IOI exceeds both thresholds or new measure.
                // Gap-hidden rests do not break beams.
                val effectiveThreshold = maxOf(BeamTune.ioiThreshold, BeamTune.restThreshold)
                if (ioi > effectiveThreshold || newMeasure) flush()
            }
            run.add(note)
        }
    }
    flush()
    return out
}

/**
 * Draws a beamed group: noteheads with stems extended to one shared beam
 * (no individual flags), one beam across the whole group plus a second beam
 * over each run of sixteenths (single sixteenths get a hook stub).
 */
fun drawBeamedGroup(
    canvas: ScoreCanvas,
    members: List<ScoreNote>,
    color: Color,
    clef: Clef,
    geometry: StaffGeometry,
    canvasHeight: Float,
    selectedNoteIds: Set<Long>,
    soundingNoteIds: Set<Long>,
    measureBeats: Int,
) {
    val steps = members.map { staffStepFor(it, clef) }
    val dirUp = steps.average() < 34.0
    val stemLength = geometry.pxPerStep * 7f
    val ys = steps.map { geometry.stepToY(it, canvasHeight) }
    val tips = ys.map { y -> if (dirUp) y - stemLength else y + stemLength }
    val beamY = if (dirUp) tips.min() else tips.max()

    members.forEachIndexed { index, note ->
        drawStaffNote(
            canvas, note, color, geometry, canvasHeight,
            step = steps[index],
            glyph = accidentalGlyph(note),
            editSelected = note.id in selectedNoteIds,
            sounding = note.id in soundingNoteIds,
            stemUpOverride = dirUp,
            stemTipYOverride = beamY,
            drawFlags = false
        )
    }

    val beamColor = when {
        members.any { it.id in soundingNoteIds } -> SoundingGreen
        members.any { it.id in selectedNoteIds } -> Color.Red
        else -> color
    }
    val rx = geometry.noteRadiusPx * 1.35f
    val sxs = members.map { note ->
        val barGap = if (note.startBeat % measureBeats == 0.0) geometry.pxPerStep * 2.0f else 0f
        val qBeat = kotlin.math.round(note.startBeat / BeamTune.quantizeGrid) * BeamTune.quantizeGrid
    val x = geometry.beatToX(qBeat) + barGap
        if (dirUp) x + rx * 0.85f else x - rx * 0.85f
    }
    val beamHalf = (geometry.pxPerStep * 0.3f).coerceAtLeast(2f)
    canvas.drawRect(beamColor, sxs.min(), beamY - beamHalf, sxs.max(), beamY + beamHalf, fill = true)
    // Second beam over sixteenth runs; lone sixteenths get a hook stub.
    val secondOff = beamHalf * 2f + geometry.pxPerStep * 1.1f
    val secondY = if (dirUp) beamY + secondOff else beamY - secondOff
    val hookLen = geometry.pxPerStep * 2.2f
    val sortedM = members.sortedBy { it.startBeat }
    val avgIoi = if (sortedM.size > 1) {
        var s = 0.0
        for (k in 0 until sortedM.size - 1) s += sortedM[k + 1].startBeat - sortedM[k].startBeat
        s / (sortedM.size - 1)
    } else 0.5
    val beamLevel = flagCount(avgIoi)
    var i = 0
    while (i < members.size) {
        if (beamLevel < 2) {
            break
        }
        var j = i
        while (j + 1 < members.size) j++
        val x0 = sxs[i]
        val x1 = sxs[j]
        if (j > i) {
            canvas.drawRect(beamColor, x0, secondY - beamHalf, x1, secondY + beamHalf, fill = true)
        } else {
            val hookX1 = if (dirUp) x0 + hookLen else x0 - hookLen
            canvas.drawRect(beamColor, minOf(x0, hookX1), secondY - beamHalf, maxOf(x0, hookX1), secondY + beamHalf, fill = true)
        }
        i = j + 1
    }
}

// ---------------------------------------------------------------------------
// Full lane + time strip, exactly as drawn in the UI's TrackLane / TimeStrip.
// ---------------------------------------------------------------------------

data class ScoreLaneParams(
    val geometry: StaffGeometry,
    val laneWidthPx: Float,
    val laneHeightPx: Float,
    val notes: List<ScoreNote>,
    val color: Color,
    val clef: Clef,
    val timeSigNum: Int,
    val timeSigDen: Int,
    val measureBeats: Int,
    val measureCount: Int,
    val music: Typeface?,
    val ink: Color,
    val soundingNoteIds: Set<Long> = emptySet(),
    val selectedNoteIds: Set<Long> = emptySet(),
    /** When true, beamed groups are drawn as individual flagged notes. */
    val forceUnbeamed: Boolean = false,
)

/** One staff lane: staff lines, clef + time signature, rests, barlines, notes. */
fun ScoreCanvas.drawScoreLane(p: ScoreLaneParams) {
    val g = p.geometry
    val canvasHeight = p.laneHeightPx

    // Staff lines
    for (i in 0 until 5) {
        val step = BOTTOM_LINE_STEP + i * 2
        val y = g.stepToY(step, canvasHeight)
        drawLine(StaffLine, 0f, y, p.laneWidthPx, y, 2f)
    }

    // Clef + time signature at the start of the lane, before beat 0.
    drawClefAndTimeSig(
        this, p.clef, p.timeSigNum, p.timeSigDen,
        g, canvasHeight, g.clefInsetPx, p.ink, p.music
    )
    // Rests fill the silences (whole rest per empty measure, halves /
    // quarters / eighths for gaps). Drawn under the notes.
    if (p.music != null) {
        drawRestMarks(this, computeRests(p.notes, p.measureBeats, p.measureCount), p.music, g, canvasHeight, p.ink)
    }

    // Measure barlines: bold full-strength strokes (scaled with zoom),
    // every 4th measure accented, final barline light-heavy.
    val topY = g.stepToY(TOP_LINE_STEP, canvasHeight)
    val bottomY = g.stepToY(BOTTOM_LINE_STEP, canvasHeight)
    val barWidth = (g.pxPerStep * 0.45f).coerceAtLeast(3.5f)
    for (m in 0..p.measureCount) {
        val x = g.beatToX((m * p.measureBeats).toDouble())
        if (m == p.measureCount) {
            drawLine(StaffLine, x - barWidth * 2.4f, topY, x - barWidth * 2.4f, bottomY, barWidth * 0.7f)
            drawLine(StaffLine, x, topY, x, bottomY, barWidth * 2.2f)
        } else {
            drawLine(
                StaffLine, x, topY, x, bottomY,
                if (m % 4 == 0) barWidth * 1.8f else barWidth
            )
        }
    }

    // This lane's notes only. Sounding notes glow green while they
    // play; selected notes are filled red instead of an outline.
    // Consecutive eighth/sixteenth notes within a beat share beams; the
    // rest keep individual flags.
    val ordered = p.notes.sortedBy { it.startBeat }
    val groupsById = if (p.forceUnbeamed) emptyMap() else beamGroups(ordered, p.measureBeats)
    ordered.forEach { note ->
        val group = groupsById[note.id]
        if (group != null && note.id == group.first().id) {
            drawBeamedGroup(
                this, group, p.color, p.clef,
                g, canvasHeight, p.selectedNoteIds, p.soundingNoteIds,
                p.measureBeats
            )
        } else if (group == null) {
            // Extend duration to fill hidden gap (Bobby 2026-10-14)
            val idx = ordered.indexOf(note)
            val next = ordered.getOrNull(idx + 1)
            val effectiveDur = if (next != null) {
                val gap = next.startBeat - note.startBeat
                // If gap is hidden by Gap threshold, extend note to fill it
                if (gap <= BeamTune.restThreshold + 0.1) gap else note.durationBeats
            } else note.durationBeats
            val displayNote = if (effectiveDur != note.durationBeats) note.copy(durationBeats = effectiveDur) else note
            drawStaffNote(
                this,
                displayNote,
                p.color,
                g,
                canvasHeight,
                step = staffStepFor(note, p.clef),
                glyph = accidentalGlyph(note),
                editSelected = note.id in p.selectedNoteIds,
                sounding = note.id in p.soundingNoteIds,
                measureBeats = p.measureBeats,
                music = p.music
            )
        }
        // Non-first members of a beamed group are drawn with the group.
    }
}

data class ScoreTimeStripParams(
    val geometry: StaffGeometry,
    val widthPx: Float,
    val heightPx: Float,
    val measureBeats: Int,
    val measureCount: Int,
    val bg: Color,
    val fg: Color,
    val playheadBeat: Double
)

/** Time ruler: quarter-note ticks, bar numbers, playhead marker. */
fun ScoreCanvas.drawScoreTimeStrip(p: ScoreTimeStripParams) {
    val h = p.heightPx
    drawRect(p.bg, 0f, 0f, p.widthPx, h, fill = true)
    val totalBeats = p.measureBeats * p.measureCount
    var beat = 0.0
    var guard = 0
    while (beat <= totalBeats.toDouble() + 1e-9 && guard < 5000) {
        val x = p.geometry.beatToX(beat)
        val barRem = beat % p.measureBeats.toDouble()
        val isBar = barRem < 1e-6 || (p.measureBeats.toDouble() - barRem) < 1e-6
        val beatRem = beat % 1.0
        val isBeat = beatRem < 1e-6 || (1.0 - beatRem) < 1e-6
        val (tickLen, width) = when {
            isBar -> (h * 0.72f) to 3.5f
            isBeat -> (h * 0.50f) to 2.5f
            else -> (h * 0.30f) to 1.5f
        }
        val c = when {
            isBar -> p.fg
            isBeat -> p.fg.copy(alpha = 0.8f)
            else -> p.fg.copy(alpha = 0.55f)
        }
        drawLine(c, x, h - tickLen, x, h, width)
        if (isBar) {
            val bar = (beat / p.measureBeats.toDouble()).roundToInt() + 1
            drawText(bar.toString(), x + 5f, h - tickLen - 6f, p.fg, h * 0.30f, null, bold = true, center = false)
        }
        beat += 1.0
        guard++
    }
    // Playhead marker on the strip.
    if (p.playheadBeat >= 0.0) {
        val x = p.geometry.beatToX(p.playheadBeat)
        drawLine(PlayheadRed, x, 0f, x, h, 3.5f)
        drawCircle(PlayheadRed, x, 7f, 6f)
    }
}

/** Single playhead line spanning all lanes (dark red, like the UI overlay). */
fun ScoreCanvas.drawScorePlayhead(x: Float, top: Float, bottom: Float, widthPx: Float, color: Color = PlayheadRed) {
    drawRect(color, x, top, x + widthPx, bottom, fill = true)
}
