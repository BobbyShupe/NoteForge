package com.robert.noteforge.synth

import kotlin.math.*

/** Heuristic single-note analysis used to seed a mimicking patch. */
data class Analysis(
    val pitchHz: Float,
    val attackSec: Float,
    val decaySec: Float,
    val sustainLevel: Float,
    val releaseSec: Float,
    val brightnessHz: Float,
    val filterEnvAmt: Float,
    val wave: Wave,
    val fmAmount: Float,
    val noiseLevel: Float,
    val pitchDropSemi: Float,
    val durationSec: Float
)

/** Iterative radix-2 FFT (in-place, real input packed into complex). */
private fun fft(re: DoubleArray, im: DoubleArray) {
    val n = re.size
    var j = 0
    for (i in 1 until n) {
        var bit = n shr 1
        while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
        j = j xor bit
        if (i < j) {
            val tr = re[i]; re[i] = re[j]; re[j] = tr
            val ti = im[i]; im[i] = im[j]; im[j] = ti
        }
    }
    var len = 2
    while (len <= n) {
        val ang = -2.0 * PI / len
        val wr = cos(ang); val wi = sin(ang)
        var i = 0
        while (i < n) {
            var wR = 1.0; var wI = 0.0
            for (k in 0 until len / 2) {
                val ur = re[i + k]; val ui = im[i + k]
                val vr = re[i + k + len / 2] * wR - im[i + k + len / 2] * wI
                val vi = re[i + k + len / 2] * wI + im[i + k + len / 2] * wR
                re[i + k] = ur + vr; im[i + k] = ui + vi
                re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                val nwr = wR * wr - wI * wi; wI = wR * wi + wI * wr; wR = nwr
            }
            i += len
        }
        len = len shl 1
    }
}

private fun magnitudeSpectrum(x: FloatArray): DoubleArray {
    var n = 1
    while (n < x.size) n = n shl 1
    val re = DoubleArray(n); val im = DoubleArray(n)
    // Hann window
    for (i in x.indices) {
        val w = 0.5 - 0.5 * cos(2.0 * PI * i / x.size)
        re[i] = x[i] * w
    }
    fft(re, im)
    return DoubleArray(n / 2) { i -> sqrt(re[i] * re[i] + im[i] * im[i]) }
}

private fun centroid(mag: DoubleArray, sr: Int): Double {
    val binHz = sr.toDouble() / (mag.size * 2)
    var num = 0.0; var den = 0.0
    for (i in 1 until mag.size) {
        num += i * binHz * mag[i]; den += mag[i]
    }
    return if (den > 0) num / den else 0.0
}

/** RMS envelope, 256-sample windows, 128 hop. */
private fun envelope(x: FloatArray): FloatArray {
    val hop = 128; val win = 256
    val n = ((x.size - win) / hop).coerceAtLeast(1)
    return FloatArray(n) { w ->
        var s = 0.0
        val o = w * hop
        for (i in 0 until win) s += x[o + i] * x[o + i]
        sqrt(s / win).toFloat()
    }
}

/** Fundamental via normalized autocorrelation on the loudest 4096-sample block. */
private fun detectPitch(x: FloatArray, sr: Int): Float {
    val block = 4096
    if (x.size < block) return 0f
    var best = 0; var bestE = 0.0
    var o = 0
    while (o + block <= x.size) {
        var e = 0.0
        for (i in 0 until block) e += x[o + i] * x[o + i]
        if (e > bestE) { bestE = e; best = o }
        o += 1024
    }
    val seg = FloatArray(block) { x[best + it] }
    val minLag = (sr / 2000.0).toInt().coerceAtLeast(2)
    val maxLag = (sr / 40.0).toInt().coerceAtMost(block / 2)
    var bestLag = minLag; var bestC = -1.0
    var e0 = 0.0
    for (i in seg.indices) e0 += seg[i] * seg[i]
    if (e0 <= 0) return 0f
    for (lag in minLag..maxLag) {
        var c = 0.0; var e1 = 0.0
        for (i in 0 until block - lag) { c += seg[i] * seg[i + lag]; e1 += seg[i + lag] * seg[i + lag] }
        val nc = c / sqrt(e0 * e1 + 1e-12)
        // Prefer the smallest lag above threshold (avoids octave-down picks).
        if (nc > 0.35 && (bestC < 0 || lag < bestLag)) { bestC = nc; bestLag = lag }
    }
    return if (bestC < 0) 0f else sr.toFloat() / bestLag
}

