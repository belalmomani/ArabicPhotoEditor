package com.example.arabicphotoeditor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

object EditorColors {
    val Background = Color(0xFF16171A)
    val Surface = Color(0xFF202226)
    val SurfaceRaised = Color(0xFF2A2D32)
    val Workspace = Color(0xFF0F1012)
    val Border = Color(0xFF3A3D44)
    val TextPrimary = Color(0xFFECEEF2)
    val TextSecondary = Color(0xFFA3A8B3)
    val Accent = Color(0xFF5B9BF5)
    val AccentContainer = Color(0xFF22344F)
    val OnAccent = Color(0xFF08121F)
}

private val EditorDarkScheme = darkColorScheme(
    primary = EditorColors.Accent,
    onPrimary = EditorColors.OnAccent,
    background = EditorColors.Background,
    onBackground = EditorColors.TextPrimary,
    surface = EditorColors.Surface,
    onSurface = EditorColors.TextPrimary,
    surfaceVariant = EditorColors.SurfaceRaised,
    onSurfaceVariant = EditorColors.TextSecondary,
    outline = EditorColors.Border,
)

@Composable
fun ArabicPhotoEditorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EditorDarkScheme, content = content)
}
