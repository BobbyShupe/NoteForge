package com.robert.noteforge.synth

import kotlin.math.pow
import kotlin.random.Random

/** Drum categories. */
enum class DrumType {
    KICK, SNARE, HAT_CLOSED, HAT_OPEN, TOM, CLAP, CYMBAL, RIM
}

/** Steers DrumPatch.random(): 0.5 = neutral, 0/1 favor opposite extremes. */
data class DrumBias(
    val punchy: Float = 0.5f,   // 0=soft … 1=punchy (attack/transient)
    val acoustic: Float = 0.5f, // 0=electronic … 1=acoustic (noise vs tone)
    val bright: Float = 0.5f,   // 0=dark … 1=bright (filter cutoff)
    val tight: Float = 0.5f     // 0=loose/long … 1=tight/short (decay)
)

/**
 * A drum synthesis patch. All parameters are knob-tweakable;
 * DrumPatch.random() generates a new one, steered by DrumBias.
 */
data class DrumPatch(
    val seed: Long,
    val type: DrumType,
    // Pitch
    val baseFreq: Float,      // Hz: kick 40-80, snare 180-250, etc.
    val pitchEnvAmt: Float,    // 0..1: how much pitch drops (kick/tom)
    val pitchEnvDecay: Float,  // 0..1: pitch envelope speed (log 0.005..0.5s)
    // Tone body
    val toneLevel: Float,      // 0..1
    val toneDecay: Float,      // 0..1: log 0.05..2s
    // Noise
    val noiseLevel: Float,     // 0..1
    val noiseDecay: Float,     // 0..1: log 0.01..1s
    val noiseFilterFreq: Float,// 0..1: log 500..12000 Hz
    val noiseFilterQ: Float,    // 0..1: 0.5..8
    val noiseHighpass: Boolean, // true=highpass (hats/cymbals), false=bandpass
    // Transient/click
    val clickLevel: Float,     // 0..1: initial click transient
    val clickFreq: Float,      // 0..1: log 1000..8000 Hz
    // Envelope
    val attack: Float,         // 0..1: log 0.0005..0.02s
    val decay: Float,          // 0..1: log 0.05..2s (overall)
    // Character
    val drive: Float,          // 0..1: saturation
    val body: Float,           // 0..1: low-end body boost (kick/snare/tom)
    // For metallic sounds (cymbal): inharmonic partials
    val metallic: Float,        // 0..1: amount of metallic partials
    val metallicDecay: Float,  // 0..1: metallic partials decay
) {
    companion object {
        fun random(seed: Long, type: DrumType? = null, bias: DrumBias = DrumBias()): DrumPatch {
            val r = Random(seed)
            val t = type ?: DrumType.values().random(r)

            // Helper: biased random 0..1
            fun br(b: Float, strength: Float = 1f): Float {
                val v = r.nextFloat()
                // Shift toward bias: 0.5=neutral, 0/1=extremes
                return (v + (b - 0.5f) * strength).coerceIn(0f, 1f)
            }

            // Base frequency ranges by type
            val baseFreq = when (t) {
                DrumType.KICK -> 40f + r.nextFloat() * 40f // 40-80
                DrumType.SNARE -> 170f + r.nextFloat() * 80f // 170-250
                DrumType.HAT_CLOSED, DrumType.HAT_OPEN -> 6000f + r.nextFloat() * 4000f
                DrumType.TOM -> 80f + r.nextFloat() * 120f // 80-200
                DrumType.CLAP -> 1200f + r.nextFloat() * 800f
                DrumType.CYMBAL -> 5000f + r.nextFloat() * 5000f
                DrumType.RIM -> 800f + r.nextFloat() * 1200f
            }

            return DrumPatch(
                seed = seed,
                type = t,
                baseFreq = baseFreq,
                pitchEnvAmt = when (t) {
                    DrumType.KICK -> 0.7f + r.nextFloat() * 0.3f
                    DrumType.TOM -> 0.4f + r.nextFloat() * 0.4f
                    DrumType.SNARE -> r.nextFloat() * 0.3f
                    else -> r.nextFloat() * 0.1f
                },
                pitchEnvDecay = br(0.5f).let { 0.1f + it * 0.4f },
                toneLevel = when (t) {
                    DrumType.KICK, DrumType.TOM -> 0.8f + r.nextFloat() * 0.2f
                    DrumType.SNARE -> 0.4f + r.nextFloat() * 0.4f
                    DrumType.RIM -> 0.7f + r.nextFloat() * 0.3f
                    else -> r.nextFloat() * 0.3f
                },
                toneDecay = (0.3f + br(bias.tight, 0.8f) * 0.5f).coerceIn(0f, 1f),
                noiseLevel = when (t) {
                    DrumType.SNARE -> 0.5f + r.nextFloat() * 0.4f
                    DrumType.HAT_CLOSED, DrumType.HAT_OPEN -> 0.8f + r.nextFloat() * 0.2f
                    DrumType.CLAP -> 0.7f + r.nextFloat() * 0.3f
                    DrumType.CYMBAL -> 0.6f + r.nextFloat() * 0.4f
                    DrumType.KICK -> r.nextFloat() * 0.3f
                    else -> 0.3f + r.nextFloat() * 0.4f
                } * (0.5f + br(bias.acoustic, 0.7f)),
                noiseDecay = (0.2f + br(bias.tight, 0.8f) * 0.6f).coerceIn(0f, 1f),
                noiseFilterFreq = br(bias.bright).let { 0.3f + it * 0.7f },
                noiseFilterQ = r.nextFloat() * 0.5f,
                noiseHighpass = t == DrumType.HAT_CLOSED || t == DrumType.HAT_OPEN || t == DrumType.CYMBAL,
                clickLevel = br(bias.punchy).let { 0.3f + it * 0.7f },
                clickFreq = r.nextFloat(),
                attack = 0.05f + r.nextFloat() * 0.1f, // Very fast for drums
                decay = (0.3f + br(1f - bias.tight, 0.9f) * 0.7f).coerceIn(0f, 1f),
                drive = br(1f - bias.punchy, 0.5f) * 0.6f,
                body = when (t) {
                    DrumType.KICK -> 0.6f + r.nextFloat() * 0.4f
                    DrumType.SNARE, DrumType.TOM -> 0.3f + r.nextFloat() * 0.5f
                    else -> r.nextFloat() * 0.3f
                },
                metallic = when (t) {
                    DrumType.CYMBAL -> 0.7f + r.nextFloat() * 0.3f
                    DrumType.HAT_CLOSED, DrumType.HAT_OPEN -> 0.3f + r.nextFloat() * 0.4f
                    else -> r.nextFloat() * 0.2f
                },
                metallicDecay = r.nextFloat()
            )
        }

        fun fromJson(json: String): DrumPatch? {
            return try {
                val o = org.json.JSONObject(json)
                DrumPatch(
                    seed = o.getLong("seed"),
                    type = DrumType.valueOf(o.getString("type")),
                    baseFreq = o.getDouble("baseFreq").toFloat(),
                    pitchEnvAmt = o.getDouble("pitchEnvAmt").toFloat(),
                    pitchEnvDecay = o.getDouble("pitchEnvDecay").toFloat(),
                    toneLevel = o.getDouble("toneLevel").toFloat(),
                    toneDecay = o.getDouble("toneDecay").toFloat(),
                    noiseLevel = o.getDouble("noiseLevel").toFloat(),
                    noiseDecay = o.getDouble("noiseDecay").toFloat(),
                    noiseFilterFreq = o.getDouble("noiseFilterFreq").toFloat(),
                    noiseFilterQ = o.getDouble("noiseFilterQ").toFloat(),
                    noiseHighpass = o.getBoolean("noiseHighpass"),
                    clickLevel = o.getDouble("clickLevel").toFloat(),
                    clickFreq = o.getDouble("clickFreq").toFloat(),
                    attack = o.getDouble("attack").toFloat(),
                    decay = o.getDouble("decay").toFloat(),
                    drive = o.getDouble("drive").toFloat(),
                    body = o.getDouble("body").toFloat(),
                    metallic = o.getDouble("metallic").toFloat(),
                    metallicDecay = o.getDouble("metallicDecay").toFloat()
                )
            } catch (_: Exception) { null }
        }
    }

    /** Serialize to JSON for storage. */
    fun toJson(): String {
        return org.json.JSONObject().apply {
            put("isDrum", true)
            put("seed", seed)
            put("type", type.name)
            put("baseFreq", baseFreq)
            put("pitchEnvAmt", pitchEnvAmt)
            put("pitchEnvDecay", pitchEnvDecay)
            put("toneLevel", toneLevel)
            put("toneDecay", toneDecay)
            put("noiseLevel", noiseLevel)
            put("noiseDecay", noiseDecay)
            put("noiseFilterFreq", noiseFilterFreq)
            put("noiseFilterQ", noiseFilterQ)
            put("noiseHighpass", noiseHighpass)
            put("clickLevel", clickLevel)
            put("clickFreq", clickFreq)
            put("attack", attack)
            put("decay", decay)
            put("drive", drive)
            put("body", body)
            put("metallic", metallic)
            put("metallicDecay", metallicDecay)
        }.toString()
    }

        fun fromJson(json: String): DrumPatch? {
            return try {
                val o = org.json.JSONObject(json)
                DrumPatch(
                    seed = o.getLong("seed"),
                    type = DrumType.valueOf(o.getString("type")),
                    baseFreq = o.getDouble("baseFreq").toFloat(),
                    pitchEnvAmt = o.getDouble("pitchEnvAmt").toFloat(),
                    pitchEnvDecay = o.getDouble("pitchEnvDecay").toFloat(),
                    toneLevel = o.getDouble("toneLevel").toFloat(),
                    toneDecay = o.getDouble("toneDecay").toFloat(),
                    noiseLevel = o.getDouble("noiseLevel").toFloat(),
                    noiseDecay = o.getDouble("noiseDecay").toFloat(),
                    noiseFilterFreq = o.getDouble("noiseFilterFreq").toFloat(),
                    noiseFilterQ = o.getDouble("noiseFilterQ").toFloat(),
                    noiseHighpass = o.getBoolean("noiseHighpass"),
                    clickLevel = o.getDouble("clickLevel").toFloat(),
                    clickFreq = o.getDouble("clickFreq").toFloat(),
                    attack = o.getDouble("attack").toFloat(),
                    decay = o.getDouble("decay").toFloat(),
                    drive = o.getDouble("drive").toFloat(),
                    body = o.getDouble("body").toFloat(),
                    metallic = o.getDouble("metallic").toFloat(),
                    metallicDecay = o.getDouble("metallicDecay").toFloat()
                )
            } catch (_: Exception) { null }
        }
}
