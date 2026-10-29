package app.simpleinsta.ig.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val LightBackground = Color(0xFFFDFDFC)
private val LightInk = Color(0xFF141413)
private val LightMuted = Color(0xFF6B6B66)
private val LightLine = Color(0xFFE4E3DF)

private val DarkBackground = Color(0xFF121211)
private val DarkInk = Color(0xFFF3F2EE)
private val DarkMuted = Color(0xFF9A9791)
private val DarkLine = Color(0xFF2C2C29)

private val LightColors = lightColorScheme(
    primary = LightInk,
    onPrimary = Color.White,
    background = LightBackground,
    onBackground = LightInk,
    surface = LightBackground,
    onSurface = LightInk,
    surfaceVariant = Color(0xFFF1F0ED),
    onSurfaceVariant = LightMuted,
    outline = LightLine,
    outlineVariant = LightLine
)

private val DarkColors = darkColorScheme(
    primary = DarkInk,
    onPrimary = Color(0xFF121211),
    background = DarkBackground,
    onBackground = DarkInk,
    surface = DarkBackground,
    onSurface = DarkInk,
    surfaceVariant = Color(0xFF232322),
    onSurfaceVariant = DarkMuted,
    outline = DarkLine,
    outlineVariant = DarkLine
)

private val SimpleInstaType = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
)

/** Window colour for a theme, used for the status bar and the pre-draw background. */
fun windowColorFor(dark: Boolean): Int =
    if (dark) 0xFF121211.toInt() else 0xFFFDFDFC.toInt()

@Composable
fun SimpleInstaTheme(dark: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = SimpleInstaType,
        content = content
    )
}
