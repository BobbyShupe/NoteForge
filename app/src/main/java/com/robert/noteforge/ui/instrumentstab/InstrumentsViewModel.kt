package com.robert.noteforge.ui.instrumentstab

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert.noteforge.audio.AudioEngine
import com.robert.noteforge.data.AudioFile
import com.robert.noteforge.data.AudioFileRepository
import com.robert.noteforge.data.Instrument
import com.robert.noteforge.data.InstrumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import javax.inject.Inject

data class InstrumentsUiState(
    val instruments: List<Instrument> = emptyList(),
    val audioFiles: List<AudioFile> = emptyList()
)

@HiltViewModel
class InstrumentsViewModel @Inject constructor(
    private val instrumentRepository: InstrumentRepository,
    private val audioFileRepository: AudioFileRepository,
    private val audioEngine: AudioEngine,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    val uiState: StateFlow<InstrumentsUiState> = combine(
        instrumentRepository.observeAll(),
        audioFileRepository.observeAll()
    ) { instruments, audioFiles ->
        InstrumentsUiState(instruments, audioFiles)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), InstrumentsUiState())

    fun createSynthInstrument(name: String, patchJson: String) {
        viewModelScope.launch {
            instrumentRepository.insert(
                Instrument(
                    name = name,
                    audioFileId = 0, // synth instruments hold no audio file
                    rootNoteMidi = 60,
                    synthPatchJson = patchJson,
                    syncUid = java.util.UUID.randomUUID().toString(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateInstrument(instrument: Instrument) {
        viewModelScope.launch {
            instrumentRepository.update(
                instrument.copy(updatedAt = System.currentTimeMillis())
            )
        }
    }

    fun deleteInstrument(instrument: Instrument) {
        viewModelScope.launch { instrumentRepository.delete(instrument) }
    }

    val syncMessage = MutableStateFlow<String?>(null)

    /** Uploads all local instruments to the sync server. */
    fun uploadInstruments() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val all = instrumentRepository.observeAll()
                // Grab a snapshot via first()
                val list = all.first()
                val baseUrl = com.robert.noteforge.sync.SyncPrefs.loadUrl(appContext)
                val n = com.robert.noteforge.sync.InstrumentSync.upload(list, baseUrl)
                syncMessage.value = "Uploaded $n instrument(s) to server"
            } catch (e: Exception) {
                syncMessage.value = "Upload failed: ${e.message}"
            }
        }
    }

    /** Downloads instruments from the sync server, merging by syncUid. */
    fun downloadInstruments() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val local = uiState.value.instruments
                val baseUrl = com.robert.noteforge.sync.SyncPrefs.loadUrl(appContext)
                val n = com.robert.noteforge.sync.InstrumentSync.download(instrumentRepository, local, baseUrl)
                syncMessage.value = if (n == 0) "Already up to date" else "Synced $n instrument(s) from server"
            } catch (e: Exception) {
                syncMessage.value = "Download failed: ${e.message}"
            }
        }
    }

    fun clearSyncMessage() { syncMessage.value = null }

    /** Preview for synth instruments, from the stored patch JSON. */
    fun previewSynth(instrument: Instrument, midi: Int) {
        val patch = instrument.synthPatchJson?.let {
            try {
                com.robert.noteforge.synth.patchFromJson(org.json.JSONObject(it))
            } catch (_: Exception) { null }
        } ?: return
        previewSynthPatch(patch, midi)
    }

    /** Preview for a live (possibly edited, unsaved) synth patch. */
    fun previewSynthPatch(patch: com.robert.noteforge.synth.Patch, midi: Int) {
        audioEngine.playSynthPreview(patch, midi)
    }

    val isPreviewPlaying: kotlinx.coroutines.flow.StateFlow<Boolean> =
        audioEngine.isPreviewPlaying

    fun stopPreview() {
        audioEngine.stopPreview()
    }
}
