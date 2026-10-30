package dev.tubescribe.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.tubescribe.R

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF0000),
    onPrimary = Color.White,
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

/** The wordmark face used by the top bar and the About header. */
val LogoFontFamily = FontFamily(
    Font(R.font.space_grotesk_bold, weight = FontWeight.Bold)
)

/**
 * The app mark as it appears inside the UI: just the white play-and-captions
 * artwork, cropped from the launcher foreground and drawn larger, with no red
 * tile behind it. The launcher icon keeps the red background.
 */
@Composable
fun BrandMark(height: Dp = 22.dp) {
    Image(
        painter = painterResource(R.drawable.ic_brand_mark),
        contentDescription = null,
        modifier = Modifier.size(width = height * (40f / 48f), height = height)
    )
}

/** TubeScribe is a video app: always dark, matching the desktop edition. */
@Composable
fun TubeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        content = content
    )
}
