package com.robert.noteforge.ui.drumstab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert.noteforge.data.Instrument
import com.robert.noteforge.data.InstrumentRepository
import com.robert.noteforge.synth.DrumPatch
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class DrumsViewModel @Inject constructor(
    private val instrumentRepository: InstrumentRepository
) : ViewModel() {

    /** Creates an Instrument from a drum patch so it can be assigned to tracks. */
    fun addDrumAsInstrument(name: String, patch: DrumPatch, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val instrument = Instrument(
                name = name,
                audioFileId = -1, // no sample; synth-only
                synthPatchJson = patch.toJson(),
                syncUid = UUID.randomUUID().toString(),
                updatedAt = System.currentTimeMillis()
            )
            instrumentRepository.insert(instrument)
            onDone()
        }
    }
}
