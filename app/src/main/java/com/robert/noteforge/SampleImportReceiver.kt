package com.robert.noteforge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.robert.noteforge.data.Instrument
import com.robert.noteforge.data.InstrumentRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Quiet entry point for patches fired in from WildPatch ("Send to NoteForge").
 * The live patch JSON becomes a synth instrument — editable, rendered per
 * note — entirely in the background, no UI opens. The instrument is waiting
 * in the Instruments tab next time NoteForge is opened.
 */
@AndroidEntryPoint
class SampleImportReceiver : BroadcastReceiver() {

    @Inject lateinit var instrumentRepository: InstrumentRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != MainActivity.ACTION_IMPORT_SAMPLE) return
        val name = intent.getStringExtra(MainActivity.EXTRA_NAME) ?: "WildPatch"
        val rootMidi = intent.getIntExtra(MainActivity.EXTRA_ROOT_MIDI, 60)
        val patchJson = intent.getStringExtra(MainActivity.EXTRA_PATCH_JSON)?.let { json ->
            // Validate before trusting it; ignore the send on garbage.
            try {
                com.robert.noteforge.synth.patchFromJson(org.json.JSONObject(json))
                json
            } catch (_: Exception) { null }
        } ?: return
        val pending = goAsync()
        scope.launch {
            try {
                instrumentRepository.insert(
                    Instrument(
                        name = name,
                        audioFileId = 0, // synth instruments hold no audio file
                        rootNoteMidi = rootMidi.coerceIn(0, 127),
                        synthPatchJson = patchJson
                    )
                )
            } catch (_: Exception) {
                // quiet by design; WildPatch already confirmed the send
            } finally {
                pending.finish()
            }
        }
    }
}
