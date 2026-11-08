package com.robert.noteforge.synth

import kotlin.math.*
import kotlin.random.Random

/**
 * Offline software synth: renders a Patch to a stereo float buffer.
 * Voice -> resonant lowpass (env + LFO modulated) -> drive ->
 * ping-pong delay -> Schroeder-ish reverb -> stereo width -> normalize.
 * Pure Kotlin, no Android dependencies.
 */
class SynthEngine(val sr: Int = 44100) {

    class StereoBuf(val l: FloatArray, val r: FloatArray) {
        val n: Int get() = l.size
    }

    // deterministic noise source, reseeded per patch
    private var nzState = 0x12345678
    private fun white(): Float {
        var x = nzState
        x = x xor (x shl 13); x = x xor (x ushr 17); x = x xor (x shl 5)
        nzState = x
        return x.toFloat() / 2147483648f
    }

    private fun blep(phase: Float, dt: Float): Float {
        if (dt <= 0f) return 0f
        var x = 0f
        if (phase < dt) {
            val t = phase / dt
            x = t + t - t * t - 1f
        } else if (phase > 1f - dt) {
            val t = (phase - 1f) / dt
            x = t * t + t + t + 1f
        }
        return x
    }

    private fun wave(w: Wave, phase: Float, dt: Float): Float {
        var p = phase % 1f
        if (p < 0f) p += 1f
        return when (w) {
            Wave.SINE -> sin(2.0 * PI * p).toFloat()
            Wave.TRI -> 4f * abs(p - 0.5f) - 1f
            Wave.SAW -> (2f * p - 1f) - blep(p, dt)
            Wave.SQUARE -> (if (p < 0.5f) 1f else -1f) + blep(p, dt) - blep((p + 0.5f) % 1f, dt)
        }
    }

    private fun panGains(pan: Float): Pair<Float, Float> {
        val a = (pan.coerceIn(-1f, 1f) + 1f) * PI.toFloat() / 4f
        return Pair(cos(a), sin(a))
    }

    private fun adsr(t: Float, dur: Float, a: Float, d: Float, s: Float, r: Float): Float {
        return when {
            a > 0f && t < a -> t / a
            t < a + d -> 1f - (1f - s) * (t - a) / d
            t < dur -> s
            else -> {
                val k = 1f - (t - dur) / r
                if (k <= 0f) 0f else s * k * k
            }
        }
    }

