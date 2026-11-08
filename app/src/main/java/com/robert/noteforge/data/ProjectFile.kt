package com.robert.noteforge.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Portable project file (v1 JSON). Tracks are stored in order and notes
 * reference them by track index (not device-local row IDs) so files can be
 * opened on any install. Instruments are intentionally NOT embedded: the
 * instrument library lives in the app database, so tracks store the
 * instrument ID when it still exists and fall back on import.
 */
data class ProjectTrackData(
    val instrumentId: Long,
    val name: String,
    val orderIndex: Int,
    val muted: Boolean,
    val solo: Boolean,
    val noteColorArgb: Int? = null,
    val volume: Float = 1f,
    val clef: String = "TREBLE",
    val drumType: String? = null
)

data class ProjectNoteData(
    val trackIndex: Int,
    val pitchMidi: Int,
    val startBeat: Double,
    val durationBeats: Double,
    val velocity: Float,
    val accidental: Int
)

data class ProjectSettingsData(
    val tempoBpm: Int,
    val beatsPerMeasure: Int,
    val timeSigNum: Int = 4,
    val timeSigDen: Int = 4
)

data class ProjectData(
    val settings: ProjectSettingsData,
    val tracks: List<ProjectTrackData>,
    val notes: List<ProjectNoteData>
)

object ProjectFileCodec {
    const val FILE_VERSION = 1

    fun encode(data: ProjectData): String {
        val root = JSONObject()
        root.put("app", "NoteForge")
        root.put("version", FILE_VERSION)
        val settings = JSONObject()
        settings.put("tempoBpm", data.settings.tempoBpm)
        settings.put("beatsPerMeasure", data.settings.beatsPerMeasure)
        settings.put("timeSigNum", data.settings.timeSigNum)
        settings.put("timeSigDen", data.settings.timeSigDen)
        root.put("settings", settings)
        val tracks = JSONArray()
        data.tracks.forEach { t ->
            val o = JSONObject()
            o.put("instrumentId", t.instrumentId)
            o.put("name", t.name)
            o.put("orderIndex", t.orderIndex)
            o.put("muted", t.muted)
            o.put("solo", t.solo)
            if (t.noteColorArgb != null) o.put("noteColorArgb", t.noteColorArgb)
            o.put("volume", t.volume.toDouble())
            o.put("clef", t.clef)
            tracks.put(o)
        }
        root.put("tracks", tracks)
        val notes = JSONArray()
        data.notes.forEach { n ->
            val o = JSONObject()
            o.put("trackIndex", n.trackIndex)
            o.put("pitchMidi", n.pitchMidi)
            o.put("startBeat", n.startBeat)
            o.put("durationBeats", n.durationBeats)
            o.put("velocity", n.velocity.toDouble())
            o.put("accidental", n.accidental)
            notes.put(o)
        }
        root.put("notes", notes)
        return root.toString(2)
    }

    fun decode(json: String): ProjectData {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw IllegalArgumentException("Not a valid NoteForge project file.")
        }
        if (root.optString("app") != "NoteForge") {
            throw IllegalArgumentException("Not a NoteForge project file.")
        }
        val version = root.optInt("version", 1)
        if (version != FILE_VERSION) {
            throw IllegalArgumentException("Unsupported project version: $version.")
        }
        val settingsJson = root.optJSONObject("settings") ?: JSONObject()
        val settingsBeats = settingsJson.optInt("beatsPerMeasure", 4).coerceIn(1, 12)
        val settings = ProjectSettingsData(
            tempoBpm = settingsJson.optInt("tempoBpm", 120).coerceIn(20, 300),
            beatsPerMeasure = settingsBeats,
            timeSigNum = settingsJson.optInt("timeSigNum", settingsBeats).coerceIn(1, 12),
            timeSigDen = settingsJson.optInt("timeSigDen", 4).coerceIn(2, 16)
        )
        val tracksJson = root.optJSONArray("tracks") ?: JSONArray()
        if (tracksJson.length() > 64) {
            throw IllegalArgumentException("Too many tracks (${tracksJson.length()}).")
        }
        val tracks = mutableListOf<ProjectTrackData>()
        for (i in 0 until tracksJson.length()) {
            val o = tracksJson.optJSONObject(i) ?: continue
            tracks.add(
                ProjectTrackData(
                    instrumentId = o.optLong("instrumentId", -1),
                    name = o.optString("name", "Track ${i + 1}").take(64).ifEmpty { "Track ${i + 1}" },
                    orderIndex = o.optInt("orderIndex", i),
                    muted = o.optBoolean("muted", false),
                    solo = o.optBoolean("solo", false),
                    noteColorArgb = if (o.has("noteColorArgb")) o.optInt("noteColorArgb") else null,
                    volume = o.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f),
                    clef = o.optString("clef", "TREBLE").take(16).ifEmpty { "TREBLE" }
                )
            )
        }
        val notesJson = root.optJSONArray("notes") ?: JSONArray()
        if (notesJson.length() > 20_000) {
            throw IllegalArgumentException("Too many notes (${notesJson.length()}).")
        }
        val notes = mutableListOf<ProjectNoteData>()
        for (i in 0 until notesJson.length()) {
            val o = notesJson.optJSONObject(i) ?: continue
            val trackIndex = o.optInt("trackIndex", -1)
            if (trackIndex !in tracks.indices) continue
            notes.add(
                ProjectNoteData(
                    trackIndex = trackIndex,
                    pitchMidi = o.optInt("pitchMidi", 60).coerceIn(0, 127),
                    startBeat = o.optDouble("startBeat", 0.0).coerceAtLeast(0.0),
                    durationBeats = o.optDouble("durationBeats", 1.0).coerceIn(0.125, 16.0),
                    velocity = o.optDouble("velocity", 1.0).toFloat().coerceIn(0.05f, 2.0f),
                    accidental = o.optInt("accidental", 0).coerceIn(-1, 1)
                )
            )
        }
        if (tracks.isEmpty() && notes.isNotEmpty()) {
            throw IllegalArgumentException("Project has notes but no tracks.")
        }
        return ProjectData(settings, tracks, notes)
    }
}
