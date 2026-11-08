package com.robert.noteforge.ui.drumstab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.robert.noteforge.synth.DrumPatch
import com.robert.noteforge.synth.DrumSynth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Stored drum sound. */
data class DrumSound(
    val id: String,
    val name: String,
    val patch: DrumPatch
)

@Composable
fun DrumsScreen(modifier: Modifier = Modifier) {
    val drumsViewModel: DrumsViewModel = hiltViewModel()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var drums by remember { mutableStateOf(loadDrums(context)) }
    var editingDrum by remember { mutableStateOf<DrumSound?>(null) }
    var playingId by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    var kit by remember { mutableStateOf(loadKit(context)) }

    fun save() = saveDrums(context, drums)

    fun preview(drum: DrumSound) {
        if (playingId == drum.id) {
            playingId = null
            return
        }
        playingId = drum.id
        scope.launch(Dispatchers.Default) {
            try {
                val pcm = DrumSynth.renderToShort(drum.patch, 22050)
                withContext(Dispatchers.Main) {
                    playPcm(context, pcm, 22050) {
                        playingId = null
                    }
                }
            } catch (_: Exception) {
                playingId = null
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Drums") }) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(
                    onClick = {
                        val patch = DrumPatch.random(System.currentTimeMillis())
                        val drum = DrumSound(
                            id = System.currentTimeMillis().toString(),
                            name = "${patch.type.name.lowercase().replace('_', ' ')} ${drums.size + 1}",
                            patch = patch
                        )
                        editingDrum = drum
                    }
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "New drum")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Sounds") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Kit") }
                )
            }

            if (selectedTab == 0) {
                SoundsTab(
                    drums = drums,
                    playingId = playingId,
                    onPreview = { preview(it) },
                    onDelete = {
                        drums = drums.filter { d -> d.id != it.id }
                        save()
                    },
                    onEdit = { editingDrum = it },
                    onAddToInstruments = { drum ->
                        drumsViewModel.addDrumAsInstrument(drum.name, drum.patch) {
                            scope.launch {
                                snackbar.showSnackbar("\"${drum.name}\" added to instruments")
                            }
                        }
                    },
                    onPresetSelected = { preset ->
                        val drum = DrumSound(
                            id = System.currentTimeMillis().toString(),
                            name = preset.name,
                            patch = preset.patch
                        )
                        drums = drums + drum
                        save()
                    },
                    onPresetPreview = { patch ->
                        scope.launch(Dispatchers.Default) {
                            val pcm = DrumSynth.renderToShort(patch, 22050)
                            withContext(Dispatchers.Main) {
                                playPcm(context, pcm, 22050) {}
                            }
                        }
                    }
                )
            } else {
                KitTab(
                    kit = kit,
                    drums = drums,
                    onKitChanged = {
                        kit = it
                        saveKit(context, it)
                        scope.launch {
                            snackbar.showSnackbar("Kit updated")
                        }
                    }
                )
            }
        }
    }

    editingDrum?.let { drum ->
        DrumEditorDialog(
            drum = drum,
            onDismiss = { editingDrum = null },
            onSave = { updated ->
                val idx = drums.indexOfFirst { it.id == updated.id }
                drums = if (idx >= 0) {
                    drums.toMutableList().apply { set(idx, updated) }
                } else {
                    drums + updated
                }
                save()
                editingDrum = null
            },
            onPreview = { preview(it) },
            isPlaying = playingId == drum.id
        )
    }
}

@Composable
private fun SoundsTab(
    drums: List<DrumSound>,
    playingId: String?,
    onPreview: (DrumSound) -> Unit,
    onDelete: (DrumSound) -> Unit,
    onEdit: (DrumSound) -> Unit,
    onAddToInstruments: (DrumSound) -> Unit,
    onPresetSelected: (DrumPreset) -> Unit,
    onPresetPreview: (DrumPatch) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Factory Presets",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        item {
            PresetGrid(
                onSelect = onPresetSelected,
                onPreview = onPresetPreview
            )
        }

        item {
            Text(
                "My Drums",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }

        items(drums, key = { it.id }) { drum ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onEdit(drum) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            drum.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            drum.patch.type.name.lowercase().replace('_', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = { onPreview(drum) }) {
                        Icon(
                            if (playingId == drum.id) Icons.Filled.Stop
                            else Icons.Filled.PlayArrow,
                            contentDescription = if (playingId == drum.id) "Stop" else "Preview"
                        )
                    }
                    IconButton(onClick = { onAddToInstruments(drum) }) {
                        Icon(Icons.Filled.MusicNote, contentDescription = "Add to instruments")
                    }
                    IconButton(onClick = { onDelete(drum) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete")
                    }
                }
            }
        }

        if (drums.isEmpty()) {
            item {
                Text(
                    "No drum sounds yet. Tap + to generate one!",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

// --- Persistence (SharedPreferences, JSON) ---

private const val DRUMS_PREFS = "drum_sounds"
private const val DRUMS_KEY = "drums"

private fun loadDrums(context: android.content.Context): List<DrumSound> {
    return try {
        val json = context.getSharedPreferences(DRUMS_PREFS, android.content.Context.MODE_PRIVATE)
            .getString(DRUMS_KEY, "[]") ?: "[]"
        val arr = org.json.JSONArray(json)
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            DrumSound(
                id = o.getString("id"),
                name = o.getString("name"),
                patch = DrumPatch.fromJson(o.getString("patch")) ?: DrumPatch.random(System.currentTimeMillis())
            )
        }
    } catch (_: Exception) { emptyList() }
}

private fun saveDrums(context: android.content.Context, drums: List<DrumSound>) {
    try {
        val arr = org.json.JSONArray()
        for (d in drums) {
            arr.put(org.json.JSONObject().apply {
                put("id", d.id)
                put("name", d.name)
                put("patch", d.patch.toJson())
            })
        }
        context.getSharedPreferences(DRUMS_PREFS, android.content.Context.MODE_PRIVATE)
            .edit()
            .putString(DRUMS_KEY, arr.toString())
            .apply()
    } catch (_: Exception) {}
}

// --- Audio playback ---

private var currentAudioTrack: android.media.AudioTrack? = null

internal fun playPcm(
    context: android.content.Context,
    pcm: ShortArray,
    sampleRate: Int,
    onDone: () -> Unit
) {
    try {
        currentAudioTrack?.stop()
        currentAudioTrack?.release()
    } catch (_: Exception) {}

    val track = android.media.AudioTrack.Builder()
        .setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setAudioFormat(
            android.media.AudioFormat.Builder()
                .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setBufferSizeInBytes(pcm.size * 2)
        .setTransferMode(android.media.AudioTrack.MODE_STATIC)
        .build()

    currentAudioTrack = track
    track.write(pcm, 0, pcm.size)
    track.setNotificationMarkerPosition(pcm.size)
    track.setPlaybackPositionUpdateListener(
        object : android.media.AudioTrack.OnPlaybackPositionUpdateListener {
            override fun onMarkerReached(t: android.media.AudioTrack?) {
                onDone()
            }
            override fun onPeriodicNotification(t: android.media.AudioTrack?) {}
        }
    )
    track.play()
}