    /**
     * Renders a patch. [tailSec] is the extra time after the last note for the
     * release/FX tail; callers that need exact durations (e.g. a sequencer)
     * pass a short tail and truncate afterwards.
     */
    fun render(p: Patch, tailSec: Float = 1.8f, velocity: Float = 1f): StereoBuf {
        nzState = (p.seed xor 0x9E3779B9L).toInt()
        val beatSec = 60f / p.tempo
        var seqSec = 0f
        for (nt in p.notes) seqSec += nt.beats * beatSec
        val totalSec = (seqSec + tailSec).coerceIn(0.5f, 12f)
        val n = (totalSec * sr).toInt()
        val L = FloatArray(n)
        val R = FloatArray(n)
        val env = FloatArray(n) // summed note envelopes, drives the filter env

        val nOsc = p.activeOscs.coerceIn(1, p.oscs.size)
        val oscGain = p.oscs.size.toFloat() / nOsc // keep loudness steady vs osc count
        val phL = FloatArray(nOsc)
        val phR = FloatArray(nOsc)
        val pr = Random(p.seed)
        for (i in 0 until nOsc) { phL[i] = pr.nextFloat(); phR[i] = pr.nextFloat() }
        var modPh = pr.nextFloat()
        var subPh = pr.nextFloat()
        val driftPh1 = pr.nextFloat() * 2f * PI.toFloat()
        val driftPh2 = pr.nextFloat() * 2f * PI.toFloat()

        val freqs = FloatArray(p.notes.size) { i -> 55f * 2f.pow(p.notes[i].semitone / 12f) }

        var tSec = 0f
        for (i in p.notes.indices) {
            val dur = p.notes[i].beats * beatSec
            val fTarg = freqs[i]
            val startSamp = (tSec * sr).toInt()
            val durSamp = (dur * sr).toInt()
            val relSamp = (p.release * sr).toInt().coerceAtMost(sr / 2)
            val m = durSamp + relSamp
            val tl = FloatArray(m)
            val tr = FloatArray(m)
            var nzLp = 0f
            for (s in 0 until m) {
                val tt = s.toFloat() / sr
                val gt = tSec + tt
                // Pitch envelope: offset decays exponentially from note start.
                // Slide: linear portamento glide into the note (strings/brass).
                val slideK = (1f - tt / p.slideTimeSec()).coerceIn(0f, 1f)
                val bendSemi = p.pitchEnvSemi *
                    exp(-tt / p.pitchEnvTime.coerceAtLeast(0.005f)) +
                    p.slideSemi * slideK
                val fr = fTarg * 2f.pow(bendSemi / 12f)
                // Vibrato: fades in after vibDelay, at vibRate Hz.
                val vibEnv = ((tt - p.vibDelaySec()) / 0.3f).coerceIn(0f, 1f)
                val vibEnvS = vibEnv * vibEnv * (3f - 2f * vibEnv)
                val vibCents = p.vibrato * 50f * vibEnvS * sin(2.0 * PI * p.vibRateHz() * gt).toFloat()
                // Pitch drift: slow organic wander, two incommensurate LFOs.
                val driftCents = p.driftCentsMax() * (0.6f * sin(2.0 * PI * 0.13 * gt + driftPh1).toFloat() + 0.4f * sin(2.0 * PI * 0.31 * gt + driftPh2).toFloat())
                val frv = fr * 2f.pow((vibCents + driftCents) / 1200f)
                // FM
                modPh += frv * p.fmRatio / sr
                if (modPh >= 1f) modPh -= 1f
                val fmEnv = 1f - 0.6f * (s.toFloat() / m)
                val fmIdx = p.fmAmount * 5f * fmEnv
                val fm = if (fmIdx > 0.001f) sin(2.0 * PI * modPh).toFloat() * fmIdx else 0f

                var vl = 0f
                var vr = 0f
                for (o in 0 until nOsc) {
                    val osc = p.oscs[o]
                    val base = frv * 2f.pow(osc.octave.toFloat())
                    val det = osc.detuneCents * (0.25f + p.spread * 1.5f)
                    val dtL = base * 2f.pow(det / 1200f) / sr
                    val dtR = base * 2f.pow(-det / 1200f) / sr
                    phL[o] += dtL; if (phL[o] >= 1f) phL[o] -= 1f
                    phR[o] += dtR; if (phR[o] >= 1f) phR[o] -= 1f
                    val sL = wave(osc.wave, phL[o] + fm, dtL)
                    val sR = wave(osc.wave, phR[o] + fm, dtR)
                    val (gL, gR) = panGains(osc.pan)
                    vl += sL * osc.level * gL * oscGain
                    vr += sR * osc.level * gR * oscGain
                }
                if (p.subLevel > 0f) {
                    subPh += frv * 0.5f / sr
                    if (subPh >= 1f) subPh -= 1f
                    val s = sin(2.0 * PI * subPh).toFloat() * p.subLevel * 0.9f
                    vl += s; vr += s
                }
                if (p.noiseLevel > 0f || p.breathAmt > 0f) {
                    nzLp += 0.08f * (white() - nzLp)
                    val nz = nzLp * p.noiseLevel * 2f
                    vl += nz; vr += nz
                    if (p.breathAmt > 0f) {
                        val breathEnv = exp(-tt / 0.08f)
                        val br = nzLp * p.breathAmt * 3f * breathEnv
                        vl += br; vr += br
                    }
                    if (p.clickAmt > 0f && tt < 0.008f) {
                        val ck = white() * p.clickAmt * 2.5f * (1f - tt / 0.008f)
                        vl += ck; vr += ck
                    }
                }
                if (p.airAmt > 0f) {
                    val nzHp = white() - nzLp
                    val ar = nzHp * p.airAmt * 0.6f
                    vl += ar; vr += ar
                }
                val e = adsr(tt, dur, p.attack, p.decay, p.sustain, p.release)
                var amp = e
                if (p.tremDepth > 0.001f) {
                    val trm = sin(2.0 * PI * p.tremRateHz() * gt).toFloat()
                    amp *= 1f - p.tremDepth * 0.5f * (1f + trm)
                }
                if (p.growl > 0.001f) {
                    val gr = sin(2.0 * PI * 30.0 * gt).toFloat()
                    amp *= 1f + p.growl * 0.45f * gr
                }
                if (p.flutterAmt > 0.001f) {
                    val fl = sin(2.0 * PI * 18.0 * gt +
                        3f * sin(2.0 * PI * 7.3 * gt).toFloat()).toFloat()
                    amp *= 1f + p.flutterAmt * 0.5f * fl
                }
                tl[s] = vl * amp
                tr[s] = vr * amp
                val es = startSamp + s
                if (es < n) env[es] += e
            }
            for (s in 0 until m) {
                val idx = startSamp + s
                if (idx < n) { L[idx] += tl[s]; R[idx] += tr[s] }
            }
            tSec += dur
        }

        val noteSemi = p.notes.firstOrNull()?.semitone ?: 0
        applyFilter(p, L, R, env, n, noteSemi, velocity)
        applyFormant(p, L, R, n)
        applyDrive(p, L, R, n)
        applyDelay(p, L, R, n, beatSec)
        applyReverb(p, L, R, n)
        applyWidth(p, L, R, n)

        // fades + normalize
        var peak = 0f
        for (s in 0 until n) {
            val a = abs(L[s]); if (a > peak) peak = a
            val b = abs(R[s]); if (b > peak) peak = b
        }
        val g = if (peak > 0.001f) 0.89f / peak else 1f
        val fadeIn = (0.015f * sr).toInt().coerceAtLeast(1)
        val fadeOut = (0.35f * sr).toInt().coerceAtMost(n / 2)
        for (s in 0 until n) {
            var k = 1f
            if (s < fadeIn) k = s.toFloat() / fadeIn
            else if (s >= n - fadeOut) {
                val t = (n - 1 - s).toFloat() / fadeOut
                k = t * t * (3f - 2f * t)
            }
            L[s] *= g * k
            R[s] *= g * k
        }
        return StereoBuf(L, R)
    }

