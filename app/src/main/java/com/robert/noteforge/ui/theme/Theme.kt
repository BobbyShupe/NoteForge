package com.robert.noteforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val NoteForgeColorScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Background,
    secondary = AccentDim,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnBackground,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceMuted,
    outline = StaffLine
)

@Composable
fun NoteForgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NoteForgeColorScheme,
        typography = NoteForgeTypography,
        content = content
    )
}
