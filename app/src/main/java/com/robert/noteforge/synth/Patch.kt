package com.robert.noteforge.synth

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.random.Random

enum class Wave { SINE, TRI, SAW, SQUARE }

data class OscLayer(
    val wave: Wave,
    val octave: Int,       // -1, 0, +1
    val detuneCents: Float, // base detune, scaled by patch.spread
    val level: Float,
    val pan: Float          // -1..1
)

data class Note(val semitone: Int, val beats: Float)

/** Steers Patch.random(): 0.5 = neutral, 0/1 favor opposite extremes. */
data class RandomBias(
    val bright: Float = 0.5f,   // 0=dark … 1=bright
    val organic: Float = 0.5f,  // 0=synthetic … 1=organic
    val aggro: Float = 0.5f,    // 0=calm … 1=aggressive
    val space: Float = 0.5f,    // 0=dry … 1=spacious
    val motion: Float = 0.5f    // 0=static … 1=evolving
)

/**
 * A fully self-describing synth patch. Each patch is ONE complex evolving
 * tone (no melody): a sustained root note through the osc stack, FM, filter
 * and FX. Every randomized element is a plain field, so everything the dice
 * touches is also knob-tweakable.
 */
data class Patch(
    val seed: Long,
    val tempo: Float,      // knob 60..180 bpm — sets the synced-delay musical time
    val rootSemi: Int,     // knob 0..12 semitones above A1 (55 Hz); MIDI root = 33 + rootSemi
    val notes: List<Note>, // always exactly one sustained note
    val oscs: List<OscLayer>, // always 4 generated; activeOscs selects (knob); re-rollable via rerollOscs()
    val activeOscs: Int,   // knob 1..4
    val fmRatio: Float,    // knob: snapped musical ratios
    val fmAmount: Float,   // knob
    val spread: Float,     // knob: detune multiplier
    val subLevel: Float,   // knob
    val noiseLevel: Float, // knob
    val cutoff: Float,     // knob 0..1 -> 120..9000 Hz log
    val reso: Float,       // knob 0..1 -> Q 0.7..9
    val filterEnv: Float,  // knob
    val lfoRate: Float,    // knob 0..1 -> 0.05..7 Hz log
    val lfoDepth: Float,   // knob
    val vibrato: Float,    // knob 0..1 -> 0..50 cents
    val vibDelay: Float = 0f,   // knob 0..1 -> 0..0.8 s before vibrato fades in
    val vibRate: Float = 0.5f,    // knob 0..1 -> 4..8 Hz vibrato rate
    val breathAmt: Float = 0f,  // knob 0..1, breath/chiff noise burst at attack
    val driftAmt: Float = 0f,   // knob 0..1 -> 0..20 cents slow pitch wander
    val vowel: Float = 0f,      // knob 0..1 morphs formants A-E-I-O-U
    val vowelMix: Float = 0f,   // knob 0..1 formant filter mix
    val tremRate: Float = 0.4f,   // knob 0..1 -> 1..10 Hz tremolo rate
    val tremDepth: Float = 0f,  // knob 0..1 tremolo depth
    val growl: Float = 0f,      // knob 0..1, 30 Hz AM growl
    val slideSemi: Float = 0f,  // knob -12..+12 st, pitch glide into note
    val slideTime: Float = 0.15f, // knob 0.01..1.0 s glide time
    val clickAmt: Float = 0f,   // knob 0..1, mechanical key click at onset
    val flutterAmt: Float = 0f, // knob 0..1, flutter-tongue AM
    val airAmt: Float = 0f,      // knob 0..1, sustained breath/bow air
    val attack: Float,     // knob log 0.001..1.5 s
    val decay: Float,      // knob log 0.01..1.0 s
    val sustain: Float,    // knob 0..1
    val release: Float,    // knob log 0.02..1.5 s
    val delayTime: Float,  // knob 0..1 -> 40..850 ms log (free mode)
    val delaySync: Boolean,
    val delayFb: Float,    // knob
    val delayMix: Float,   // knob
    val revSize: Float,    // knob
    val revDamp: Float,    // knob
    val revMix: Float,     // knob
    val drive: Float,      // knob
    val width: Float,      // knob
    val pitchEnvSemi: Float, // knob -12..+12 st, pitch offset at note start
    val pitchEnvTime: Float, // knob 0.01..1.5 s, decay time of pitch env
    val velToCutoff: Float,  // knob 0..1, velocity -> filter brightness
    val keyTrack: Float      // knob 0..1, cutoff follows note pitch
) {
    fun cutoffHz() = mapCutoff(cutoff)
    fun resoQ() = mapReso(reso)
    fun lfoHz() = mapLfoRate(lfoRate)
    fun delayMs() = mapDelayMs(delayTime)
    fun vibDelaySec() = mapVibDelay(vibDelay)
    fun vibRateHz() = mapVibRate(vibRate)
    fun driftCentsMax() = mapDriftCents(driftAmt)
    fun tremRateHz() = mapTremRate(tremRate)
    fun slideTimeSec() = mapSlideTime(slideTime)

    /** MIDI note number of the patch root (A1 = 55 Hz = MIDI 33). */
    fun rootMidi() = 33 + rootSemi

    /** Change root, keeping the single sustained note in tune with it. */
    fun withRootSemi(rs: Int): Patch {
        val r = rs.coerceIn(0, 12)
        val beats = notes.firstOrNull()?.beats ?: 4f
        return copy(rootSemi = r, notes = listOf(Note(r, beats)))
    }

    /** Re-roll just the oscillator stack. */
    fun rerollOscs(): Patch =
        copy(oscs = genOscs(Random.Default))

    fun describe(): String {
        val sb = StringBuilder()
        sb.append("Tone ").append(midiToName(rootMidi()))
        sb.append(" · ").append(activeOscs).append("osc ")
        sb.append(oscs.take(activeOscs).joinToString("+") { it.wave.name.take(3) })
        if (fmAmount > 0.01f) sb.append(" · FM×").append(trimNum(fmRatio))
        if (subLevel > 0.01f) sb.append(" · sub")
        if (noiseLevel > 0.005f) sb.append(" · noise")
        sb.append(" · LP ").append(fmtHz(cutoffHz()))
        if (reso > 0.02f) sb.append(" Q").append(trimNum(resoQ()))
        if (vibrato > 0.02f) {
            sb.append(" · vib")
            if (vibDelay > 0.05f) sb.append("+dly")
        }
        if (breathAmt > 0.02f) sb.append(" · breath")
        if (driftAmt > 0.02f) sb.append(" · drift")
        if (vowelMix > 0.02f) sb.append(" · vw").append(vowelName(vowel))
        if (tremDepth > 0.02f) sb.append(" · trem")
        if (growl > 0.02f) sb.append(" · growl")
        if (slideSemi != 0f) sb.append(" · sld")
        if (clickAmt > 0.02f) sb.append(" · click")
        if (flutterAmt > 0.02f) sb.append(" · flut")
        if (airAmt > 0.02f) sb.append(" · air")
        if (delayMix > 0.01f) sb.append(if (delaySync) " · sync-dly" else " · dly ".plus(delayMs().toInt()).plus("ms"))
        if (revMix > 0.01f) sb.append(" · rev ").append(if (revSize > 0.66f) "hall" else if (revSize > 0.4f) "room" else "box")
        if (drive > 0.02f) sb.append(" · drive")
        return sb.toString()
    }

    companion object {
        fun mapCutoff(v: Float) = 120f * 75f.pow(v)          // 120..9000 Hz
        fun mapReso(v: Float) = 0.7f + v * 8.3f              // Q 0.7..9
        fun mapLfoRate(v: Float) = 0.05f * 140f.pow(v)       // 0.05..7 Hz
        fun mapVibDelay(v: Float) = v * 0.8f                    // 0..0.8 s
        fun mapVibRate(v: Float) = 4f + v * 4f                 // 4..8 Hz
        fun mapDriftCents(v: Float) = v * 20f                  // 0..20 cents
        fun mapTremRate(v: Float) = 1f + v * 9f                   // 1..10 Hz
        fun mapSlideTime(v: Float) = 0.01f * 100f.pow(v)            // 0.01..1.0 s
        // Vowel formant pairs (F1, F2) for A-E-I-O-U
        val VOWEL_F1 = floatArrayOf(730f, 530f, 270f, 570f, 300f)
        val VOWEL_F2 = floatArrayOf(1090f, 1840f, 2290f, 840f, 870f)
        val VOWEL_NAMES = arrayOf("A", "E", "I", "O", "U")
        fun vowelFormants(v: Float): Pair<Float, Float> {
            val x = (v.coerceIn(0f, 1f) * 4f)
            val i = x.toInt().coerceIn(0, 3)
            val f = x - i
            return Pair(
                VOWEL_F1[i] + (VOWEL_F1[i + 1] - VOWEL_F1[i]) * f,
                VOWEL_F2[i] + (VOWEL_F2[i + 1] - VOWEL_F2[i]) * f
            )
        }
        fun vowelName(v: Float) = VOWEL_NAMES[(v.coerceIn(0f, 1f) * 4f).roundToInt().coerceIn(0, 4)]
        fun mapDelayMs(v: Float) = 40f * 21.25f.pow(v)       // 40..850 ms
        fun mapTempo(v: Float) = 60f + v * 120f              // 60..180 bpm
        fun mapAttack(v: Float) = 0.001f * 1500f.pow(v)      // 0.001..1.5 s
        fun mapDecay(v: Float) = 0.01f * 100f.pow(v)         // 0.01..1.0 s
        fun mapRelease(v: Float) = 0.02f * 75f.pow(v)       // 0.02..1.5 s
        fun unmapLog(v: Float, min: Float, max: Float) =
            (ln(v / min) / ln(max / min)).toFloat().coerceIn(0f, 1f)

        val FM_RATIOS = floatArrayOf(0.5f, 1f, 1.5f, 2f, 3f, 4f, 5.5f, 7f, 8f)
        fun fmRatioIndex(ratio: Float): Int {
            var best = 0
            var bestD = Float.MAX_VALUE
            FM_RATIOS.forEachIndexed { i, r ->
                val d = kotlin.math.abs(r - ratio)
                if (d < bestD) { bestD = d; best = i }
            }
            return best
        }

        fun fmtHz(hz: Float) = if (hz >= 1000f) "${trimNum(hz / 1000f)}k" else "${hz.toInt()}"
        fun fmtMs(ms: Float) = "${ms.toInt()}ms"
        fun fmtTime(s: Float) = if (s < 1f) "${(s * 1000f).toInt()}ms" else "${trimNum(s)}s"
        fun trimNum(f: Float): String {
            val r = (f * 10f).toInt() / 10f
            return if (r == r.toInt().toFloat()) r.toInt().toString() else r.toString()
        }

        private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        fun midiToName(midi: Int) = "${NOTE_NAMES[midi % 12]}${midi / 12 - 1}"

        private fun genOscs(r: Random): List<OscLayer> {
            fun rf(a: Float, b: Float) = a + r.nextFloat() * (b - a)
            return (0 until 4).map {
                val w = when (r.nextFloat()) {
                    in 0f..0.34f -> Wave.SAW
                    in 0.34f..0.54f -> Wave.SQUARE
                    in 0.54f..0.74f -> Wave.TRI
                    else -> Wave.SINE
                }
                OscLayer(
                    wave = w,
                    octave = listOf(-1, 0, 0, 1)[r.nextInt(4)],
                    detuneCents = rf(-1f, 1f) * rf(4f, 30f),
                    level = 0.25f,
                    pan = rf(-0.8f, 0.8f)
                )
            }
        }

        fun random(seed: Long, bias: RandomBias = RandomBias()): Patch {
            val r = Random(seed)
            fun rf(a: Float, b: Float) = a + r.nextFloat() * (b - a)
            fun logRf(a: Float, b: Float): Float {
                val la = ln(a); val lb = ln(b)
                return exp(la + r.nextFloat() * (lb - la)).toFloat()
            }
            // Shift a 0..1 value toward a bias extreme.
            fun sh(v: Float, b: Float, amt: Float) =
                (v + (b - 0.5f) * 2f * amt).coerceIn(0f, 1f)
            // Shift a probability toward a bias extreme.
            fun pr(p: Float, b: Float, amt: Float) =
                (p + (b - 0.5f) * amt).coerceIn(0.02f, 0.98f)

            val tempo = rf(80f, 150f)
            val rootSemi = r.nextInt(0, 13)
            val sustainSecs = rf(3.5f, 6f)

            val fmOn = r.nextFloat() < pr(0.55f, bias.aggro, 0.3f)

            return Patch(
                seed = seed,
                tempo = tempo,
                rootSemi = rootSemi,
                notes = listOf(Note(rootSemi, sustainSecs * tempo / 60f)),
                oscs = genOscs(r),
                activeOscs = r.nextInt(2, 5),
                fmRatio = if (fmOn) listOf(0.5f, 1f, 1.5f, 2f, 3f, 4f, 5.5f)[r.nextInt(7)] else 1f,
                fmAmount = if (fmOn) sh(rf(0.15f, 0.85f), bias.aggro, 0.2f) * (1f - (bias.organic - 0.5f) * 0.6f) else 0f,
                spread = rf(0.3f, 1f),
                subLevel = if (r.nextFloat() < 0.5f) rf(0.1f, 0.5f) else 0f,
                noiseLevel = if (r.nextFloat() < 0.35f) rf(0.02f, 0.25f) else 0f,
                cutoff = sh(rf(0.1f, 0.9f), bias.bright, 0.25f),
                reso = if (r.nextFloat() < pr(0.25f, bias.aggro, 0.3f)) 0f else sh(rf(0.05f, 0.75f), bias.aggro, 0.2f),
                filterEnv = sh(rf(0f, 1f), bias.motion, 0.25f),
                lfoRate = rf(0f, 1f),
                lfoDepth = if (r.nextFloat() < pr(0.3f, bias.motion, 0.4f)) 0f else rf(0.1f, 1f),
                vibrato = if (r.nextFloat() < pr(0.4f, bias.organic, 0.4f)) 0f else rf(0.05f, 0.8f),
                vibDelay = if (r.nextFloat() < 0.5f) 0f else rf(0.05f, 0.7f),
                vibRate = rf(0.15f, 0.85f),
                breathAmt = if (r.nextFloat() < pr(0.55f, bias.organic, 0.4f)) 0f else rf(0.05f, 0.6f),
                driftAmt = if (r.nextFloat() < pr(0.5f, bias.organic, 0.3f)) 0f else rf(0.05f, 0.7f),
                vowel = rf(0f, 1f),
                vowelMix = if (r.nextFloat() < pr(0.55f, bias.organic, 0.4f)) 0f else rf(0.15f, 0.7f),
                tremRate = rf(0.1f, 0.8f),
                tremDepth = if (r.nextFloat() < pr(0.6f, bias.motion, 0.4f)) 0f else rf(0.1f, 0.7f),
                growl = if (r.nextFloat() < pr(0.7f, bias.aggro, 0.4f)) 0f else rf(0.1f, 0.6f),
                slideSemi = if (r.nextFloat() < pr(0.7f, bias.organic, 0.3f)) 0f else rf(-7f, 5f),
                slideTime = rf(0.1f, 0.6f),
                clickAmt = if (r.nextFloat() < pr(0.65f, bias.organic, 0.3f)) 0f else rf(0.1f, 0.6f),
                flutterAmt = if (r.nextFloat() < pr(0.75f, bias.aggro, 0.3f)) 0f else rf(0.15f, 0.7f),
                airAmt = if (r.nextFloat() < pr(0.55f, bias.organic, 0.4f)) 0f else rf(0.05f, 0.5f),
                attack = logRf(0.005f, 0.3f),
                decay = rf(0.05f, 0.4f),
                sustain = rf(0.35f, 0.9f),
                release = rf(0.08f, 0.4f),
                delayTime = rf(0f, 1f),
                delaySync = r.nextBoolean(),
                delayFb = rf(0.15f, 0.7f),
                delayMix = if (r.nextFloat() < pr(0.2f, bias.space, 0.3f)) 0f else sh(rf(0.05f, 0.5f), bias.space, 0.2f),
                revSize = rf(0.25f, 0.95f),
                revDamp = rf(0.15f, 0.85f),
                revMix = sh(rf(0.08f, 0.45f), bias.space, 0.2f),
                drive = if (r.nextFloat() < pr(0.35f, bias.aggro, 0.4f)) 0f else sh(rf(0.05f, 0.7f), bias.aggro, 0.2f),
                width = rf(0.2f, 1f),
                pitchEnvSemi = 0f,
                pitchEnvTime = 0.25f,
                velToCutoff = 0f,
                keyTrack = 0f
            )
        }
    }
}
