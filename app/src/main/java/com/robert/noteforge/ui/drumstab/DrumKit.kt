package com.robert.noteforge.ui.drumstab

import android.content.Context
import com.robert.noteforge.synth.DrumBias
import com.robert.noteforge.synth.DrumPatch
import com.robert.noteforge.synth.DrumType
import com.robert.noteforge.synth.GmDrumMap
import org.json.JSONObject

/**
 * A drum kit maps GM drum pitches to drum patches.
 * Stored in SharedPreferences as JSON: {pitch: patchJson}.
 */
data class DrumKit(
    val assignments: Map<Int, DrumPatch> = emptyMap()
) {
    fun getPatch(pitch: Int): DrumPatch? = assignments[pitch]

    fun toJson(): String {
        return JSONObject().apply {
            for ((pitch, patch) in assignments) {
                put(pitch.toString(), JSONObject(patch.toJson()))
            }
        }.toString()
    }

    companion object {
        fun fromJson(json: String): DrumKit {
            return try {
                val obj = JSONObject(json)
                val map = mutableMapOf<Int, DrumPatch>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val pitch = key.toIntOrNull() ?: continue
                    val patchJson = obj.getJSONObject(key).toString()
                    DrumPatch.fromJson(patchJson)?.let { map[pitch] = it }
                }
                DrumKit(map)
            } catch (_: Exception) {
                DrumKit()
            }
        }

        /** Default kit: factory presets mapped to GM pitches. */
        fun default(): DrumKit {
            val presets = getFactoryPresets()
            fun presetFor(type: DrumType): DrumPatch {
                return presets.firstOrNull { it.patch.type == type }?.patch
                    ?: DrumPatch.random(System.currentTimeMillis(), type, DrumBias())
            }
            val map = mutableMapOf<Int, DrumPatch>()
            // Map common GM pitches to drum types
            val pitchToType = mapOf(
                35 to DrumType.KICK, 36 to DrumType.KICK,
                37 to DrumType.RIM, 38 to DrumType.SNARE, 39 to DrumType.SNARE, 40 to DrumType.SNARE,
                41 to DrumType.TOM, 42 to DrumType.HAT_CLOSED, 43 to DrumType.TOM,
                44 to DrumType.HAT_CLOSED, 45 to DrumType.TOM, 46 to DrumType.HAT_OPEN, 47 to DrumType.TOM,
                48 to DrumType.TOM, 49 to DrumType.CYMBAL, 50 to DrumType.TOM,
                51 to DrumType.RIM, 52 to DrumType.CYMBAL, 53 to DrumType.RIM,
                54 to DrumType.TOM, 55 to DrumType.CYMBAL, 56 to DrumType.CLAP, 57 to DrumType.CYMBAL,
                59 to DrumType.RIM, 69 to DrumType.CLAP, 70 to DrumType.RIM
            )
            for ((pitch, type) in pitchToType) {
                map[pitch] = presetFor(type)
            }
            return DrumKit(map)
        }
    }
}

private const val KIT_PREFS = "drum_kit"
private const val KIT_KEY = "kit"

fun loadKit(context: Context): DrumKit {
    val prefs = context.getSharedPreferences(KIT_PREFS, Context.MODE_PRIVATE)
    val json = prefs.getString(KIT_KEY, null) ?: return DrumKit.default()
    val kit = DrumKit.fromJson(json)
    // If empty (corrupt), return default
    return if (kit.assignments.isEmpty()) DrumKit.default() else kit
}

fun saveKit(context: Context, kit: DrumKit) {
    context.getSharedPreferences(KIT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(KIT_KEY, kit.toJson())
        .apply()
}