    private fun applyFilter(
        p: Patch, L: FloatArray, R: FloatArray, env: FloatArray, n: Int,
        noteSemi: Int = 0, velocity: Float = 1f
    ) {
        var baseCut = p.cutoffHz()
        // Keytrack: cutoff follows the note's pitch relative to the patch root.
        baseCut *= 2f.pow((noteSemi - p.rootSemi) / 12f * p.keyTrack)
        // Velocity -> brightness: harder hits open the filter.
        baseCut *= 2f.pow((velocity - 0.6f) * p.velToCutoff * 2f)
        val q = p.resoQ()
        val lfoW = (2.0 * PI * p.lfoHz()).toFloat()
        var x1L = 0f; var x2L = 0f; var y1L = 0f; var y2L = 0f
        var x1R = 0f; var x2R = 0f; var y1R = 0f; var y2R = 0f
        for (s in 0 until n) {
            val gt = s.toFloat() / sr
            val e = min(env[s], 1.5f)
            var cut = baseCut * (1f + p.filterEnv * 7f * e)
            cut *= 1f + p.lfoDepth * 0.85f * sin(lfoW * gt)
            cut = cut.coerceIn(50f, 15000f)
            val w0 = 2f * PI.toFloat() * cut / sr
            val cw = cos(w0); val sw = sin(w0)
            val alpha = sw / (2f * q)
            val b0 = (1f - cw) / 2f; val b1 = 1f - cw; val b2 = b0
            val a0 = 1f + alpha; val a1 = -2f * cw; val a2 = 1f - alpha
            val xl = L[s]; val xr = R[s]
            val yl = (b0 * xl + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L) / a0
            val yr = (b0 * xr + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R) / a0
            x2L = x1L; x1L = xl; y2L = y1L; y1L = yl
            x2R = x1R; x1R = xr; y2R = y1R; y1R = yr
            L[s] = yl; R[s] = yr
        }
    }

    /** Vowel formant filter: two parallel bandpass resonators mixed with dry. */
    private fun applyFormant(p: Patch, L: FloatArray, R: FloatArray, n: Int) {
        if (p.vowelMix <= 0.001f) return
        val (f1, f2) = Patch.vowelFormants(p.vowel)
        val q = 6f
        fun coeffs(f: Float): FloatArray {
            val w0 = 2f * PI.toFloat() * f.coerceIn(80f, 12000f) / sr
            val alpha = sin(w0) / (2f * q)
            val b0 = alpha; val b1 = 0f; val b2 = -alpha
            val a0 = 1f + alpha; val a1 = -2f * cos(w0); val a2 = 1f - alpha
            return floatArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
        }
        val c1 = coeffs(f1); val c2 = coeffs(f2)
        var x1aL = 0f; var x2aL = 0f; var y1aL = 0f; var y2aL = 0f
        var x1bL = 0f; var x2bL = 0f; var y1bL = 0f; var y2bL = 0f
        var x1aR = 0f; var x2aR = 0f; var y1aR = 0f; var y2aR = 0f
        var x1bR = 0f; var x2bR = 0f; var y1bR = 0f; var y2bR = 0f
        val mix = p.vowelMix.coerceIn(0f, 1f)
        for (s in 0 until n) {
            val xl = L[s]; val xr = R[s]
            val yaL = c1[0] * xl + c1[1] * x1aL + c1[2] * x2aL - c1[3] * y1aL - c1[4] * y2aL
            val ybL = c2[0] * xl + c2[1] * x1bL + c2[2] * x2bL - c2[3] * y1bL - c2[4] * y2bL
            val yaR = c1[0] * xr + c1[1] * x1aR + c1[2] * x2aR - c1[3] * y1aR - c1[4] * y2aR
            val ybR = c2[0] * xr + c2[1] * x1bR + c2[2] * x2bR - c2[3] * y1bR - c2[4] * y2bR
            x2aL = x1aL; x1aL = xl; y2aL = y1aL; y1aL = yaL
            x2bL = x1bL; x1bL = xl; y2bL = y1bL; y1bL = ybL
            x2aR = x1aR; x1aR = xr; y2aR = y1aR; y1aR = yaR
            x2bR = x1bR; x1bR = xr; y2bR = y1bR; y1bR = ybR
            val formL = (yaL + ybL) * 2.2f
            val formR = (yaR + ybR) * 2.2f
            L[s] = xl * (1f - mix) + formL * mix
            R[s] = xr * (1f - mix) + formR * mix
        }
    }

