package com.robert.noteforge.audio

import com.robert.noteforge.data.ProjectData
import com.robert.noteforge.data.ProjectNoteData
import com.robert.noteforge.data.ProjectSettingsData
import com.robert.noteforge.data.ProjectTrackData
import java.util.ArrayDeque

/**
 * Minimal Standard MIDI File (SMF) importer. Reads format 0/1/2 songs with
 * metrical timing, pairing note-on/off events into notes. Each MIDI track
 * chunk containing notes becomes one app track (instrument resolved on
 * import, falling back to the first instrument). Ticks convert directly to
 * quarter-note beats via the file's division; the first set-tempo event
 * supplies the project tempo.
 */
object MidiParser {

    fun parse(bytes: ByteArray): ProjectData {
        val reader = Reader(bytes)
        require(reader.readAscii(4) == "MThd") { "Not a MIDI file (missing MThd)." }
        val headerLen = reader.readInt32()
        require(headerLen == 6) { "Unsupported MIDI header length: $headerLen." }
        reader.readU16() // format (0/1/2 all handled the same here)
        val trackCount = reader.readU16()
        val division = reader.readU16()
        require(division and 0x8000 == 0) { "SMPTE-timed MIDI files are not supported." }
        require(division > 0) { "Invalid MIDI time division." }
        require(trackCount in 1..128) { "Invalid MIDI track count: $trackCount." }

        data class OpenNote(val startTick: Long, val velocity: Int)
        data class DoneNote(
            val chunk: Int,
            val channel: Int,
            val pitch: Int,
            val startTick: Long,
            val endTick: Long,
            val velocity: Int
        )

        val chunkNames = mutableListOf<String?>()
        val done = mutableListOf<DoneNote>()
        val open = HashMap<Triple<Int, Int, Int>, ArrayDeque<OpenNote>>()
        var tempoBpm = 120
        var tempoSet = false

        repeat(trackCount) { chunk ->
            if (reader.remaining() < 8) return@repeat
            val id = reader.readAscii(4)
            val len = reader.readInt32()
            require(len >= 0) { "Invalid MIDI chunk length." }
            if (id != "MTrk") {
                reader.skip(len)
                chunkNames.add(null)
                return@repeat
            }
            val end = reader.pos + len
            require(end <= bytes.size) { "Truncated MIDI file." }
            var tick = 0L
            var status = 0
            var name: String? = null
            while (reader.pos < end) {
                tick += reader.readVlq()
                var b = reader.readU8()
                if (b < 0x80) {
                    // Running status: data byte already consumed as first param.
                    require(status != 0) { "Invalid MIDI running status." }
                    reader.pos--
                } else {
                    status = b
                }
                when {
                    status == 0xFF -> {
                        val type = reader.readU8()
                        val n = reader.readVlq()
                        if (type == 0x51 && n == 3L && !tempoSet) {
                            val mpq = (reader.readU8() shl 16) or (reader.readU8() shl 8) or reader.readU8()
                            if (mpq > 0) tempoBpm = (60_000_000.0 / mpq).toInt().coerceIn(20, 300)
                            tempoSet = true
                        } else if (type == 0x03 && name == null) {
                            val raw = reader.readBytes(n)
                            name = String(raw, Charsets.UTF_8).trim().take(64).ifEmpty { null }
                        } else {
                            reader.skip(n)
                        }
                        if (type == 0x2F) break
                    }
                    status == 0xF0 || status == 0xF7 -> reader.skip(reader.readVlq())
                    status == 0xF1 || status == 0xF3 -> reader.skip(1)
                    status == 0xF2 -> reader.skip(2)
                    status == 0xF6 || status >= 0xF8 -> { /* no data bytes */ }
                    status >= 0x80 -> {
                        val cmd = status and 0xF0
                        val channel = status and 0x0F
                        val first = reader.readU8()
                        if (cmd == 0xC0 || cmd == 0xD0) {
                            // 1 data byte; nothing to do.
                        } else {
                            val second = reader.readU8()
                            if (cmd == 0x90 && second > 0) {
                                open.getOrPut(Triple(chunk, channel, first)) { ArrayDeque() }
                                    .addLast(OpenNote(tick, second))
                            } else if (cmd == 0x80 || (cmd == 0x90 && second == 0)) {
                                val stack = open[Triple(chunk, channel, first)]
                                val start = stack?.pollLast()
                                if (start != null) {
                                    done.add(DoneNote(chunk, channel, first, start.startTick, tick, start.velocity))
                                }
                            }
                        }
                    }
                }
            }
            // Close dangling note-ons at end of track.
            for ((key, stack) in open) {
                if (key.first != chunk) continue
                while (true) {
                    val start = stack.pollFirst() ?: break
                    done.add(DoneNote(chunk, key.second, key.third, start.startTick, tick, start.velocity))
                }
            }
            chunkNames.add(name)
            reader.pos = end
        }

        if (done.isEmpty()) throw IllegalArgumentException("No notes found in MIDI file.")
        if (done.size > 100_000) throw IllegalArgumentException("Too many notes (${done.size}).")

        // Separate drum channel (MIDI ch 10, index 9) from melodic channels.
        // Each unique drum pitch gets its own track with the GM drum type,
        // so playback can assign the correct drum instrument and never miss events.
        val drumNotes = done.filter { it.channel == 9 }
        val melodicNotes = done.filter { it.channel != 9 }

        val chunksWithNotes = melodicNotes.map { it.chunk }.distinct().sorted().take(64)
        val trackIndexByChunk = chunksWithNotes.mapIndexed { i, c -> c to i }.toMap()

        val tracks = mutableListOf<ProjectTrackData>()
        // Melodic tracks first
        chunksWithNotes.forEachIndexed { i, c ->
            tracks.add(
                ProjectTrackData(
                    instrumentId = -1,
                    name = chunkNames.getOrNull(c) ?: "MIDI ${i + 1}",
                    orderIndex = i,
                    muted = false,
                    solo = false
                )
            )
        }
        // Single drum track for all channel 10 notes.
        // The track uses a drum kit instrument; the sequencer looks up
        // each note's pitch in the kit's GM map during playback.
        val drumTrackIndex: Int? = if (drumNotes.isNotEmpty()) {
            val idx = tracks.size
            tracks.add(
                ProjectTrackData(
                    instrumentId = -1,
                    name = "Drums",
                    orderIndex = idx,
                    muted = false,
                    solo = false,
                    drumType = "KIT" // marker: this track uses the drum kit
                )
            )
            idx
        } else null

        val notes = done.mapNotNull { n ->
            // Drum notes go to the single drum track; others to chunk track
            val idx = if (n.channel == 9) {
                drumTrackIndex ?: return@mapNotNull null
            } else {
                trackIndexByChunk[n.chunk] ?: return@mapNotNull null
            }
            val durBeats = ((n.endTick - n.startTick).toDouble() / division).coerceIn(0.125, 16.0)
            ProjectNoteData(
                trackIndex = idx,
                pitchMidi = n.pitch.coerceIn(0, 127),
                startBeat = (n.startTick.toDouble() / division).coerceAtLeast(0.0),
                durationBeats = durBeats,
                velocity = (0.2f + (n.velocity / 127f) * 1.1f).coerceIn(0.05f, 2.0f),
                accidental = 0
            )
        }
        return ProjectData(
            settings = ProjectSettingsData(tempoBpm = tempoBpm, beatsPerMeasure = 4),
            tracks = tracks,
            notes = notes
        )
    }

