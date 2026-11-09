package io.github.edrock200.appdownloader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Silora's split-prism violet stays consistent across Android versions and wallpapers.
private val Light = lightColorScheme(
    primary = Color(0xFF6540BA), onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE3FF), onPrimaryContainer = Color(0xFF321766),
    secondary = Color(0xFF665B79), onSecondary = Color.White,
    secondaryContainer = Color(0xFFECE5F5), onSecondaryContainer = Color(0xFF292134),
    tertiary = Color(0xFF216B55), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDAF2E7), onTertiaryContainer = Color(0xFF124B39),
    background = Color(0xFFF8F6FC), onBackground = Color(0xFF211B2B),
    surface = Color(0xFFFFFBFF), onSurface = Color(0xFF211B2B),
    surfaceVariant = Color(0xFFEAE4F1), onSurfaceVariant = Color(0xFF655D70),
    surfaceTint = Color(0xFF6540BA),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F0F9),
    surfaceContainer = Color(0xFFF0EBF5), surfaceContainerHigh = Color(0xFFEAE4F1),
    surfaceContainerHighest = Color(0xFFE4DDEB),
    outline = Color(0xFF80748F), outlineVariant = Color(0xFFDCD3E7),
    error = Color(0xFFAE303C), onError = Color.White,
    errorContainer = Color(0xFFFFDADD), onErrorContainer = Color(0xFF681421),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFC9B0FF), onPrimary = Color(0xFF30175D),
    primaryContainer = Color(0xFF302244), onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFCDC0DF), onSecondary = Color(0xFF332A40),
    secondaryContainer = Color(0xFF302A3C), onSecondaryContainer = Color(0xFFE9DFF5),
    tertiary = Color(0xFF91D5B6), onTertiary = Color(0xFF123C2E),
    tertiaryContainer = Color(0xFF183B30), onTertiaryContainer = Color(0xFFB6EBD1),
    background = Color(0xFF0C0B10), onBackground = Color(0xFFF3EEF9),
    surface = Color(0xFF15121C), onSurface = Color(0xFFF3EEF9),
    surfaceVariant = Color(0xFF292332), onSurfaceVariant = Color(0xFFBCB2CC),
    surfaceTint = Color(0xFFC9B0FF),
    surfaceContainerLowest = Color(0xFF0C0B10), surfaceContainerLow = Color(0xFF1A1622),
    surfaceContainer = Color(0xFF211C2B), surfaceContainerHigh = Color(0xFF292332),
    surfaceContainerHighest = Color(0xFF332C3E),
    outline = Color(0xFF8C7E9E), outlineVariant = Color(0xFF3B3248),
    error = Color(0xFFFFB0B8), onError = Color(0xFF650F24),
    errorContainer = Color(0xFF4A1C2B), onErrorContainer = Color(0xFFFFDADD),
)

private val SiloraTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 1.4.sp),
)

@Composable
fun AppTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = SiloraTypography,
        shapes = Shapes(
            small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