fun analyzeWav(wav: WavData): Analysis {
    val sr = wav.sampleRate
    val x = wav.samples
    // Trim leading/trailing near-silence.
    val env = envelope(x)
    val peak = env.maxOrNull() ?: 0f
    if (peak <= 0f) return Analysis(0f, 0.01f, 0.1f, 0.8f, 0.1f, 800f, 0f, Wave.SAW, 0f, 0f, 0f, 0f)
    val hopSec = 128f / sr
    val start = env.indexOfFirst { it > peak * 0.05f }.coerceAtLeast(0)
    val end = env.indexOfLast { it > peak * 0.05f }.coerceAtLeast(start)
    val durSec = (end - start + 1) * hopSec

    // Attack: 10% -> 90% rise.
    val i10 = env.indexOfFirst { it > peak * 0.1f }.coerceAtLeast(start)
    val i90 = env.indexOfFirst { it > peak * 0.9f }.let { if (it < 0) end else it }
    val attack = ((i90 - i10).coerceAtLeast(1) * hopSec).coerceIn(0.003f, 3f)

    // Sustain: median of the middle half, relative to peak.
    val mid = env.slice((start + end) / 2 * 3 / 4..(start + end) / 2 * 5 / 4).filter { it > 0 }
    val sustain = if (mid.isEmpty()) 0.8f
        else mid.sorted()[mid.size / 2] / peak

    // Release: last 50% -> 5% drop at the tail.
    val tailStart = (start + (end - start) * 0.6).toInt().coerceAtMost(end)
    val r50 = (tailStart..end).lastOrNull { env[it] > peak * 0.5f } ?: end
    val r05 = (r50..end).firstOrNull { env[it] < peak * 0.05f } ?: end
    val release = ((r05 - r50).coerceAtLeast(1) * hopSec).coerceIn(0.01f, 3f)

    // Decay: peak -> sustain level (from 90% point to where it settles).
    val iSus = (i90..end).firstOrNull { env[it] < peak * (sustain * 0.9f + 0.05f) } ?: end
    val decay = ((iSus - i90).coerceAtLeast(1) * hopSec).coerceIn(0.01f, 4f)

    // Pitch on the loudest block; pitch drop = first 60ms vs sustain pitch.
    val pitch = detectPitch(x, sr)
    val early = x.sliceArray(0 until (sr * 0.06).toInt().coerceAtMost(x.size))
    val pitchEarly = detectPitch(early, sr)
    val pitchDrop = if (pitch > 0 && pitchEarly > 0)
        (12 * ln(pitch / pitchEarly) / ln(2.0)).toFloat().coerceIn(-24f, 0f)
    else 0f

    // Brightness: centroid on attack block vs sustain block.
    val aStart = (start * 128).coerceAtMost(x.size - 1)
    val aEnd = (aStart + 2048).coerceAtMost(x.size)
    val sMid = ((start + end) / 2 * 128).toInt().coerceAtMost(x.size - 2048)
    val magA = magnitudeSpectrum(x.sliceArray(aStart until aEnd))
    val magS = magnitudeSpectrum(x.sliceArray(sMid until sMid + 2048))
    val centA = centroid(magA, sr)
    val centS = centroid(magS, sr)
    val brightness = centS.coerceIn(80.0, 12000.0).toFloat()
    val filterEnv = ((centA - centS) / centA.coerceAtLeast(1.0)).toFloat()
        .coerceIn(0f, 0.9f)

    // Harmonic structure on the sustain block -> wave choice + FM.
    val fund = pitch.coerceAtLeast(20f)
    val binHz = sr.toDouble() / (magS.size * 2)
    fun harmAmp(h: Int): Double {
        val target = fund * h / binHz
        val b0 = (target - 2).toInt().coerceAtLeast(1)
        val b1 = (target + 2).toInt().coerceAtMost(magS.size - 1)
        var m = 0.0
        for (b in b0..b1) m = max(m, magS[b])
        return m
    }
    val h1 = harmAmp(1).coerceAtLeast(1e-9)
    val h2 = harmAmp(2) / h1; val h3 = harmAmp(3) / h1
    val wave = when {
        h1 > 0 && h2 < 0.25 && h3 < 0.15 -> Wave.SINE
        h3 > h2 * 1.8 && h3 > 0.2 -> Wave.SQUARE
        h2 > 0.35 || h3 > 0.25 -> Wave.SAW
        else -> Wave.TRI
    }
    // Noise: energy above 6kHz relative to total.
    var hiE = 0.0; var totE = 0.0
    for (i in magS.indices) {
        val e = magS[i] * magS[i]
        totE += e
        if (i * binHz > 6000) hiE += e
    }
    val noise = (hiE / totE.coerceAtLeast(1e-12)).toFloat().coerceIn(0f, 0.6f)
    // FM for inharmonic/bright content with weak harmonics (bells, metallic).
    val fm = if (h2 < 0.15 && h3 < 0.15 && brightness > 1500) 0.35f else 0f

    return Analysis(
        pitchHz = pitch,
        attackSec = attack,
        decaySec = decay,
        sustainLevel = sustain.coerceIn(0f, 1f),
        releaseSec = release,
        brightnessHz = brightness,
        filterEnvAmt = filterEnv,
        wave = wave,
        fmAmount = fm,
        noiseLevel = noise,
        pitchDropSemi = pitchDrop,
        durationSec = durSec
    )
}

