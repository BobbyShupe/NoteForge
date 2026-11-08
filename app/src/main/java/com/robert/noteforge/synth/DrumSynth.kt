package com.robert.noteforge.synth

import kotlin.math.*
import kotlin.random.Random

/**
 * Renders a DrumPatch to PCM. Pure Kotlin, no dependencies.
 * Sample rate is configurable; 44.1kHz for render, 22.05kHz for preview.
 */
object DrumSynth {

    fun render(patch: DrumPatch, sampleRate: Int = 44100): FloatArray {
        val r = Random(patch.seed + 999)

        // Duration: based on decay, max 2 seconds
        val decaySec = (0.05 * 2.0.pow((patch.decay * 5.0).toDouble())).toFloat() // 0.05..1.6s
        val durationSec = (decaySec + 0.1f).coerceIn(0.2f, 2.0f)
        val n = (durationSec * sampleRate).toInt()
        val out = FloatArray(n)

        // Envelope parameters
        val attackSec = 0.0005 * 2.0.pow((patch.attack * 5.0).toDouble()) // 0.0005..0.016s
        val attackSamp = max(1, (attackSec * sampleRate).toInt())

        // Pitch envelope
        val pitchDecaySec = 0.005 * 2.0.pow((patch.pitchEnvDecay * 6.0).toDouble()) // 0.005..0.32s
        val pitchStartMult = 1f + patch.pitchEnvAmt * 3f // Up to 4x start pitch

        // Tone decay
        val toneDecaySec = 0.05 * 2.0.pow((patch.toneDecay * 5.0).toDouble())

        // Noise
        val noiseDecaySec = 0.01 * 2.0.pow((patch.noiseDecay * 6.0).toDouble())
        val noiseFilterHz = 500f * 2.0.pow((patch.noiseFilterFreq * 4.5f).toDouble()) // 500..~11k

        // Click
        val clickHz = 1000f * 2.0.pow((patch.clickFreq * 3f).toDouble()) // 1k..8k

        // Metallic partials (inharmonic)
        val metallicRatios = floatArrayOf(1.48f, 1.92f, 2.71f, 3.36f, 4.07f)
        val metallicDecaySec = 0.1 * 2.0.pow((patch.metallicDecay * 4f).toDouble())

        // Simple one-pole filters for noise
        var noiseLpY = 0f
        var noiseHpY = 0f
        var noiseHpX = 0f
        val noiseAlpha = exp(-2.0 * PI * noiseFilterHz / sampleRate.toDouble()).toFloat()

        var phase = 0f
        var metallicPhase = FloatArray(metallicRatios.size)

        for (i in 0 until n) {
            val t = i / sampleRate.toFloat()

            // Amplitude envelope: attack then exponential decay
            val env = if (i < attackSamp) {
                i.toFloat() / attackSamp
            } else {
                exp(-(t - attackSec) / decaySec).toFloat()
            }

            var sample = 0f

            // --- Tone body (sine with pitch envelope) ---
            if (patch.toneLevel > 0.01f) {
                val pitchMult = 1f + (pitchStartMult - 1f) * exp(-t / pitchDecaySec).toFloat()
                val freq = patch.baseFreq * pitchMult
                phase += 2f * PI.toFloat() * freq / sampleRate
                if (phase > 2f * PI) phase -= 2f * PI.toFloat()
                val toneEnv = exp(-t / toneDecaySec).toFloat()
                // Add body (low-end boost) via slightly saturated sine
                var tone = sin(phase)
                if (patch.body > 0.01f) {
                    val bodyAmt = 1f + patch.body * 2f
                    tone = (tanh((tone * bodyAmt).toDouble()) / tanh(bodyAmt.toDouble())).toFloat()
                }
                sample += tone * patch.toneLevel * toneEnv
            }

            // --- Noise ---
            if (patch.noiseLevel > 0.01f) {
                val noise = r.nextFloat() * 2f - 1f
                val noiseEnv = exp(-t / noiseDecaySec).toFloat()
                // Filter
                val filtered = if (patch.noiseHighpass) {
                    // Highpass: y = alpha * (y + x - x_prev)
                    noiseHpY = noiseAlpha * (noiseHpY + noise - noiseHpX)
                    noiseHpX = noise
                    noiseHpY
                } else {
                    // Lowpass: y += (1-alpha) * (x - y)
                    noiseLpY += (1f - noiseAlpha) * (noise - noiseLpY)
                    noiseLpY
                }
                sample += filtered * patch.noiseLevel * noiseEnv * 2f
            }

            // --- Click transient ---
            if (patch.clickLevel > 0.01f && t < 0.02f) {
                val clickEnv = exp((-t / 0.003f).toDouble()).toFloat()
                val click = sin((2f * PI.toFloat() * clickHz * t).toDouble()).toFloat() * clickEnv
                sample += click * patch.clickLevel
            }

            // --- Metallic partials ---
            if (patch.metallic > 0.01f) {
                val metEnv = exp(-t / metallicDecaySec).toFloat()
                var met = 0f
                for (j in metallicRatios.indices) {
                    metallicPhase[j] += 2f * PI.toFloat() * patch.baseFreq * metallicRatios[j] / sampleRate
                    met += sin(metallicPhase[j]) / metallicRatios.size
                }
                sample += met * patch.metallic * metEnv * 0.5f
            }

            // --- Drive/saturation ---
            if (patch.drive > 0.01f) {
                val driveAmt = 1f + patch.drive * 4f
                sample = (tanh((sample * driveAmt).toDouble()) / tanh(driveAmt.toDouble())).toFloat()
            }

            out[i] = sample * env
        }

        // Peak normalize to -1dB
        var peak = 0f
        for (v in out) {
            val a = abs(v)
            if (a > peak) peak = a
        }
        if (peak > 0.89f) {
            val g = 0.89f / peak
            for (i in out.indices) out[i] *= g
        }

        return out
    }

    /** Render to 16-bit PCM for playback. */
    fun renderToShort(patch: DrumPatch, sampleRate: Int = 22050): ShortArray {
        val floatPcm = render(patch, sampleRate)
        return ShortArray(floatPcm.size) { i ->
            (floatPcm[i].coerceIn(-1f, 1f) * 32767).toInt().toShort()
        }
    }
}
