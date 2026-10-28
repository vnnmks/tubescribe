package dev.tubescribe.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB4C0),
    onPrimary = Color(0xFF8E1436),
    secondary = Color(0xFFC0C6DD),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE4E1E6),
    surface = Color(0xFF161B22),
    onSurface = Color(0xFFE4E1E6),
    surfaceVariant = Color(0xFF22282F),
    onSurfaceVariant = Color(0xFFC4C6CF),
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF8D9099)
)

/** TubeScribe is a video app: always dark, matching the desktop edition. */
@Composable
fun TubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
