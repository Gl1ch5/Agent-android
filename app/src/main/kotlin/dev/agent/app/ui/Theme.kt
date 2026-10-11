package dev.agent.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The whole palette: three colors. Everything else is a tint of these. */
val Ink = Color(0xFF0E0E11)     // background
val Paper = Color(0xFFECECF1)   // text, icons, outlines
val Accent = Color(0xFF6E8BFF)  // primary actions and the active state

private val FlatColors = darkColorScheme(
    primary = Accent,
    onPrimary = Ink,
    secondary = Paper,
    onSecondary = Ink,
    background = Ink,
    onBackground = Paper,
    surface = Ink,
    onSurface = Paper,
    surfaceVariant = Paper.copy(alpha = 0.07f),
    onSurfaceVariant = Paper.copy(alpha = 0.7f),
    outline = Paper.copy(alpha = 0.25f),
    error = Accent,
    onError = Ink,
)

/** Flat dark theme. Light mode is intentionally not offered yet. */
@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FlatColors, content = content)
}
