package com.robert.noteforge.synth

import android.content.Context

/** Persists the dice RandomBias across app restarts. */
object BiasPrefs {
    private const val FILE = "dice_bias"
    private const val K_BRIGHT = "bright"
    private const val K_ORGANIC = "organic"
    private const val K_AGGRO = "aggro"
    private const val K_SPACE = "space"
    private const val K_MOTION = "motion"

    fun load(ctx: Context): RandomBias {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return RandomBias(
            bright = p.getFloat(K_BRIGHT, 0.5f),
            organic = p.getFloat(K_ORGANIC, 0.5f),
            aggro = p.getFloat(K_AGGRO, 0.5f),
            space = p.getFloat(K_SPACE, 0.5f),
            motion = p.getFloat(K_MOTION, 0.5f)
        )
    }

    fun save(ctx: Context, bias: RandomBias) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat(K_BRIGHT, bias.bright)
            .putFloat(K_ORGANIC, bias.organic)
            .putFloat(K_AGGRO, bias.aggro)
            .putFloat(K_SPACE, bias.space)
            .putFloat(K_MOTION, bias.motion)
            .apply()
    }
}
