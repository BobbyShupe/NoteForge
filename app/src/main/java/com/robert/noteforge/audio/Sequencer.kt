package com.robert.noteforge.audio

import com.robert.noteforge.data.Instrument
import com.robert.noteforge.data.InstrumentRepository
import com.robert.noteforge.data.ScoreNote
import com.robert.noteforge.data.Track
import com.robert.noteforge.synth.Note
import com.robert.noteforge.synth.Patch
import com.robert.noteforge.synth.SynthEngine
import com.robert.noteforge.synth.patchFromJson
import com.robert.noteforge.synth.toMonoPcm16
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/**
 * Walks the placed notes in beat order and triggers each through the
 * AudioEngine at the right wall-clock offset for the current tempo.
 *
 * Every unique voice (patch x pitch x duration x tempo) is rendered once,
 * up front and in parallel, before the playhead starts moving — renders are
 * seed-deterministic, so the cache is exact. Playback itself is then just
 * cache lookups: zero per-note processing delay. Voices render at
 * [RENDER_SR] (half rate) for speed; the difference is inaudible for synth.
 */
/**
 * Marker for drum kit instruments. The actual kit (pitch -> DrumPatch)
 * is loaded from SharedPreferences at render time.
 */
private object DrumKitMarker

@Singleton
class Sequencer @Inject constructor(
    private val audioEngine: AudioEngine,
    private val instrumentRepository: InstrumentRepository,
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context
) {
    init {
        // Restore the user's quality preference at startup.
        RENDER_SR = QualityPrefs.getSampleRate(appContext)
    }
    private val scope = CoroutineScope(Dispatchers.Default)
    private var playJob: Job? = null

    /** Loads the user's drum kit from SharedPreferences. */
    private fun loadDrumKit(): com.robert.noteforge.ui.drumstab.DrumKit {
        return try {
            com.robert.noteforge.ui.drumstab.loadKit(appContext)
        } catch (_: Exception) {
            com.robert.noteforge.ui.drumstab.DrumKit.default()
        }
    }

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playheadBeat = MutableStateFlow(0.0)
    val playheadBeat: StateFlow<Double> = _playheadBeat.asStateFlow()

    /** Wall-clock basis for the playhead, so the UI can draw it independently every frame. */
    data class PlaybackClock(
        val startTimeMillis: Long,
        val fromBeat: Double,
        val msPerBeat: Double,
        val endBeat: Double
    )
    private val _playbackClock = MutableStateFlow<PlaybackClock?>(null)
    val playbackClock: StateFlow<PlaybackClock?> = _playbackClock.asStateFlow()

    private val _soundingIds = MutableStateFlow<Set<Long>>(emptySet())
    val soundingIds: StateFlow<Set<Long>> = _soundingIds.asStateFlow()

    private data class VoiceKey(
        val patchHash: Int,
        val pitchMidi: Int,
        val durationBeats: Double,
        val tempoBpm: Int,
        val velLevel: Int
    )

    // Velocity quantized to 3 levels so the vel->cutoff render stays cacheable.
    private fun velLevel(v: Float) = when {
        v < 0.4f -> 0
        v < 0.75f -> 1
        else -> 2
    }
    private fun velValue(level: Int) = floatArrayOf(0.3f, 0.65f, 1f)[level.coerceIn(0, 2)]

    // Rendered voices, shared across plays so replaying a score is instant.
    // Keyed by patch content hash, so editing a patch re-renders automatically.
    private val voiceCache = ConcurrentHashMap<VoiceKey, ShortArray>()
    /** Live per-track volumes, consulted at note launch; updated mid-playback. */
    private val trackVolumes = ConcurrentHashMap<Long, Float>()
    /** Live mute/solo sets and instrument mapping, updated mid-playback. */
    private val mutedTracks = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val soloTracks = java.util.Collections.newSetFromMap(ConcurrentHashMap<Long, Boolean>())
    private val liveInstrumentByTrack = ConcurrentHashMap<Long, Long>()
    private val liveInstrumentCache = ConcurrentHashMap<Long, Instrument?>()
    private val livePatchCache = ConcurrentHashMap<Long, Any?>()
    @Volatile private var currentNotes: List<ScoreNote> = emptyList()
    @Volatile private var currentTempoBpm: Int = 120
    /** Previous instrument per track: keeps sounding until the new one's voices are cached. */
    private val previousInstrumentByTrack = ConcurrentHashMap<Long, Long>()

    /** Renders one voice: the patch's single tone at the exact pitch,
     *  including its full release tail. */
    private fun renderVoice(patch: Any, key: VoiceKey): ShortArray {
        // Drum kit: look up the pitch in the GM kit map, render that drum
        if (patch is DrumKitMarker) {
            val kit = loadDrumKit()
            val drumPatch = kit.getPatch(key.pitchMidi)
                ?: com.robert.noteforge.synth.DrumPatch.random(
                    key.pitchMidi.toLong(),
                    com.robert.noteforge.synth.GmDrumMap.drumTypeForPitch(key.pitchMidi)
                        ?: com.robert.noteforge.synth.DrumType.SNARE,
                    com.robert.noteforge.synth.DrumBias()
                )
            return com.robert.noteforge.synth.DrumSynth.renderToShort(drumPatch, RENDER_SR)
        }
        // Single drum instrument: render via DrumSynth (pitch-independent one-shot)
        if (patch is com.robert.noteforge.synth.DrumPatch) {
            return com.robert.noteforge.synth.DrumSynth.renderToShort(patch, RENDER_SR)
        }
        val melodicPatch = patch as Patch
        val sp = melodicPatch.copy(
            tempo = key.tempoBpm.toFloat(),
            notes = listOf(Note(key.pitchMidi - 33, key.durationBeats.toFloat()))
        )
        // Fresh engine per render: render() reseeds its noise source, so a
        // shared instance would corrupt concurrent renders. The tail must
        // cover not just the ADSR release but the delay-feedback and reverb
        // tails too — 1.8s, matching the instrument preview, so playback
        // renders the full patch exactly as auditioned.
        return toMonoPcm16(SynthEngine(RENDER_SR).render(sp, tailSec = 1.8f, velocity = velValue(key.velLevel)))
    }

    private fun isAudible(trackId: Long): Boolean {
        if (trackId in mutedTracks) return false
        if (soloTracks.isNotEmpty() && trackId !in soloTracks) return false
        return true
    }

    /** Live mute: stops sounding voices immediately; future notes are skipped. */
    fun setTrackMuted(trackId: Long, muted: Boolean) {
        if (muted) {
            mutedTracks.add(trackId)
            audioEngine.stopTrackVoices(trackId)
        } else {
            mutedTracks.remove(trackId)
        }
    }

    /** Live solo: re-evaluates all sounding voices against the new solo set. */
    fun setTrackSolo(trackId: Long, solo: Boolean) {
        if (solo) soloTracks.add(trackId) else soloTracks.remove(trackId)
        // Stop voices that are no longer audible under the new solo state.
        // (Voices that become audible will start with future notes.)
        for ((tid, _) in trackVolumes) {
            if (!isAudible(tid)) audioEngine.stopTrackVoices(tid)
        }
    }

    /** Live instrument change: future notes use the new instrument. */
    fun setTrackInstrument(trackId: Long, instrumentId: Long) {
        val oldId = liveInstrumentByTrack[trackId]
        if (oldId != null && oldId != instrumentId) {
            previousInstrumentByTrack[trackId] = oldId
        }
        liveInstrumentByTrack[trackId] = instrumentId
        scope.launch {
            try {
                val inst = instrumentRepository.getById(instrumentId)
                val patchJson = inst?.synthPatchJson ?: return@launch
                val patch: Any = try {
                    val json = JSONObject(patchJson)
                    when {
                        json.optBoolean("isDrumKit", false) -> DrumKitMarker
                        json.optBoolean("isDrum", false) -> {
                            com.robert.noteforge.synth.DrumPatch.fromJson(patchJson) ?: return@launch
                        }
                        else -> patchFromJson(json)
                    }
                } catch (_: Exception) { return@launch }
                liveInstrumentCache[instrumentId] = inst
                livePatchCache[instrumentId] = patch
                // Render this track's voices for the new patch so upcoming
                // notes find them in the cache instead of going silent.
                val patchHash = patchJson.hashCode()
                val tempo = currentTempoBpm
                currentNotes.filter { it.trackId == trackId }
                    .map { note ->
                        VoiceKey(
                            patchHash = patchHash,
                            pitchMidi = note.pitchMidi,
                            durationBeats = note.durationBeats,
                            tempoBpm = tempo,
                            velLevel = velLevel(note.velocity)
                        ) to patch
                    }
                    .distinctBy { it.first }
                    .filter { (key, _) -> !voiceCache.containsKey(key) }
                    .map { (key, p) ->
                        async {
                            try {
                                voiceCache[key] = renderVoice(p, key)
                            } catch (_: Exception) {}
                        }
                    }
                    .awaitAll()
                // New voices are ready: drop the fallback, but only if the
                // user hasn't switched to yet another instrument since.
                if (liveInstrumentByTrack[trackId] == instrumentId) {
                    previousInstrumentByTrack.remove(trackId)
                }
            } catch (_: Exception) {}
        }
    }

    /** Sets render quality; cached voices are discarded (wrong sample rate). */
    fun setQuality(sampleRate: Int) {
        if (sampleRate == RENDER_SR) return
        RENDER_SR = sampleRate
        voiceCache.clear()
    }

    /** Live track volume: applies to sounding voices immediately and to future notes. */
    fun setTrackVolume(trackId: Long, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        trackVolumes[trackId] = v
        audioEngine.setTrackVolume(trackId, v)
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        audioEngine.stopNotes()
        _isPlaying.value = false
        _playheadBeat.value = 0.0
        _soundingIds.value = emptySet()
        _playbackClock.value = null
    }

    /** Pauses playback, keeping the playhead where it is so playback can resume. */
    fun pause() {
        playJob?.cancel()
        playJob = null
        audioEngine.stopNotes()
        _isPlaying.value = false
        _soundingIds.value = emptySet()
        _playbackClock.value = null
    }

    /** Moves the playhead marker while stopped/paused. */
    fun seek(beat: Double) {
        _playheadBeat.value = beat.coerceAtLeast(0.0)
        _soundingIds.value = emptySet()
    }

    fun play(notes: List<ScoreNote>, tracks: List<Track>, tempoBpm: Int, startBeat: Double = 0.0) {
        playJob?.cancel()
        playJob = null
        _playheadBeat.value = startBeat.coerceAtLeast(0.0)
        val fromBeat = _playheadBeat.value
        if (notes.isEmpty()) {
            _isPlaying.value = false
            return
        }

        val instrumentByTrack = HashMap<Long, Long>() // trackId -> instrumentId
        tracks.forEach { instrumentByTrack[it.id] = it.instrumentId }
        val trackById = tracks.associateBy { it.id }
        trackVolumes.clear()
        tracks.forEach { trackVolumes[it.id] = it.volume.coerceIn(0f, 1f) }
        mutedTracks.clear()
        tracks.filter { it.muted }.forEach { mutedTracks.add(it.id) }
        soloTracks.clear()
        tracks.filter { it.solo }.forEach { soloTracks.add(it.id) }
        liveInstrumentByTrack.clear()
        liveInstrumentByTrack.putAll(instrumentByTrack)
        val soloIds = tracks.filter { it.solo }.map { it.id }.toSet()

        val msPerBeat = 60_000.0 / tempoBpm
        // When any track is soloed only soloed tracks sound; otherwise muted tracks are skipped.
        val sorted = notes
            .filter { note ->
                val track = trackById[note.trackId] ?: return@filter false
                val audible = if (soloIds.isNotEmpty()) note.trackId in soloIds else !track.muted
                audible && note.startBeat + note.durationBeats > fromBeat
            }
            .sortedBy { it.startBeat }
        if (sorted.isEmpty()) {
            _isPlaying.value = false
            return
        }
        val endBeat = sorted.maxOf { it.startBeat + it.durationBeats }
        currentNotes = sorted
        currentTempoBpm = tempoBpm

        _isPlaying.value = true
        playJob = scope.launch {
            val instrumentCache: MutableMap<Long, Instrument?> = liveInstrumentCache
            val synthPatchCache: MutableMap<Long, Any?> = livePatchCache

            // Resolve every note's instrument and patch up front
            // (sequentially) so a single missing row can't abort the schedule.
            for (note in sorted) {
                if (!isActive) return@launch
                val instrumentId = instrumentByTrack[note.trackId] ?: continue
                val instrument = try {
                    instrumentCache.getOrPut(instrumentId) {
                        instrumentRepository.getById(instrumentId)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                } ?: continue
                synthPatchCache.getOrPut(instrumentId) {
                    instrument.synthPatchJson?.let {
                        try {
                            val json = JSONObject(it)
                            when {
                                json.optBoolean("isDrumKit", false) -> DrumKitMarker
                                json.optBoolean("isDrum", false) -> {
                                    com.robert.noteforge.synth.DrumPatch.fromJson(it)
                                }
                                else -> patchFromJson(json)
                            }
                        } catch (_: Exception) { null }
                    }
                }
            }

            // Pre-render every unique voice in parallel before the playhead
            // starts, so each note's turn is just a cache lookup.
            if (voiceCache.size > 400) voiceCache.clear()
            sorted.mapNotNull { note ->
                val instrumentId = instrumentByTrack[note.trackId] ?: return@mapNotNull null
                val instrument = instrumentCache[instrumentId] ?: return@mapNotNull null
                val patch = synthPatchCache[instrumentId] ?: return@mapNotNull null
                VoiceKey(
                    patchHash = instrument.synthPatchJson.hashCode(),
                    pitchMidi = note.pitchMidi,
                    durationBeats = note.durationBeats,
                    tempoBpm = tempoBpm,
                    velLevel = velLevel(note.velocity)
                ) to patch
            }
                .distinctBy { it.first }
                .filter { (key, _) -> !voiceCache.containsKey(key) }
                .map { (key, patch) ->
                    async {
                        try {
                            voiceCache[key] = renderVoice(patch, key)
                        } catch (e: Exception) {
                            // A failed voice just won't be cached; its notes
                            // are skipped below. Never let one bad render
                            // take down the sequencer's scope.
                            if (e is CancellationException) throw e
                        }
                    }
                }
                .awaitAll()
            if (!isActive) return@launch

            val startTime = System.currentTimeMillis()

            // Publishes the wall-clock basis so the UI can draw the playhead
            // independently every frame (no jitter from timer granularity).
            // This ticker only handles note highlighting at ~30fps.
            _playbackClock.value = PlaybackClock(startTime, fromBeat, msPerBeat, endBeat)
            val ticker = launch {
                while (isActive) {
                    val e = System.currentTimeMillis() - startTime
                    val pb = (fromBeat + e / msPerBeat).coerceAtMost(endBeat)
                    _playheadBeat.value = pb
                    val sounding = sorted
                        .filter { it.startBeat <= pb && pb < it.startBeat + it.durationBeats }
                        .map { it.id }
                        .toSet()
                    if (sounding != _soundingIds.value) _soundingIds.value = sounding
                    delay(33)
                }
            }

            try {
                for (note in sorted) {
                    if (!isActive) break
                    val targetMs = ((note.startBeat - fromBeat) * msPerBeat).toLong().coerceAtLeast(0)
                    val elapsed = System.currentTimeMillis() - startTime
                    val waitMs = targetMs - elapsed
                    if (waitMs > 0) delay(waitMs)
                    if (!isActive) break

                    if (!isAudible(note.trackId)) continue
                    val instrumentId = liveInstrumentByTrack[note.trackId] ?: continue
                    val instrument = instrumentCache[instrumentId]
                    val patchHash = instrument?.synthPatchJson?.hashCode() ?: 0
                    val key = VoiceKey(
                        patchHash = patchHash,
                        pitchMidi = note.pitchMidi,
                        durationBeats = note.durationBeats,
                        tempoBpm = tempoBpm,
                        velLevel = velLevel(note.velocity)
                    )
                    var pcm = if (instrument != null) voiceCache[key] else null
                    if (pcm == null) {
                        // New instrument's voices aren't rendered yet: keep
                        // playing the old instrument until the swap is ready.
                        val prevId = previousInstrumentByTrack[note.trackId]
                        val prevInst = prevId?.let { instrumentCache[it] }
                        if (prevInst?.synthPatchJson != null) {
                            val prevKey = key.copy(patchHash = prevInst.synthPatchJson.hashCode())
                            pcm = voiceCache[prevKey]
                        }
                    }
                    val audio = pcm ?: continue

                    // Fire-and-forget per note, isolated: a failed AudioTrack
                    // for one note must never cancel the remaining schedule.
                    // The voice is pre-rendered, so this is just a lookup,
                    // a duration trim and a gain — no processing delay.
                    launch {
                        try {
                            val trackVolume = trackVolumes[note.trackId] ?: 1f
                            // Full voice including its natural release — never
                            // truncated, so notes linger as the patch dictates
                            // (like WildPatch) instead of cutting off.
                            // Velocity is baked in; track volume stays live via
                            // the AudioTrack so sliders work mid-playback.
                            val shaped = PitchResampler.applyGain(audio, note.velocity)
                            audioEngine.playRaw(shaped, RENDER_SR, note.trackId, trackVolume)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                        }
                    }
                }

                val totalMs = ((endBeat - fromBeat) * msPerBeat).toLong()
                val remaining = totalMs - (System.currentTimeMillis() - startTime)
                if (remaining > 0) delay(remaining)
            } finally {
                ticker.cancel()
            }
            _playheadBeat.value = endBeat
            _soundingIds.value = emptySet()
            _playbackClock.value = null
            _isPlaying.value = false
        }
    }

    companion object {
        /**
         * Render rate for note voices. Half rate roughly halves the DSP cost
         * versus 44.1kHz; for synth the audible difference is negligible.
         */
        /** Current render sample rate; set via Sequencer.setQuality(). */
        var RENDER_SR = 22050
            private set
    }
}