    private class Reader(private val bytes: ByteArray) {
        var pos = 0

        fun remaining(): Int = bytes.size - pos

        private fun need(n: Int) {
            if (pos + n > bytes.size) throw IllegalArgumentException("Truncated MIDI file.")
        }

        fun readU8(): Int {
            need(1)
            return bytes[pos++].toInt() and 0xFF
        }

        fun readU16(): Int = (readU8() shl 8) or readU8()

        fun readInt32(): Int =
            (readU8() shl 24) or (readU8() shl 16) or (readU8() shl 8) or readU8()

        fun readAscii(n: Int): String {
            need(n)
            val s = String(bytes, pos, n, Charsets.US_ASCII)
            pos += n
            return s
        }

        fun readVlq(): Long {
            var value = 0L
            repeat(4) {
                val b = readU8()
                value = (value shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) return value
            }
            throw IllegalArgumentException("Invalid MIDI variable-length value.")
        }

        fun readBytes(n: Long): ByteArray {
            if (n < 0 || n > 8_000_000) throw IllegalArgumentException("Invalid MIDI length.")
            need(n.toInt())
            val out = bytes.copyOfRange(pos, pos + n.toInt())
            pos += n.toInt()
            return out
        }

        fun skip(n: Int) {
            // readVlq lengths are Long; clamp defensively.
            if (n > Int.MAX_VALUE) throw IllegalArgumentException("Invalid MIDI chunk length.")
            need(n)
            pos += n
        }

        fun skip(n: Long) {
            if (n < 0 || n > Int.MAX_VALUE) throw IllegalArgumentException("Invalid MIDI length.")
            skip(n.toInt())
        }
    }
}
