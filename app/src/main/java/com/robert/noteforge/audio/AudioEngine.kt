package com.robert.noteforge.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Synth-only audio engine: renders live synth patches per note and plays
 * them through short-lived AudioTrack voices in STATIC mode; fine for the
 * polyphony a hand-built score realistically needs (a handful of concurrent
 * notes).
 */
@Singleton
class AudioEngine @Inject constructor() {

    private val engineScope = CoroutineScope(Dispatchers.Default)

    // Strong refs to every note track that is currently playing. Without
    // this, playRaw()'s local AudioTrack becomes GC-eligible the moment it
    // returns and playback can be cut short mid-note, then the next note
    // starts normally — the "cuts out and continues" symptom.
    private val activeTracks: MutableSet<AudioTrack> =
        Collections.newSetFromMap(ConcurrentHashMap<AudioTrack, Boolean>())
    private val maxPolyphony = 24

    /** Preview for synth instruments: renders the live patch at the given pitch. */
    private var previewTrack: android.media.AudioTrack? = null
    val isPreviewPlaying = kotlinx.coroutines.flow.MutableStateFlow(false)

    fun playSynthPreview(patch: com.robert.noteforge.synth.Patch, pitchMidi: Int, volume: Float = 1f, velocity: Float = 1f) {
        stopPreview()
        isPreviewPlaying.value = true
        engineScope.launch {
            val sp = patch.copy(
                tempo = 120f,
                notes = listOf(com.robert.noteforge.synth.Note(pitchMidi - 33, 4f))
            )
            // Fresh engine per render: render() reseeds its noise source, so a
            // shared instance would corrupt concurrent renders. Half-rate, as
            // in the sequencer, keeps previews snappy.
            val pcm = com.robert.noteforge.synth.toMonoPcm16(
                com.robert.noteforge.synth.SynthEngine(Sequencer.RENDER_SR).render(sp, velocity = velocity))
            val shaped = PitchResampler.applyGain(pcm, volume.coerceIn(0f, 1f))
            val track = try {
                buildTrack(shaped, Sequencer.RENDER_SR)
            } catch (_: Exception) {
                isPreviewPlaying.value = false
                return@launch
            }
            previewTrack = track
            activeTracks.add(track)
            track.setNotificationMarkerPosition(shaped.size)
            track.setPlaybackPositionUpdateListener(object : android.media.AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: android.media.AudioTrack) {
                    if (previewTrack === t) {
                        previewTrack = null
                        isPreviewPlaying.value = false
                    }
                    activeTracks.remove(t)
                    try { t.stop() } catch (_: Exception) {}
                    try { t.release() } catch (_: Exception) {}
                }
                override fun onPeriodicNotification(t: android.media.AudioTrack) {}
            })
            try {
                track.write(shaped, 0, shaped.size)
                track.play()
            } catch (_: Exception) {
                previewTrack = null
                isPreviewPlaying.value = false
                activeTracks.remove(track)
                try { track.stop() } catch (_: Exception) {}
                try { track.release() } catch (_: Exception) {}
            }
        }
    }

    /** Stops the synth preview if playing. */
    fun stopPreview() {
        val t = previewTrack
        previewTrack = null
        isPreviewPlaying.value = false
        if (t != null) {
            activeTracks.remove(t)
            try { t.stop() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
        }
    }

    // Live-mix bookkeeping: which AudioTracks belong to which score track,
    // so volume/mute can change mid-playback without restarting.
    private val tracksByScoreTrack = java.util.concurrent.ConcurrentHashMap<Long, MutableSet<AudioTrack>>()

    private fun untagTrack(t: AudioTrack, scoreTrackId: Long) {
        activeTracks.remove(t)
        if (scoreTrackId != -1L) {
            tracksByScoreTrack[scoreTrackId]?.remove(t)
        }
    }

    /** Live volume for all currently sounding voices of a score track. */
    fun setTrackVolume(scoreTrackId: Long, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        tracksByScoreTrack[scoreTrackId]?.toList()?.forEach { t ->
            try { t.setVolume(v) } catch (_: Exception) {}
        }
    }

    /** Fades out and stops all currently sounding voices of a score track. */
    fun stopTrackVoices(scoreTrackId: Long) {
        val doomed = tracksByScoreTrack[scoreTrackId]?.toList() ?: return
        if (doomed.isEmpty()) return
        tracksByScoreTrack[scoreTrackId]?.clear()
        engineScope.launch {
            try {
                for (i in 4 downTo 0) {
                    doomed.forEach { t ->
                        try { t.setVolume(i / 4f) } catch (_: Exception) {}
                    }
                    delay(20)
                }
            } catch (_: Exception) {}
            doomed.forEach { t ->
                activeTracks.remove(t)
                try { t.stop() } catch (_: Exception) {}
                try { t.release() } catch (_: Exception) {}
            }
        }
    }

    fun playRaw(samples: ShortArray, sampleRate: Int, scoreTrackId: Long = -1L, volume: Float = 1f) {
        if (samples.isEmpty()) return
        enforcePolyphonyLimit()
        val track = try {
            buildTrack(samples, sampleRate)
        } catch (e: Exception) {
            return
        }
        // Keep a strong ref until the marker fires; otherwise the track is
        // GC-eligible while still sounding and notes cut out mid-playback.
        activeTracks.add(track)
        if (scoreTrackId != -1L) {
            tracksByScoreTrack.getOrPut(scoreTrackId) {
                java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
            }.add(track)
        }
        try { track.setVolume(volume.coerceIn(0f, 1f)) } catch (_: Exception) {}

        track.setNotificationMarkerPosition(samples.size)
        track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: AudioTrack) {
                untagTrack(t, scoreTrackId)
                try { t.stop() } catch (_: Exception) {}
                try { t.release() } catch (_: Exception) {}
            }
            override fun onPeriodicNotification(t: AudioTrack) {}
        })
        try {
            track.write(samples, 0, samples.size)
            track.play()
        } catch (e: Exception) {
            untagTrack(track, scoreTrackId)
            try { track.stop() } catch (_: Exception) {}
            try { track.release() } catch (_: Exception) {}
        }
    }

    /** Stops all sounding note tracks (sequencer stop/pause) and releases them. */
    fun stopNotes() {
        val snapshot = activeTracks.toList()
        activeTracks.clear()
        snapshot.forEach { t ->
            try { t.stop() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
        }
    }

    private fun enforcePolyphonyLimit() {
        while (activeTracks.size >= maxPolyphony) {
            val oldest = activeTracks.firstOrNull() ?: break
            activeTracks.remove(oldest)
            // Fade out before release: an abrupt stop() mid-tail clicks.
            // Fade to true zero — stopping at 0.25 still clicks.
            engineScope.launch {
                try {
                    for (i in 4 downTo 0) {
                        try { oldest.setVolume(i / 4f) } catch (_: Exception) {}
                        delay(20)
                    }
                } catch (_: Exception) {}
                try { oldest.stop() } catch (_: Exception) {}
                try { oldest.release() } catch (_: Exception) {}
            }
        }
    }

    private fun buildTrack(samples: ShortArray, sampleRate: Int): AudioTrack {
        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(samples.size * 2)

        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBufSize)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
    }
}
