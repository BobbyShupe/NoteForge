package com.robert.noteforge.ui.instrumentstab

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.robert.noteforge.data.AudioFile
import com.robert.noteforge.data.Instrument
import com.robert.noteforge.synth.Patch
import com.robert.noteforge.synth.patchFromJson
import com.robert.noteforge.synth.toJson
import com.robert.noteforge.util.Midi
import org.json.JSONObject
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun InstrumentsScreen(modifier: Modifier = Modifier, viewModel: InstrumentsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var editingInstrument by remember { mutableStateOf<Instrument?>(null) }
    val listState = rememberLazyListState()
    var prevCount by remember { mutableStateOf(0) }
    // Scroll to a newly added instrument (list is newest-first).
    LaunchedEffect(state.instruments.size) {
        val n = state.instruments.size
        if (n > prevCount && prevCount > 0) {
            listState.animateScrollToItem(0)
        }
        prevCount = n
    }

    val syncMsg by viewModel.syncMessage.collectAsState()
    LaunchedEffect(syncMsg) {
        if (syncMsg != null) {
            kotlinx.coroutines.delay(3500)
            viewModel.clearSyncMessage()
        }
    }

    // Sync-server address, editable here and persisted across restarts.
    // Blank until Bobby enters his server address; sync then fails gracefully.
    val ctx = LocalContext.current
    var serverUrl by remember { mutableStateOf(com.robert.noteforge.sync.SyncPrefs.loadUrl(ctx)) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Instruments") },
                actions = {
                    TextButton(onClick = { viewModel.uploadInstruments() }) {
                        Text("Upload")
                    }
                    TextButton(onClick = { viewModel.downloadInstruments() }) {
                        Text("Download")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    val seed = Random.nextLong()
                    val tag = (seed and Long.MAX_VALUE) % 100000
                    viewModel.createSynthInstrument(
                        "Synth $tag",
                        Patch.random(seed).toJson().toString()
                    )
                }
            ) {
                Icon(Icons.Filled.Add, contentDescription = "New synth instrument")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = serverUrl,
                onValueChange = {
                    serverUrl = it
                    com.robert.noteforge.sync.SyncPrefs.saveUrl(ctx, it)
                },
                label = { Text("Sync server") },
                placeholder = { Text("http://<address>:8127") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )
            if (syncMsg != null) {
                Text(
                    syncMsg!!,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }
            if (state.instruments.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No instruments yet.\nTap + to create a synth instrument.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(state.instruments, key = { it.id }) { instrument ->
                        val audioFile = state.audioFiles.find { it.id == instrument.audioFileId }
                        InstrumentRow(
                            instrument = instrument,
                            audioFile = audioFile,
                            onClick = { editingInstrument = instrument },
                            onDelete = { viewModel.deleteInstrument(instrument) },
                            onPreview = if (instrument.synthPatchJson != null) {
                                {
                                    viewModel.previewSynth(
                                        instrument,
                                        instrument.rootNoteMidi
                                    )
                                }
                            } else null
                        )
                    }
                }
            }
        }
    }

    editingInstrument?.let { instrument ->
        if (instrument.synthPatchJson != null) {
            val previewPlaying by viewModel.isPreviewPlaying.collectAsState()
            EditSynthDialog(
                instrument = instrument,
                onDismiss = {
                    viewModel.stopPreview()
                    editingInstrument = null
                },
                onSave = {
                    viewModel.updateInstrument(it)
                    editingInstrument = null
                },
                onPreviewPatch = { patch -> viewModel.previewSynthPatch(patch, 60) },
                isPreviewPlaying = previewPlaying,
                onStopPreview = { viewModel.stopPreview() }
            )
        } else {
            val audioFile = state.audioFiles.find { it.id == instrument.audioFileId }
            EditInstrumentDialog(
                instrument = instrument,
                audioFile = audioFile,
                onDismiss = { editingInstrument = null },
                onSave = {
                    viewModel.updateInstrument(it)
                    editingInstrument = null
                },
                onPreview = null
            )
        }
    }
}

