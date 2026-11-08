package com.robert.noteforge.audio

import com.robert.noteforge.data.InstrumentRepository
import com.robert.noteforge.data.ScoreNote
import com.robert.noteforge.data.Track
import com.robert.noteforge.synth.Note
import com.robert.noteforge.synth.SynthEngine
import com.robert.noteforge.synth.patchFromJson
import com.robert.noteforge.synth.toMonoPcm16
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil

/**
 * Offline high-quality audio renderer. Mixes the full song to 44.1kHz PCM,
 * respecting track volume, mute, and solo. Used for video export.
 */
@Singleton
class MixdownRenderer @Inject constructor(
    private val instrumentRepository: InstrumentRepository
) {
    companion object {
        const val MIXDOWN_SR = 44100
        /** Extra tail after the last note for reverb/delay decay. */
        private const val TAIL_SEC = 2.0f
    }

    /**
     * Renders the song to mono 16-bit PCM at 44.1kHz.
     * @param onProgress called with 0..1 as voices complete
     */
    suspend fun render(
        notes: List<ScoreNote>,
        tracks: List<Track>,
        tempoBpm: Int,
        onProgress: (Float) -> Unit = {}
    ): ShortArray {
        val sr = MIXDOWN_SR
        val msPerBeat = 60_000.0 / tempoBpm

        val trackById = tracks.associateBy { it.id }
        val soloIds = tracks.filter { it.solo }.map { it.id }.toSet()

        // Filter to audible notes
        val audible = notes.filter { note ->
            val track = trackById[note.trackId] ?: return@filter false
            if (track.muted) return@filter false
            if (soloIds.isNotEmpty() && track.id !in soloIds) return@filter false
            true
        }
        if (audible.isEmpty()) return ShortArray(0)

        val endBeat = audible.maxOf { it.startBeat + it.durationBeats }
        val totalSec = endBeat * msPerBeat / 1000.0 + TAIL_SEC
        val totalSamples = ceil(totalSec * sr).toInt()
        val mix = FloatArray(totalSamples)

        // Cache patches by instrument
        val patchCache = mutableMapOf<Long, com.robert.noteforge.synth.Patch?>()
        val voiceCache = mutableMapOf<String, ShortArray>()

        for ((idx, note) in audible.withIndex()) {
            val track = trackById[note.trackId] ?: continue
            val patch = patchCache.getOrPut(track.instrumentId) {
                try {
                    val inst = instrumentRepository.getById(track.instrumentId)
                    inst?.synthPatchJson?.let { patchFromJson(JSONObject(it)) }
                } catch (_: Exception) { null }
            } ?: continue

            // Voice key: patch hash + pitch + duration + velocity level
            val velLevel = when {
                note.velocity >= 0.85f -> 2
                note.velocity >= 0.5f -> 1
                else -> 0
            }
            val key = "${patch.hashCode()}_${note.pitchMidi}_${note.durationBeats}_$velLevel"
            val voice = voiceCache.getOrPut(key) {
                val sp = patch.copy(
                    tempo = tempoBpm.toFloat(),
                    notes = listOf(Note(note.pitchMidi - 33, note.durationBeats.toFloat()))
                )
                val velValue = when (velLevel) {
                    2 -> 1.0f
                    1 -> 0.7f
                    else -> 0.4f
                }
                toMonoPcm16(
                    SynthEngine(sr).render(sp, tailSec = 1.8f, velocity = velValue)
                )
            }

            val offset = (note.startBeat * msPerBeat / 1000.0 * sr).toInt()
            val gain = track.volume.coerceIn(0f, 1f) * note.velocity
            for (i in voice.indices) {
                val pos = offset + i
                if (pos < mix.size) {
                    mix[pos] += (voice[i] / 32768f) * gain
                }
            }

            if (idx % 10 == 0) {
                onProgress(idx / audible.size.toFloat() * 0.5f)
                kotlinx.coroutines.yield()
            }
        }

        onProgress(0.5f)

        // Soft-clip and convert to 16-bit
        val out = ShortArray(mix.size)
        // Peak-normalize BEFORE the soft clip: if the mix is hot, scale it down
        // so the tanh only catches occasional peaks instead of constantly saturating.
        var peak = 0f
        for (v in mix) {
            val a = kotlin.math.abs(v)
            if (a > peak) peak = a
        }
        // Target -1dB (0.89) to leave headroom; only scale down, never up
        val normGain = if (peak > 0.89f) 0.89f / peak else 1f
        for (i in mix.indices) {
            val s = (mix[i] * normGain).coerceIn(-1f, 1f)
            // Gentle tanh soft clip for any residual peaks
            val shaped = kotlin.math.tanh(s * 1.2f) / kotlin.math.tanh(1.2f)
            out[i] = (shaped * 32767).toInt().toShort()
            if (i % 441000 == 0) {
                onProgress(0.5f + 0.5f * i / mix.size)
                kotlinx.coroutines.yield()
            }
        }

        onProgress(1f)
        return out
    }
}
