package com.robert.noteforge.audio

import android.content.Context

/** Playback render quality. Higher = better fidelity, more DSP cost. */
object QualityPrefs {
    private const val FILE = "playback_quality"
    private const val K_SR = "sample_rate"

    const val SR_SMOOTH = 22050
    const val SR_HIGH = 44100

    fun getSampleRate(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getInt(K_SR, SR_SMOOTH)

    fun setSampleRate(ctx: Context, sr: Int) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt(K_SR, sr)
            .apply()
    }
}