/** Subtitle for synth instruments: "Synth • <patch DNA>". Null for sample instruments. */
private fun synthSubtitle(instrument: Instrument): String? {
    val json = instrument.synthPatchJson ?: return null
    val dna = try {
        patchFromJson(JSONObject(json)).describe()
    } catch (_: Exception) { "patch" }
    return "Synth • $dna"
}

@Composable
private fun InstrumentRow(
    instrument: Instrument,
    audioFile: AudioFile?,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onPreview: (() -> Unit)?
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onClick
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Piano, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(instrument.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    synthSubtitle(instrument)
                        ?: "${audioFile?.displayName ?: "missing sample"} • root ${Midi.name(instrument.rootNoteMidi)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onPreview != null) {
                IconButton(onClick = onPreview) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "Preview", tint = MaterialTheme.colorScheme.primary)
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}


@Composable
private fun EditInstrumentDialog(
    instrument: Instrument,
    audioFile: AudioFile?,
    onDismiss: () -> Unit,
    onSave: (Instrument) -> Unit,
    onPreview: ((Int) -> Unit)?
) {
    var name by remember { mutableStateOf(instrument.name) }
    var rootNote by remember { mutableStateOf(instrument.rootNoteMidi.toFloat()) }
    var gain by remember { mutableStateOf(instrument.gain) }
    var loop by remember { mutableStateOf(instrument.loopEnabled) }
    var startMs by remember { mutableStateOf(instrument.sampleStartMs.toFloat()) }
    val maxMs = (audioFile?.durationMs ?: 1000L).toFloat().coerceAtLeast(1f)
    var loopStartMs by remember {
        mutableStateOf(instrument.loopStartMs.toFloat().coerceIn(0f, maxMs))
    }
    var loopEndMs by remember {
        mutableStateOf(
            if (instrument.loopEndMs < 0) maxMs
            else instrument.loopEndMs.toFloat().coerceIn(0f, maxMs)
        )
    }
    var endMs by remember {
        mutableStateOf(if (instrument.sampleEndMs < 0) maxMs else instrument.sampleEndMs.toFloat())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit instrument") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name") }, modifier = Modifier.fillMaxWidth()
                )

                Text("Root note: ${Midi.name(rootNote.toInt())}", modifier = Modifier.padding(top = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = rootNote, onValueChange = { rootNote = it },
                        valueRange = 24f..96f, steps = 71,
                        modifier = Modifier.weight(1f)
                    )
                    if (onPreview != null) {
                        IconButton(onClick = { onPreview(rootNote.toInt()) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Preview root note")
                        }
                    }
                }

                Text("Volume: ${(gain * 100).toInt()}%", modifier = Modifier.padding(top = 8.dp))
                Slider(value = gain, onValueChange = { gain = it }, valueRange = 0f..2f)

                Text("Sample trim start: ${startMs.toInt()} ms", modifier = Modifier.padding(top = 8.dp))
                Slider(value = startMs, onValueChange = { if (it < endMs) startMs = it }, valueRange = 0f..maxMs)

                Text("Sample trim end: ${endMs.toInt()} ms", modifier = Modifier.padding(top = 8.dp))
                Slider(value = endMs, onValueChange = { if (it > startMs) endMs = it }, valueRange = 0f..maxMs)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Loop sample")
                        Text(
                            "Repeat the sample to fill long notes",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = loop, onCheckedChange = { loop = it })
                }

                Text("Loop start: ${loopStartMs.toInt()} ms", modifier = Modifier.padding(top = 8.dp))
                Slider(
                    value = loopStartMs,
                    onValueChange = { if (it <= loopEndMs) loopStartMs = it },
                    valueRange = 0f..maxMs,
                    enabled = loop
                )

                Text("Loop end: ${loopEndMs.toInt()} ms", modifier = Modifier.padding(top = 8.dp))
                Slider(
                    value = loopEndMs,
                    onValueChange = { if (it >= loopStartMs) loopEndMs = it },
                    valueRange = 0f..maxMs,
                    enabled = loop
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val lsMs = loopStartMs.toLong().coerceIn(startMs.toLong(), endMs.toLong())
                onSave(
                    instrument.copy(
                        name = name,
                        rootNoteMidi = rootNote.toInt(),
                        gain = gain,
                        loopEnabled = loop,
                        loopStartMs = lsMs,
                        loopEndMs = if (loopEndMs >= maxMs) -1
                            else loopEndMs.toLong().coerceIn(lsMs, endMs.toLong()),
                        sampleStartMs = startMs.toLong(),
                        sampleEndMs = if (endMs >= maxMs) -1 else endMs.toLong()
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** Editor for synth instruments: name, volume, dice re-roll and key patch sliders. */
@Composable
private fun EditSynthDialog(
    instrument: Instrument,
    onDismiss: () -> Unit,
    onSave: (Instrument) -> Unit,
    onPreviewPatch: (Patch) -> Unit,
    isPreviewPlaying: Boolean = false,
    onStopPreview: () -> Unit = {}
) {
    var name by remember { mutableStateOf(instrument.name) }
    var gain by remember { mutableStateOf(instrument.gain) }
    var patch by remember {
        mutableStateOf(
            try {
                patchFromJson(JSONObject(instrument.synthPatchJson!!))
            } catch (_: Exception) {
                Patch.random(Random.nextLong())
            }
        )
    }
    val pct: (Float) -> String = { "${(it * 100).toInt()}%" }
    fun biasLabel(v: Float, lo: String, hi: String) =
        if (v < 0.35f) lo else if (v > 0.65f) hi else "Neutral"
    val ctx = LocalContext.current
    var bias by remember {
        mutableStateOf(com.robert.noteforge.synth.BiasPrefs.load(ctx))
    }
    fun updateBias(b: com.robert.noteforge.synth.RandomBias) {
        bias = b
        com.robert.noteforge.synth.BiasPrefs.save(ctx, b)
    }
    val scope = rememberCoroutineScope()
    var analyzing by remember { mutableStateOf(false) }
    val mimicLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        analyzing = true
        scope.launch(Dispatchers.Default) {
            try {
                ctx.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    val wav = com.robert.noteforge.synth.decodeWav(bytes)
                    val analysis = com.robert.noteforge.synth.analyzeWav(wav)
                    patch = com.robert.noteforge.synth.mimicPatch(
                        analysis, kotlin.random.Random.nextLong())
                }
            } catch (_: Exception) {
            } finally {
                analyzing = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Title slot never scrolls: re-roll + preview stay pinned at the
        // top while the params scroll underneath.
        title = {
            Column {
                Text("Edit synth")
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    TextButton(onClick = {
                        val rolled = Patch.random(Random.nextLong(), bias)
                        patch = rolled
                        onPreviewPatch(rolled)
                    }) {
                        Text("🎲 Re-roll")
                    }
                    TextButton(
                        onClick = { mimicLauncher.launch(arrayOf("audio/*")) },
                        enabled = !analyzing
                    ) {
                        Text(if (analyzing) "\u2026" else "\ud83c\udfaf Mimic")
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        if (isPreviewPlaying) onStopPreview() else onPreviewPatch(patch)
                    }) {
                        Icon(
                            if (isPreviewPlaying) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = if (isPreviewPlaying) "Stop preview" else "Preview patch"
                        )
                    }
                }
            }
        },
        text = {
            // Every randomizable param, grouped by section.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                    SynthSection("Dice bias")
                    SynthSlider("Bright — Dark", bias.bright,
                        { updateBias(bias.copy(bright = it)) },
                        { biasLabel(it, "Dark", "Bright") })
                    SynthSlider("Organic — Synthetic", bias.organic,
                        { updateBias(bias.copy(organic = it)) },
                        { biasLabel(it, "Synthetic", "Organic") })
                    SynthSlider("Calm — Aggressive", bias.aggro,
                        { updateBias(bias.copy(aggro = it)) },
                        { biasLabel(it, "Calm", "Aggressive") })
                    SynthSlider("Dry — Spacious", bias.space,
                        { updateBias(bias.copy(space = it)) },
                        { biasLabel(it, "Dry", "Spacious") })
                    SynthSlider("Static — Evolving", bias.motion,
                        { updateBias(bias.copy(motion = it)) },
                        { biasLabel(it, "Static", "Evolving") })
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Name") }, modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        patch.describe(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )

                    SynthSection("Note")
                    SynthSlider("Pitch env", (patch.pitchEnvSemi + 12f) / 24f,
                        { patch = patch.copy(pitchEnvSemi = it * 24f - 12f) },
                        { val s = (it * 24f - 12f).toInt()
                          "${if (s >= 0) "+" else ""}${s} st" })
                    SynthSlider("Pitch env time",
                        Patch.unmapLog(patch.pitchEnvTime, 0.01f, 1.5f),
                        { patch = patch.copy(pitchEnvTime = 0.01f * 150f.pow(it)) },
                        { Patch.fmtTime(patch.pitchEnvTime) })
                    SynthSlider("Tempo", (patch.tempo - 60f) / 120f,
                        { patch = patch.copy(tempo = 60f + it * 120f) },
                        { "${(60f + it * 120f).toInt()} bpm" })
                    SynthSlider("Root", patch.rootSemi / 12f,
                        { patch = patch.withRootSemi((it * 12).roundToInt()) },
                        { Patch.midiToName(33 + patch.rootSemi) })

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SynthSection("Oscillators", modifier = Modifier.weight(1f))
                        TextButton(onClick = { patch = patch.rerollOscs() }) {
                            Text("🎲 Oscs")
                        }
                    }
                    SynthSlider("Osc count", (patch.activeOscs - 1) / 3f,
                        { patch = patch.copy(activeOscs = (it * 3).roundToInt() + 1) },
                        { "${(it * 3).roundToInt() + 1} osc" })
                    patch.oscs.forEachIndexed { i, osc ->
                        OscEditor(
                            index = i,
                            osc = osc,
                            active = i < patch.activeOscs,
                            onChange = { newOsc ->
                                patch = patch.copy(oscs = patch.oscs.toMutableList()
                                    .also { it[i] = newOsc })
                            }
                        )
                    }
                    SynthSlider("FM amount", patch.fmAmount,
                        { patch = patch.copy(fmAmount = it) }, pct)
                    SynthSlider("FM ratio",
                        Patch.fmRatioIndex(patch.fmRatio) / 8f,
                        { patch = patch.copy(fmRatio = Patch.FM_RATIOS[(it * 8).roundToInt()]) },
                        { "×${Patch.trimNum(Patch.FM_RATIOS[(it * 8).roundToInt()])}" })
                    SynthSlider("Spread", patch.spread,
                        { patch = patch.copy(spread = it) }, pct)
                    SynthSlider("Sub level", patch.subLevel,
                        { patch = patch.copy(subLevel = it) }, pct)
                    SynthSlider("Noise level", patch.noiseLevel,
                        { patch = patch.copy(noiseLevel = it) }, pct)

                    SynthSection("Filter")
                    SynthSlider("Cutoff", patch.cutoff, { patch = patch.copy(cutoff = it) },
                        { Patch.fmtHz(Patch.mapCutoff(it)) })
                    SynthSlider("Resonance", patch.reso, { patch = patch.copy(reso = it) },
                        { "Q${Patch.trimNum(Patch.mapReso(it))}" })
                    SynthSlider("Env amount", patch.filterEnv,
                        { patch = patch.copy(filterEnv = it) }, pct)
                    SynthSlider("Vel \u2192 cutoff", patch.velToCutoff,
                        { patch = patch.copy(velToCutoff = it) }, pct)
                    SynthSlider("Key track", patch.keyTrack,
                        { patch = patch.copy(keyTrack = it) }, pct)

                    SynthSection("LFO")
                    SynthSlider("LFO rate", Patch.unmapLog(patch.lfoRate, 0.05f, 7f),
                        { patch = patch.copy(lfoRate = Patch.mapLfoRate(it)) },
                        { "${Patch.trimNum(Patch.mapLfoRate(it))} Hz" })
                    SynthSlider("LFO depth", patch.lfoDepth,
                        { patch = patch.copy(lfoDepth = it) }, pct)
                    SynthSlider("Vibrato", patch.vibrato,
                        { patch = patch.copy(vibrato = it) },
                        { "${(it * 50).toInt()}¢" })

                    SynthSection("Organic")
                    SynthSlider("Vib delay", patch.vibDelay,
                        { patch = patch.copy(vibDelay = it) },
                        { Patch.fmtTime(Patch.mapVibDelay(it)) })
                    SynthSlider("Vib rate", patch.vibRate,
                        { patch = patch.copy(vibRate = it) },
                        { "${Patch.trimNum(Patch.mapVibRate(it))} Hz" })
                    SynthSlider("Breath", patch.breathAmt,
                        { patch = patch.copy(breathAmt = it) }, pct)
                    SynthSlider("Pitch drift", patch.driftAmt,
                        { patch = patch.copy(driftAmt = it) },
                        { "${Patch.trimNum(Patch.mapDriftCents(it))}¢" })

                    SynthSection("Character")
                    SynthSlider("Vowel", patch.vowel,
                        { patch = patch.copy(vowel = it) },
                        { Patch.vowelName(it) })
                    SynthSlider("Vowel mix", patch.vowelMix,
                        { patch = patch.copy(vowelMix = it) }, pct)
                    SynthSlider("Tremolo rate", patch.tremRate,
                        { patch = patch.copy(tremRate = it) },
                        { "${Patch.trimNum(Patch.mapTremRate(it))} Hz" })
                    SynthSlider("Tremolo depth", patch.tremDepth,
                        { patch = patch.copy(tremDepth = it) }, pct)
                    SynthSlider("Growl", patch.growl,
                        { patch = patch.copy(growl = it) }, pct)

                    SynthSection("Orchestral")
                    SynthSlider("Slide", patch.slideSemi / 12f,
                        { patch = patch.copy(slideSemi = it * 12f) },
                        { "${Patch.trimNum(it * 12f)} st" })
                    SynthSlider("Slide time", patch.slideTime,
                        { patch = patch.copy(slideTime = it) },
                        { Patch.fmtTime(Patch.mapSlideTime(it)) })
                    SynthSlider("Key click", patch.clickAmt,
                        { patch = patch.copy(clickAmt = it) }, pct)
                    SynthSlider("Flutter", patch.flutterAmt,
                        { patch = patch.copy(flutterAmt = it) }, pct)
                    SynthSlider("Air", patch.airAmt,
                        { patch = patch.copy(airAmt = it) }, pct)

                    SynthSection("Envelope")
                    SynthSlider("Attack", Patch.unmapLog(patch.attack, 0.001f, 1.5f),
                        { patch = patch.copy(attack = Patch.mapAttack(it)) },
                        { Patch.fmtTime(Patch.mapAttack(it)) })
                    SynthSlider("Decay", Patch.unmapLog(patch.decay, 0.01f, 1f),
                        { patch = patch.copy(decay = Patch.mapDecay(it)) },
                        { Patch.fmtTime(Patch.mapDecay(it)) })
                    SynthSlider("Sustain", patch.sustain,
                        { patch = patch.copy(sustain = it) }, pct)
                    SynthSlider("Release", Patch.unmapLog(patch.release, 0.02f, 1.5f),
                        { patch = patch.copy(release = Patch.mapRelease(it)) },
                        { Patch.fmtTime(Patch.mapRelease(it)) })

                    SynthSection("Delay")
                    SynthSlider("Time", patch.delayTime,
                        { patch = patch.copy(delayTime = it) },
                        { Patch.fmtMs(Patch.mapDelayMs(it)) })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sync to tempo", modifier = Modifier.weight(1f))
                        Switch(
                            checked = patch.delaySync,
                            onCheckedChange = { patch = patch.copy(delaySync = it) }
                        )
                    }
                    SynthSlider("Feedback", patch.delayFb,
                        { patch = patch.copy(delayFb = it) }, pct)
                    SynthSlider("Mix", patch.delayMix,
                        { patch = patch.copy(delayMix = it) }, pct)

                    SynthSection("Reverb")
                    SynthSlider("Size", patch.revSize,
                        { patch = patch.copy(revSize = it) }, pct)
                    SynthSlider("Damp", patch.revDamp,
                        { patch = patch.copy(revDamp = it) }, pct)
                    SynthSlider("Mix", patch.revMix,
                        { patch = patch.copy(revMix = it) }, pct)

                    SynthSection("Output")
                    Text("Volume: ${(gain * 100).toInt()}%", modifier = Modifier.padding(top = 8.dp))
                    Slider(value = gain, onValueChange = { gain = it }, valueRange = 0f..2f)
                    SynthSlider("Drive", patch.drive,
                        { patch = patch.copy(drive = it) }, pct)
                    SynthSlider("Width", patch.width,
                        { patch = patch.copy(width = it) }, pct)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    instrument.copy(
                        name = name,
                        gain = gain,
                        synthPatchJson = patch.toJson().toString()
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun OscEditor(
    index: Int,
    osc: com.robert.noteforge.synth.OscLayer,
    active: Boolean,
    onChange: (com.robert.noteforge.synth.OscLayer) -> Unit
) {
    Column(modifier = Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Osc ${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = if (active) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                val waves = com.robert.noteforge.synth.Wave.entries
                onChange(osc.copy(wave = waves[(osc.wave.ordinal + 1) % waves.size]))
            }) {
                Text(osc.wave.name.take(3))
            }
            TextButton(
                onClick = { onChange(osc.copy(octave = (osc.octave - 1).coerceIn(-1, 1))) }
            ) { Text("−") }
            Text(
                if (osc.octave >= 0) "+${osc.octave}" else "${osc.octave}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
            TextButton(
                onClick = { onChange(osc.copy(octave = (osc.octave + 1).coerceIn(-1, 1))) }
            ) { Text("+") }
        }
        SynthSlider("Level", osc.level,
            { onChange(osc.copy(level = it)) },
            { "${(it * 100).toInt()}%" })
        SynthSlider("Pan", (osc.pan + 1f) / 2f,
            { onChange(osc.copy(pan = it * 2f - 1f)) },
            {
                val p = it * 2f - 1f
                when {
                    p < -0.05f -> "L${(-p * 100).toInt()}"
                    p > 0.05f -> "R${(p * 100).toInt()}"
                    else -> "Center"
                }
            })
        SynthSlider("Detune", (osc.detuneCents + 30f) / 60f,
            { onChange(osc.copy(detuneCents = it * 60f - 30f)) },
            { "${(it * 60f - 30f).toInt()}¢" })
    }
}

@Composable
private fun SynthSection(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 12.dp)
    )
}

@Composable
private fun SynthSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    format: (Float) -> String
) {
    Text("$label: ${format(value)}", modifier = Modifier.padding(top = 8.dp))
    Slider(value = value, onValueChange = onChange)
}
