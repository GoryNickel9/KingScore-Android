package app.kingscore.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KingScoreColors = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF052E23),
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = Color(0xFFD1FAE5),
    background = Color(0xFF0F172A),
    onBackground = Color(0xFFF8FAFC),
    surface = Color(0xFF1E293B),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = Color(0xFF273549),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF94A3B8),
    error = Color(0xFFFCA5A5),
)

@Composable
fun KingScoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = KingScoreColors, content = content)
}
