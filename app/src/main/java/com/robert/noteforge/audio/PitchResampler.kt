package com.robert.noteforge.audio

import kotlin.math.roundToInt

/**
 * Small PCM helpers for the synth playback path: gain shaping and exact
 * note-duration truncation.
 */
object PitchResampler {

    fun applyGain(source: ShortArray, gain: Float): ShortArray {
        if (gain == 1.0f) return source
        return ShortArray(source.size) { i ->
            val v = (source[i] * gain).roundToInt()
            v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Cuts the buffer to [durationMs] with a short fade-out at the end, so a
     * sequenced note stops when its written duration ends instead of ringing
     * past it. Buffers already shorter than the duration are returned
     * untouched.
     */
    fun limitDuration(
        source: ShortArray,
        sampleRate: Int,
        durationMs: Double,
        fadeMs: Double = 30.0
    ): ShortArray {
        if (source.isEmpty() || durationMs <= 0.0) return ShortArray(0)
        val maxSamples = ((durationMs / 1000.0) * sampleRate).roundToInt().coerceAtLeast(1)
        if (source.size <= maxSamples) return source
        val out = source.copyOf(maxSamples)
        val fadeSamples = ((fadeMs / 1000.0) * sampleRate).roundToInt().coerceIn(1, maxSamples)
        for (i in 0 until fadeSamples) {
            val k = i.toFloat() / fadeSamples
            val idx = maxSamples - fadeSamples + i
            out[idx] = (out[idx] * k).roundToInt().toShort()
        }
        return out
    }
}
