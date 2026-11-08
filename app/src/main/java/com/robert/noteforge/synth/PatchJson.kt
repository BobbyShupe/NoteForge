package com.robert.noteforge.synth

import org.json.JSONArray
import org.json.JSONObject

/** Patch <-> JSON, using org.json (no new deps). */
fun Patch.toJson(): JSONObject = JSONObject().apply {
    put("seed", seed)
    put("tempo", tempo.toDouble())
    put("rootSemi", rootSemi)
    put("activeOscs", activeOscs)
    put("notes", JSONArray().apply {
        notes.forEach { n ->
            put(JSONObject().apply {
                put("s", n.semitone)
                put("b", n.beats.toDouble())
            })
        }
    })
    put("oscs", JSONArray().apply {
        oscs.forEach { o ->
            put(JSONObject().apply {
                put("w", o.wave.name)
                put("o", o.octave)
                put("d", o.detuneCents.toDouble())
                put("l", o.level.toDouble())
                put("p", o.pan.toDouble())
            })
        }
    })
    put("fmRatio", fmRatio.toDouble())
    put("fmAmount", fmAmount.toDouble())
    put("spread", spread.toDouble())
    put("subLevel", subLevel.toDouble())
    put("noiseLevel", noiseLevel.toDouble())
    put("cutoff", cutoff.toDouble())
    put("reso", reso.toDouble())
    put("filterEnv", filterEnv.toDouble())
    put("lfoRate", lfoRate.toDouble())
    put("lfoDepth", lfoDepth.toDouble())
    put("vibrato", vibrato.toDouble())
    put("vibDelay", vibDelay.toDouble())
    put("vibRate", vibRate.toDouble())
    put("breathAmt", breathAmt.toDouble())
    put("driftAmt", driftAmt.toDouble())
    put("vowel", vowel.toDouble())
    put("vowelMix", vowelMix.toDouble())
    put("tremRate", tremRate.toDouble())
    put("tremDepth", tremDepth.toDouble())
    put("growl", growl.toDouble())
    put("slideSemi", slideSemi.toDouble())
    put("slideTime", slideTime.toDouble())
    put("clickAmt", clickAmt.toDouble())
    put("flutterAmt", flutterAmt.toDouble())
    put("airAmt", airAmt.toDouble())
    put("attack", attack.toDouble())
    put("decay", decay.toDouble())
    put("sustain", sustain.toDouble())
    put("release", release.toDouble())
    put("delayTime", delayTime.toDouble())
    put("delaySync", delaySync)
    put("delayFb", delayFb.toDouble())
    put("delayMix", delayMix.toDouble())
    put("revSize", revSize.toDouble())
    put("revDamp", revDamp.toDouble())
    put("revMix", revMix.toDouble())
    put("drive", drive.toDouble())
    put("width", width.toDouble())
    put("pitchEnvSemi", pitchEnvSemi.toDouble())
    put("pitchEnvTime", pitchEnvTime.toDouble())
    put("velToCutoff", velToCutoff.toDouble())
    put("keyTrack", keyTrack.toDouble())
}

fun patchFromJson(o: JSONObject): Patch {
    fun gd(k: String) = o.getDouble(k).toFloat()
    val notes = mutableListOf<Note>()
    val na = o.optJSONArray("notes")
    if (na != null) {
        for (i in 0 until na.length()) {
            val n = na.getJSONObject(i)
            notes += Note(n.getInt("s"), n.getDouble("b").toFloat())
        }
    }
    val oscs = mutableListOf<OscLayer>()
    val oa = o.getJSONArray("oscs")
    for (i in 0 until oa.length()) {
        val x = oa.getJSONObject(i)
        oscs += OscLayer(
            wave = Wave.valueOf(x.getString("w")),
            octave = x.getInt("o"),
            detuneCents = x.getDouble("d").toFloat(),
            level = x.getDouble("l").toFloat(),
            pan = x.getDouble("p").toFloat()
        )
    }
    return Patch(
        seed = o.getLong("seed"),
        tempo = gd("tempo"),
        rootSemi = o.optInt("rootSemi", 0),
        notes = notes.take(1).ifEmpty { listOf(Note(o.optInt("rootSemi", 0), 4f)) },
        oscs = oscs,
        activeOscs = o.optInt("activeOscs", oscs.size.coerceAtLeast(1)),
        fmRatio = gd("fmRatio"),
        fmAmount = gd("fmAmount"),
        spread = gd("spread"),
        subLevel = gd("subLevel"),
        noiseLevel = gd("noiseLevel"),
        cutoff = gd("cutoff"),
        reso = gd("reso"),
        filterEnv = gd("filterEnv"),
        lfoRate = gd("lfoRate"),
        lfoDepth = gd("lfoDepth"),
        vibrato = gd("vibrato"),
        vibDelay = o.optDouble("vibDelay", 0.0).toFloat(),
        vibRate = o.optDouble("vibRate", 0.5).toFloat(),
        breathAmt = o.optDouble("breathAmt", 0.0).toFloat(),
        driftAmt = o.optDouble("driftAmt", 0.0).toFloat(),
        vowel = o.optDouble("vowel", 0.0).toFloat(),
        vowelMix = o.optDouble("vowelMix", 0.0).toFloat(),
        tremRate = o.optDouble("tremRate", 0.4).toFloat(),
        tremDepth = o.optDouble("tremDepth", 0.0).toFloat(),
        growl = o.optDouble("growl", 0.0).toFloat(),
        slideSemi = o.optDouble("slideSemi", 0.0).toFloat(),
        slideTime = o.optDouble("slideTime", 0.35).toFloat(),
        clickAmt = o.optDouble("clickAmt", 0.0).toFloat(),
        flutterAmt = o.optDouble("flutterAmt", 0.0).toFloat(),
        airAmt = o.optDouble("airAmt", 0.0).toFloat(),
        attack = gd("attack"),
        decay = gd("decay"),
        sustain = gd("sustain"),
        release = gd("release"),
        delayTime = gd("delayTime"),
        delaySync = o.optBoolean("delaySync", false),
        delayFb = gd("delayFb"),
        delayMix = gd("delayMix"),
        revSize = gd("revSize"),
        revDamp = gd("revDamp"),
        revMix = gd("revMix"),
        drive = gd("drive"),
        width = gd("width"),
        pitchEnvSemi = o.optDouble("pitchEnvSemi", 0.0).toFloat(),
        pitchEnvTime = o.optDouble("pitchEnvTime", 0.25).toFloat(),
        velToCutoff = o.optDouble("velToCutoff", 0.0).toFloat(),
        keyTrack = o.optDouble("keyTrack", 0.0).toFloat()
    )
}

/** Mixes a rendered stereo float buffer down to mono 16-bit PCM. */
fun toMonoPcm16(buf: SynthEngine.StereoBuf): ShortArray {
    val out = ShortArray(buf.n)
    for (i in 0 until buf.n) {
        val v = ((buf.l[i] + buf.r[i]) * 0.5f).coerceIn(-1f, 1f)
        out[i] = (v * 32767f).toInt().toShort()
    }
    return out
}
