package com.robert.noteforge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.robert.noteforge.ui.filetab.FileScreen
import com.robert.noteforge.ui.instrumentstab.InstrumentsScreen
import com.robert.noteforge.ui.drumstab.DrumsScreen
import com.robert.noteforge.ui.score.ScoreScreen
import com.robert.noteforge.ui.theme.NoteForgeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NoteForgeTheme {
                NoteForgeRoot()
            }
        }
    }

    companion object {
        const val ACTION_IMPORT_SAMPLE = "com.robert.noteforge.action.IMPORT_SAMPLE"
        const val EXTRA_NAME = "com.robert.noteforge.extra.NAME"
        const val EXTRA_ROOT_MIDI = "com.robert.noteforge.extra.ROOT_MIDI"
        const val EXTRA_PATCH_JSON = "com.robert.noteforge.extra.PATCH_JSON"
    }
}

private enum class RootTab(val label: String) {
    SCORE("Score"), FILE("File"), INSTRUMENTS("Instruments"), DRUMS("Drums")
}

@Composable
private fun NoteForgeRoot() {
    var selectedTab by remember { mutableStateOf(RootTab.SCORE) }

    Scaffold(
        bottomBar = {
            // Sliding tab strip: scrolls horizontally instead of squeezing,
            // padded above the system navigation bar.
            ScrollableTabRow(
                selectedTabIndex = RootTab.entries.indexOf(selectedTab),
                edgePadding = 8.dp,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            ) {
                Tab(
                    selected = selectedTab == RootTab.SCORE,
                    onClick = { selectedTab = RootTab.SCORE },
                    icon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                    text = { Text(RootTab.SCORE.label) }
                )
                Tab(
                    selected = selectedTab == RootTab.FILE,
                    onClick = { selectedTab = RootTab.FILE },
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    text = { Text(RootTab.FILE.label) }
                )
                Tab(
                    selected = selectedTab == RootTab.INSTRUMENTS,
                    onClick = { selectedTab = RootTab.INSTRUMENTS },
                    icon = { Icon(Icons.Filled.Piano, contentDescription = null) },
                    text = { Text(RootTab.INSTRUMENTS.label) }
                )
                Tab(
                    selected = selectedTab == RootTab.DRUMS,
                    onClick = { selectedTab = RootTab.DRUMS },
                    icon = { Icon(Icons.Filled.Album, contentDescription = null) },
                    text = { Text(RootTab.DRUMS.label) }
                )
            }
        }
    ) { padding ->
        when (selectedTab) {
            RootTab.SCORE -> ScoreScreen(modifier = Modifier.padding(padding))
            RootTab.FILE -> FileScreen(modifier = Modifier.padding(padding))
            RootTab.INSTRUMENTS -> InstrumentsScreen(modifier = Modifier.padding(padding))
            RootTab.DRUMS -> DrumsScreen(modifier = Modifier.padding(padding))
        }
    }
}
