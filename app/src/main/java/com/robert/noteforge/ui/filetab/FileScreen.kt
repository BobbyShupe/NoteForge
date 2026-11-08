package com.robert.noteforge.ui.filetab

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.robert.noteforge.audio.MidiParser
import com.robert.noteforge.data.ProjectData
import com.robert.noteforge.data.ProjectFileCodec
import com.robert.noteforge.ui.score.ScoreViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** File types shown in the open/import pickers: our JSON plus MIDI songs. */
private val PROJECT_MIME_TYPES = arrayOf(
    "application/json",
    "text/json",
    "audio/midi",
    "audio/mid",
    "audio/x-midi"
)

/**
 * Project file operations: New / Open / Save / Import / Close.
 *
 * Projects are portable JSON files (tracks + notes + tempo). The instrument
 * library stays in the app database; imported tracks that reference a
 * missing instrument fall back to the first available instrument.
 * Open replaces the current score, Import merges into it.
 */
@Composable
fun FileScreen(
    modifier: Modifier = Modifier,
    viewModel: ScoreViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val projectName by viewModel.projectName.collectAsState()
    val renderProgress by viewModel.renderProgress.collectAsState()
    val renderUri by viewModel.renderUri.collectAsState()
    val renderError by viewModel.renderError.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var busy by remember { mutableStateOf(false) }
    var showNewConfirm by remember { mutableStateOf(false) }
    var pendingOpenUri by remember { mutableStateOf<Uri?>(null) }

    fun queryName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                cursor.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "project"
    }

    /** Loads a project file, sniffing MIDI (.mid) vs JSON by content. */
    suspend fun loadProject(uri: Uri): Pair<ProjectData, Boolean> = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalStateException("Could not read file.")
        if (bytes.size >= 4 &&
            bytes[0] == 'M'.code.toByte() &&
            bytes[1] == 'T'.code.toByte() &&
            bytes[2] == 'h'.code.toByte() &&
            bytes[3] == 'd'.code.toByte()
        ) {
            MidiParser.parse(bytes) to true
        } else {
            ProjectFileCodec.decode(String(bytes, Charsets.UTF_8)) to false
        }
    }

    fun fail(e: Exception) {
        scope.launch { snackbar.showSnackbar(e.message ?: "File operation failed.") }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val json = viewModel.exportProjectJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(json.toByteArray())
                    } ?: throw IllegalStateException("Could not write file.")
                }
                val name = queryName(uri)
                viewModel.setProjectName(name)
                snackbar.showSnackbar("Saved $name.")
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = false
            }
        }
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        // Confirm first: opening replaces the current score.
        if (uri != null) pendingOpenUri = uri
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val (data, _) = loadProject(uri)
                viewModel.mergeProject(data)
                snackbar.showSnackbar(
                    "Imported ${data.tracks.size} track(s), ${data.notes.size} note(s)."
                )
            } catch (e: Exception) {
                fail(e)
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("File") }) },
        snackbarHost = { SnackbarHost(snackbar) { Snackbar(it) } }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            projectName ?: "Untitled project",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "${state.tracks.size} track(s) • ${state.notes.size} note(s) • " +
                                "${state.settings.tempoBpm} BPM",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            // Playback quality selector
            val context = LocalContext.current
            val sampleRate by viewModel.sampleRate.collectAsState()
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        "Playback quality",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val smooth = com.robert.noteforge.audio.QualityPrefs.SR_SMOOTH
                        val high = com.robert.noteforge.audio.QualityPrefs.SR_HIGH
                        SettingChip(
                            selected = sampleRate == smooth,
                            onClick = { viewModel.setQuality(smooth, context) },
                            label = "Smooth",
                            modifier = Modifier.weight(1f)
                        )
                        SettingChip(
                            selected = sampleRate == high,
                            onClick = { viewModel.setQuality(high, context) },
                            label = "High",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        if (sampleRate == com.robert.noteforge.audio.QualityPrefs.SR_HIGH)
                            "44.1 kHz — best fidelity, more processing"
                        else
                            "22.05 kHz — half the DSP cost, always smooth",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }


            // Expandable render settings (resolution, fps, output, style, priority)
            RenderSettingsCard()

            // Render settings state (for button label)
            val renderSettings by remember {
                mutableStateOf(com.robert.noteforge.audio.RenderPrefs.get(context))
            }

            // Render button (uses foreground service, works with screen off)
            Button(
                onClick = { viewModel.startVideoRender(context) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !viewModel.isRendering && state.notes.isNotEmpty()
            ) {
                Icon(Icons.Filled.Videocam, contentDescription = null)
                Text(
                    when (renderSettings.output) {
                        com.robert.noteforge.audio.RenderPrefs.OUTPUT_AUDIO_ONLY -> "  Render Audio — 44.1kHz WAV"
                        com.robert.noteforge.audio.RenderPrefs.OUTPUT_VIDEO_ONLY -> "  Render Video — no audio"
                        else -> "  Render Video — MP4 with 44.1kHz audio"
                    },
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            Text(
                "Renders in background — works with the screen off",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // Render error
            renderError?.let { err ->
                Text(
                    "Render failed: $err",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            // Render progress dialog (non-blocking, updates via StateFlow)
            renderProgress?.let { prog ->
                AlertDialog(
                    onDismissRequest = { /* cannot dismiss while rendering */ },
                    title = { Text("Rendering video") },
                    text = {
                        Column {
                            Text(prog.phase)
                            Spacer(modifier = Modifier.height(8.dp))
                            LinearProgressIndicator(
                                progress = { prog.fraction },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                buildString {
                                    append("%.1f%%".format(java.util.Locale.US, prog.fraction * 100))
                                    prog.etaSec?.let { append(" \u00b7 ETA ${formatEta(it)}") }
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Saved to Movies/NoteForge — you can leave this screen",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { viewModel.cancelRender(context) }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Render complete: dialog with OK button
            renderUri?.let { uri ->
                AlertDialog(
                    onDismissRequest = { viewModel.clearRenderState() },
                    title = { Text("Video ready") },
                    text = { Text("Saved to Movies/NoteForge") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.clearRenderState() }) {
                            Text("OK")
                        }
                    }
                )
            }

            Button(
                onClick = { showNewConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  New — clear the score", modifier = Modifier.padding(start = 4.dp))
            }
            OutlinedButton(
                onClick = { openLauncher.launch(PROJECT_MIME_TYPES) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) {
                Icon(Icons.Filled.FileOpen, contentDescription = null)
                Text("  Open — replace from file", modifier = Modifier.padding(start = 4.dp))
            }
            OutlinedButton(
                onClick = {
                    val suggested = (projectName ?: "noteforge-project")
                        .let { if (it.endsWith(".json")) it else "$it.json" }
                    saveLauncher.launch(suggested)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Text("  Save — write to file", modifier = Modifier.padding(start = 4.dp))
            }
            OutlinedButton(
                onClick = { importLauncher.launch(PROJECT_MIME_TYPES) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) {
                Icon(Icons.Filled.UploadFile, contentDescription = null)
                Text("  Import — merge from file", modifier = Modifier.padding(start = 4.dp))
            }
            OutlinedButton(
                onClick = {
                    viewModel.closeProject()
                    scope.launch { snackbar.showSnackbar("Closed. Playback stopped.") }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy
            ) {
                Icon(Icons.Filled.Close, contentDescription = null)
                Text("  Close — stop and park playhead", modifier = Modifier.padding(start = 4.dp))
            }

            Text(
                "Open replaces the current score. Import appends the file's " +
                    "tracks and notes to it. Both accept NoteForge (.json) " +
                    "and MIDI (.mid) songs. Instruments are matched by ID " +
                    "and fall back to the first instrument when missing.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (showNewConfirm) {
        AlertDialog(
            onDismissRequest = { showNewConfirm = false },
            title = { Text("New project?") },
            text = { Text("Clears all tracks and notes. Instruments and audio files are kept.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showNewConfirm = false
                        scope.launch {
                            busy = true
                            try {
                                viewModel.newProject()
                                snackbar.showSnackbar("New empty project.")
                            } catch (e: Exception) {
                                fail(e)
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showNewConfirm = false }) { Text("Cancel") } }
        )
    }

    pendingOpenUri?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingOpenUri = null },
            title = { Text("Open project?") },
            text = { Text("Replaces the current score with the file's content.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingOpenUri = null
                        scope.launch {
                            busy = true
                            try {
                                val (data, isMidi) = loadProject(uri)
                                val name = queryName(uri)
                                viewModel.setProjectName(name)
                                // MIDI files don't store NoteForge settings; use a
                                // per-song key so track configs persist across loads.
                                val songKey = if (isMidi) "midi:$name" else null
                                viewModel.replaceProject(data, songKey)
                                snackbar.showSnackbar(
                                    "Opened $name: ${data.tracks.size} track(s), " +
                                        "${data.notes.size} note(s)."
                                )
                            } catch (e: Exception) {
                                fail(e)
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { pendingOpenUri = null }) { Text("Cancel") } }
        )
    }
}

private fun formatEta(sec: Long): String {
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(java.util.Locale.US, m, s)
}