/** Builds a patch that mimics the analyzed reference. */
fun mimicPatch(a: Analysis, seed: Long): Patch {
    val r = kotlin.random.Random(seed)
    fun rf(lo: Float, hi: Float) = lo + r.nextFloat() * (hi - lo)
    val cutKnob = Patch.unmapLog(a.brightnessHz, 120f, 9000f).coerceIn(0.05f, 1f)
    val rootSemi = if (a.pitchHz > 0) {
        val midi = (69 + 12 * ln(a.pitchHz / 440.0) / ln(2.0)).toInt()
        ((midi - 33) % 12 + 12) % 12
    } else 0
    val oscs = List(4) { i ->
        OscLayer(
            wave = a.wave,
            octave = if (i == 3) -1 else 0,
            detuneCents = rf(-8f, 8f),
            level = if (i < 2) 0.28f else 0.15f,
            pan = rf(-0.3f, 0.3f)
        )
    }
    return Patch(
        seed = seed,
        tempo = 120f,
        rootSemi = rootSemi,
        notes = listOf(Note(rootSemi, 4f)),
        oscs = oscs,
        activeOscs = 3,
        fmRatio = 1f,
        fmAmount = a.fmAmount,
        spread = 0.35f,
        subLevel = if (a.brightnessHz < 400) 0.35f else 0.1f,
        noiseLevel = a.noiseLevel,
        cutoff = cutKnob,
        reso = 0.08f,
        filterEnv = a.filterEnvAmt,
        lfoRate = 0f,
        lfoDepth = 0f,
        vibrato = 0f,
        vibDelay = 0.25f,
        vibRate = 0.4f,
        breathAmt = (a.noiseLevel * 1.5f).coerceIn(0f, 0.5f),
        driftAmt = 0.15f,
        vowel = 0f,
        vowelMix = 0f,
        tremRate = 0.4f,
        tremDepth = 0f,
        growl = 0f,
        slideSemi = 0f,
        slideTime = 0.35f,
        clickAmt = 0f,
        flutterAmt = 0f,
        airAmt = (a.noiseLevel * 0.8f).coerceIn(0f, 0.4f),
        attack = a.attackSec.coerceIn(0.003f, 3f),
        decay = a.decaySec.coerceIn(0.01f, 4f),
        sustain = a.sustainLevel,
        release = a.releaseSec.coerceIn(0.01f, 3f),
        delayTime = 0.375f,
        delaySync = true,
        delayFb = 0f,
        delayMix = 0f,
        revSize = 0.5f,
        revDamp = 0.5f,
        revMix = 0.15f,
        drive = 0f,
        width = 0.4f,
        pitchEnvSemi = a.pitchDropSemi,
        pitchEnvTime = if (a.pitchDropSemi < -0.5f) 0.09f else 0.25f,
        velToCutoff = 0.4f,
        keyTrack = 0.7f
    )
}