    private fun applyDrive(p: Patch, L: FloatArray, R: FloatArray, n: Int) {
        if (p.drive <= 0.001f) return
        val k = (1f + p.drive * 6f) / 1.5f
        for (s in 0 until n) {
            L[s] = tanh(L[s] * k).toFloat()
            R[s] = tanh(R[s] * k).toFloat()
        }
    }

    private fun applyDelay(p: Patch, L: FloatArray, R: FloatArray, n: Int, beatSec: Float) {
        if (p.delayMix <= 0.001f) return
        val dMs = if (p.delaySync) beatSec * 750f else p.delayMs() // dotted 8th when synced
        val dSamp = ((dMs / 1000f * sr).toInt()).coerceIn(1, sr * 2)
        val dlL = FloatArray(dSamp)
        val dlR = FloatArray(dSamp)
        var dp = 0
        val fb = p.delayFb.coerceAtMost(0.85f)
        val mix = p.delayMix
        for (s in 0 until n) {
            val dryL = L[s]; val dryR = R[s]
            val ol = dlL[dp]; val orr = dlR[dp]
            L[s] = dryL + ol * mix
            R[s] = dryR + orr * mix
            dlL[dp] = dryL + orr * fb // ping-pong: feed opposite channel
            dlR[dp] = dryR + ol * fb
            dp++
            if (dp >= dSamp) dp = 0
        }
    }

    private class Comb(val buf: FloatArray) { var idx = 0; var f = 0f }
    private class Ap(val buf: FloatArray) { var idx = 0 }

    private fun allpass(ap: Ap, x: Float): Float {
        val b = ap.buf[ap.idx]
        val y = b - 0.5f * x
        ap.buf[ap.idx] = x + 0.5f * y
        ap.idx++
        if (ap.idx >= ap.buf.size) ap.idx = 0
        return y
    }

    private fun applyReverb(p: Patch, L: FloatArray, R: FloatArray, n: Int) {
        if (p.revMix <= 0.001f) return
        val combT = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val apT = intArrayOf(556, 441, 341, 225)
        val room = 0.7f + p.revSize * 0.28f
        val damp = 0.25f + p.revDamp * 0.65f
        val combs = Array(8) { Comb(FloatArray(combT[it])) }
        val aps = Array(4) { Ap(FloatArray(apT[it])) }
        val wet = p.revMix * 1.6f
        for (s in 0 until n) {
            val inpL = L[s]; val inpR = R[s]
            var outL = 0f; var outR = 0f
            for (c in 0 until 8) {
                val cb = combs[c]
                val v = cb.buf[cb.idx]
                cb.f = v * (1f - damp) + cb.f * damp
                cb.buf[cb.idx] = (if (c % 2 == 0) inpL else inpR) + cb.f * room
                cb.idx++
                if (cb.idx >= cb.buf.size) cb.idx = 0
                if (c % 2 == 0) outL += v else outR += v
            }
            outL *= 0.25f; outR *= 0.25f
            val wetL = allpass(aps[2], allpass(aps[0], outL))
            val wetR = allpass(aps[3], allpass(aps[1], outR))
            L[s] = inpL + wetL * wet
            R[s] = inpR + wetR * wet
        }
    }

    private fun applyWidth(p: Patch, L: FloatArray, R: FloatArray, n: Int) {
        val side = 0.15f + p.width * 1.5f
        for (s in 0 until n) {
            val mid = (L[s] + R[s]) * 0.5f
            val sd = (L[s] - R[s]) * 0.5f * side
            L[s] = mid + sd
            R[s] = mid - sd
        }
    }
}
